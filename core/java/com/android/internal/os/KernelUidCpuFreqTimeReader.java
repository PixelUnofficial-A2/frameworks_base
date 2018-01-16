/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.internal.os;

import static com.android.internal.util.Preconditions.checkNotNull;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.StrictMode;
import android.os.SystemClock;
import android.util.IntArray;
import android.util.Slog;
import android.util.SparseArray;
import android.util.TimeUtils;

import com.android.internal.annotations.VisibleForTesting;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;

/**
 * Reads /proc/uid_time_in_state which has the format:
 *
 * uid: [freq1] [freq2] [freq3] ...
 * [uid1]: [time in freq1] [time in freq2] [time in freq3] ...
 * [uid2]: [time in freq1] [time in freq2] [time in freq3] ...
 * ...
 *
 * This provides the times a UID's processes spent executing at each different cpu frequency.
 * The file contains a monotonically increasing count of time for a single boot. This class
 * maintains the previous results of a call to {@link #readDelta} in order to provide a proper
 * delta.
 */
public class KernelUidCpuFreqTimeReader {
    private static final boolean DEBUG = false;
    private static final String TAG = "KernelUidCpuFreqTimeReader";
    private static final String UID_TIMES_PROC_FILE = "/proc/uid_time_in_state";

    public interface Callback {
        void onUidCpuFreqTime(int uid, long[] cpuFreqTimeMs);
    }

    private long[] mCpuFreqs;
    private int mCpuFreqsCount;
    private long mLastTimeReadMs;
    private long mNowTimeMs;

    private SparseArray<long[]> mLastUidCpuFreqTimeMs = new SparseArray<>();

    // We check the existence of proc file a few times (just in case it is not ready yet when we
    // start reading) and if it is not available, we simply ignore further read requests.
    private static final int TOTAL_READ_ERROR_COUNT = 5;
    private int mReadErrorCounter;
    private boolean mPerClusterTimesAvailable;
    private boolean mAllUidTimesAvailable = true;

    public boolean perClusterTimesAvailable() {
        return mPerClusterTimesAvailable;
    }

    public boolean allUidTimesAvailable() {
        return mAllUidTimesAvailable;
    }

    public SparseArray<long[]> getAllUidCpuFreqTimeMs() {
        return mLastUidCpuFreqTimeMs;
    }

    public long[] readFreqs(@NonNull PowerProfile powerProfile) {
        checkNotNull(powerProfile);

        if (mCpuFreqs != null) {
            // No need to read cpu freqs more than once.
            return mCpuFreqs;
        }
        if (!mAllUidTimesAvailable) {
            return null;
        }
        final int oldMask = StrictMode.allowThreadDiskReadsMask();
        try (BufferedReader reader = new BufferedReader(new FileReader(UID_TIMES_PROC_FILE))) {
            return readFreqs(reader, powerProfile);
        } catch (IOException e) {
            if (++mReadErrorCounter >= TOTAL_READ_ERROR_COUNT) {
                mAllUidTimesAvailable = false;
            }
            Slog.e(TAG, "Failed to read " + UID_TIMES_PROC_FILE + ": " + e);
            return null;
        } finally {
            StrictMode.setThreadPolicyMask(oldMask);
        }
    }

    @VisibleForTesting
    public long[] readFreqs(BufferedReader reader, PowerProfile powerProfile)
            throws IOException {
        final String line = reader.readLine();
        if (line == null) {
            return null;
        }
        return readCpuFreqs(line, powerProfile);
    }

    public void readDelta(@Nullable Callback callback) {
        if (mCpuFreqs == null) {
            return;
        }
        final int oldMask = StrictMode.allowThreadDiskReadsMask();
        try (BufferedReader reader = new BufferedReader(new FileReader(UID_TIMES_PROC_FILE))) {
            mNowTimeMs = SystemClock.elapsedRealtime();
            readDelta(reader, callback);
            mLastTimeReadMs = mNowTimeMs;
        } catch (IOException e) {
            Slog.e(TAG, "Failed to read " + UID_TIMES_PROC_FILE + ": " + e);
        } finally {
            StrictMode.setThreadPolicyMask(oldMask);
        }
    }

    public void removeUid(int uid) {
        mLastUidCpuFreqTimeMs.delete(uid);
    }

    public void removeUidsInRange(int startUid, int endUid) {
        if (endUid < startUid) {
            return;
        }
        mLastUidCpuFreqTimeMs.put(startUid, null);
        mLastUidCpuFreqTimeMs.put(endUid, null);
        final int firstIndex = mLastUidCpuFreqTimeMs.indexOfKey(startUid);
        final int lastIndex = mLastUidCpuFreqTimeMs.indexOfKey(endUid);
        mLastUidCpuFreqTimeMs.removeAtRange(firstIndex, lastIndex - firstIndex + 1);
    }

    @VisibleForTesting
    public void readDelta(BufferedReader reader, @Nullable Callback callback) throws IOException {
        String line = reader.readLine();
        if (line == null) {
            return;
        }
        while ((line = reader.readLine()) != null) {
            final int index = line.indexOf(' ');
            final int uid = Integer.parseInt(line.substring(0, index - 1), 10);
            readTimesForUid(uid, line.substring(index + 1, line.length()), callback);
        }
    }

    private void readTimesForUid(int uid, String line, Callback callback) {
        long[] uidTimeMs = mLastUidCpuFreqTimeMs.get(uid);
        if (uidTimeMs == null) {
            uidTimeMs = new long[mCpuFreqsCount];
            mLastUidCpuFreqTimeMs.put(uid, uidTimeMs);
        }
        final String[] timesStr = line.split(" ");
        final int size = timesStr.length;
        if (size != uidTimeMs.length) {
            Slog.e(TAG, "No. of readings don't match cpu freqs, readings: " + size
                    + " cpuFreqsCount: " + uidTimeMs.length);
            return;
        }
        final long[] deltaUidTimeMs = new long[size];
        final long[] curUidTimeMs = new long[size];
        boolean notify = false;
        for (int i = 0; i < size; ++i) {
            // Times read will be in units of 10ms
            final long totalTimeMs = Long.parseLong(timesStr[i], 10) * 10;
            deltaUidTimeMs[i] = totalTimeMs - uidTimeMs[i];
            // If there is malformed data for any uid, then we just log about it and ignore
            // the data for that uid.
            if (deltaUidTimeMs[i] < 0 || totalTimeMs < 0) {
                if (DEBUG) {
                    final StringBuilder sb = new StringBuilder("Malformed cpu freq data for UID=")
                            .append(uid).append("\n");
                    sb.append("data=").append("(").append(uidTimeMs[i]).append(",")
                            .append(totalTimeMs).append(")").append("\n");
                    sb.append("times=").append("(");
                    TimeUtils.formatDuration(mLastTimeReadMs, sb);
                    sb.append(",");
                    TimeUtils.formatDuration(mNowTimeMs, sb);
                    sb.append(")");
                    Slog.e(TAG, sb.toString());
                }
                return;
            }
            curUidTimeMs[i] = totalTimeMs;
            notify = notify || (deltaUidTimeMs[i] > 0);
        }
        if (notify) {
            System.arraycopy(curUidTimeMs, 0, uidTimeMs, 0, size);
            if (callback != null) {
                callback.onUidCpuFreqTime(uid, deltaUidTimeMs);
            }
        }
    }

    private long[] readCpuFreqs(String line, PowerProfile powerProfile) {
        final String[] freqStr = line.split(" ");
        // First item would be "uid: " which needs to be ignored.
        mCpuFreqsCount = freqStr.length - 1;
        mCpuFreqs = new long[mCpuFreqsCount];
        for (int i = 0; i < mCpuFreqsCount; ++i) {
            mCpuFreqs[i] = Long.parseLong(freqStr[i + 1], 10);
        }

        // Check if the freqs in the proc file correspond to per-cluster freqs.
        final IntArray numClusterFreqs = extractClusterInfoFromProcFileFreqs();
        final int numClusters = powerProfile.getNumCpuClusters();
        if (numClusterFreqs.size() == numClusters) {
            mPerClusterTimesAvailable = true;
            for (int i = 0; i < numClusters; ++i) {
                if (numClusterFreqs.get(i) != powerProfile.getNumSpeedStepsInCpuCluster(i)) {
                    mPerClusterTimesAvailable = false;
                    break;
                }
            }
        } else {
            mPerClusterTimesAvailable = false;
        }
        Slog.i(TAG, "mPerClusterTimesAvailable=" + mPerClusterTimesAvailable);

        return mCpuFreqs;
    }

    /**
     * Extracts no. of cpu clusters and no. of freqs in each of these clusters from the freqs
     * read from the proc file.
     *
     * We need to assume that freqs in each cluster are strictly increasing.
     * For e.g. if the freqs read from proc file are: 12, 34, 15, 45, 12, 15, 52. Then it means
     * there are 3 clusters: (12, 34), (15, 45), (12, 15, 52)
     *
     * @return an IntArray filled with no. of freqs in each cluster.
     */
    private IntArray extractClusterInfoFromProcFileFreqs() {
        final IntArray numClusterFreqs = new IntArray();
        int freqsFound = 0;
        for (int i = 0; i < mCpuFreqsCount; ++i) {
            freqsFound++;
            if (i + 1 == mCpuFreqsCount || mCpuFreqs[i + 1] <= mCpuFreqs[i]) {
                numClusterFreqs.add(freqsFound);
                freqsFound = 0;
            }
        }
        return numClusterFreqs;
    }
}
