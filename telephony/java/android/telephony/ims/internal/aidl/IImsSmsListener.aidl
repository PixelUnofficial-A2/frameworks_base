/*
 * Copyright (c) 2017 The Android Open Source Project
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

package android.telephony.ims.internal.aidl;

/**
 * See MMTelFeature for more information.
 * {@hide}
 */
interface IImsSmsListener {
    void onSendSmsResult(in int messageRef, in int status, in int reason);
    void onSmsStatusReportReceived(in int messageRef, in String format, in byte[] pdu);
    void onSmsReceived(in String format, in byte[] pdu);
}