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

package android.util.apk;

import static android.content.pm.PackageManager.INSTALL_PARSE_FAILED_BAD_MANIFEST;
import static android.content.pm.PackageManager.INSTALL_PARSE_FAILED_CERTIFICATE_ENCODING;
import static android.content.pm.PackageManager.INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES;
import static android.content.pm.PackageManager.INSTALL_PARSE_FAILED_NO_CERTIFICATES;
import static android.content.pm.PackageManager.INSTALL_PARSE_FAILED_UNEXPECTED_EXCEPTION;
import static android.os.Trace.TRACE_TAG_PACKAGE_MANAGER;

import android.content.pm.PackageParser;
import android.content.pm.PackageParser.PackageParserException;
import android.content.pm.Signature;
import android.os.Trace;
import android.util.jar.StrictJarFile;

import com.android.internal.util.ArrayUtils;

import libcore.io.IoUtils;

import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;

/**
 * Facade class that takes care of the details of APK verification on
 * behalf of PackageParser.
 *
 * @hide for internal use only.
 */
public class ApkSignatureVerifier {

    public static final int VERSION_JAR_SIGNATURE_SCHEME = 1;
    public static final int VERSION_APK_SIGNATURE_SCHEME_V2 = 2;

    private static final AtomicReference<byte[]> sBuffer = new AtomicReference<>();

    /**
     * Verifies the provided APK and returns the certificates associated with each signer.
     *
     * @throws PackageParserException if the APK's signature failed to verify.
     */
    public static Result verify(String apkPath, int minSignatureSchemeVersion)
            throws PackageParserException {

        // first try v2
        Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "verifyV2");
        try {
            Certificate[][] signerCerts = ApkSignatureSchemeV2Verifier.verify(apkPath);
            Signature[] signerSigs = convertToSignatures(signerCerts);

            return new Result(signerCerts, signerSigs, VERSION_APK_SIGNATURE_SCHEME_V2);
        } catch (SignatureNotFoundException e) {
            // not signed with v2, try older if allowed
            if (minSignatureSchemeVersion >= VERSION_APK_SIGNATURE_SCHEME_V2) {
                throw new PackageParserException(INSTALL_PARSE_FAILED_NO_CERTIFICATES,
                        "No APK Signature Scheme v2 signature in package " + apkPath, e);
            }
        } catch (Exception e) {
            // APK Signature Scheme v2 signature found but did not verify
            throw new  PackageParserException(INSTALL_PARSE_FAILED_NO_CERTIFICATES,
                    "Failed to collect certificates from " + apkPath
                            + " using APK Signature Scheme v2", e);
        } finally {
            Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
        }

        // v2 didn't work, try jarsigner
        return verifyV1Signature(apkPath, true);
    }

    /**
     * Verifies the provided APK and returns the certificates associated with each signer.
     *
     * @param verifyFull whether to verify all contents of this APK or just collect certificates.
     *
     * @throws PackageParserException if there was a problem collecting certificates
     */
    private static Result verifyV1Signature(String apkPath, boolean verifyFull)
            throws PackageParserException {
        StrictJarFile jarFile = null;

        try {
            final Certificate[][] lastCerts;
            final Signature[] lastSigs;

            Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "strictJarFileCtor");

            // we still pass verify = true to ctor to collect certs, even though we're not checking
            // the whole jar.
            jarFile = new StrictJarFile(
                    apkPath,
                    true, // collect certs
                    verifyFull); // whether to reject APK with stripped v2 signatures (b/27887819)
            final List<ZipEntry> toVerify = new ArrayList<>();

            // Gather certs from AndroidManifest.xml, which every APK must have, as an optimization
            // to not need to verify the whole APK when verifyFUll == false.
            final ZipEntry manifestEntry = jarFile.findEntry(
                    PackageParser.ANDROID_MANIFEST_FILENAME);
            if (manifestEntry == null) {
                throw new PackageParserException(INSTALL_PARSE_FAILED_BAD_MANIFEST,
                        "Package " + apkPath + " has no manifest");
            }
            lastCerts = loadCertificates(jarFile, manifestEntry);
            if (ArrayUtils.isEmpty(lastCerts)) {
                throw new PackageParserException(INSTALL_PARSE_FAILED_NO_CERTIFICATES, "Package "
                        + apkPath + " has no certificates at entry "
                        + PackageParser.ANDROID_MANIFEST_FILENAME);
            }
            lastSigs = convertToSignatures(lastCerts);

            // fully verify all contents, except for AndroidManifest.xml  and the META-INF/ files.
            if (verifyFull) {
                final Iterator<ZipEntry> i = jarFile.iterator();
                while (i.hasNext()) {
                    final ZipEntry entry = i.next();
                    if (entry.isDirectory()) continue;

                    final String entryName = entry.getName();
                    if (entryName.startsWith("META-INF/")) continue;
                    if (entryName.equals(PackageParser.ANDROID_MANIFEST_FILENAME)) continue;

                    toVerify.add(entry);
                }

                for (ZipEntry entry : toVerify) {
                    final Certificate[][] entryCerts = loadCertificates(jarFile, entry);
                    if (ArrayUtils.isEmpty(entryCerts)) {
                        throw new PackageParserException(INSTALL_PARSE_FAILED_NO_CERTIFICATES,
                                "Package " + apkPath + " has no certificates at entry "
                                        + entry.getName());
                    }

                    // make sure all entries use the same signing certs
                    final Signature[] entrySigs = convertToSignatures(entryCerts);
                    if (!Signature.areExactMatch(lastSigs, entrySigs)) {
                        throw new PackageParserException(
                                INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES,
                                "Package " + apkPath + " has mismatched certificates at entry "
                                        + entry.getName());
                    }
                }
            }
            return new Result(lastCerts, lastSigs, VERSION_JAR_SIGNATURE_SCHEME);
        } catch (GeneralSecurityException e) {
            throw new PackageParserException(INSTALL_PARSE_FAILED_CERTIFICATE_ENCODING,
                    "Failed to collect certificates from " + apkPath, e);
        } catch (IOException | RuntimeException e) {
            throw new PackageParserException(INSTALL_PARSE_FAILED_NO_CERTIFICATES,
                    "Failed to collect certificates from " + apkPath, e);
        } finally {
            Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
            closeQuietly(jarFile);
        }
    }

    private static Certificate[][] loadCertificates(StrictJarFile jarFile, ZipEntry entry)
            throws PackageParserException {
        InputStream is = null;
        try {
            // We must read the stream for the JarEntry to retrieve
            // its certificates.
            is = jarFile.getInputStream(entry);
            readFullyIgnoringContents(is);
            return jarFile.getCertificateChains(entry);
        } catch (IOException | RuntimeException e) {
            throw new PackageParserException(INSTALL_PARSE_FAILED_UNEXPECTED_EXCEPTION,
                    "Failed reading " + entry.getName() + " in " + jarFile, e);
        } finally {
            IoUtils.closeQuietly(is);
        }
    }

    private static void readFullyIgnoringContents(InputStream in) throws IOException {
        byte[] buffer = sBuffer.getAndSet(null);
        if (buffer == null) {
            buffer = new byte[4096];
        }

        int n = 0;
        int count = 0;
        while ((n = in.read(buffer, 0, buffer.length)) != -1) {
            count += n;
        }

        sBuffer.set(buffer);
        return;
    }

    /**
     * Converts an array of certificate chains into the {@code Signature} equivalent used by the
     * PackageManager.
     *
     * @throws CertificateEncodingException if it is unable to create a Signature object.
     */
    public static Signature[] convertToSignatures(Certificate[][] certs)
            throws CertificateEncodingException {
        final Signature[] res = new Signature[certs.length];
        for (int i = 0; i < certs.length; i++) {
            res[i] = new Signature(certs[i]);
        }
        return res;
    }

    private static void closeQuietly(StrictJarFile jarFile) {
        if (jarFile != null) {
            try {
                jarFile.close();
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * Returns the certificates associated with each signer for the given APK without verification.
     * This method is dangerous and should not be used, unless the caller is absolutely certain the
     * APK is trusted.
     *
     * @throws PackageParserException if the APK's signature failed to verify.
     * or greater is not found, except in the case of no JAR signature.
     */
    public static Result plsCertsNoVerifyOnlyCerts(String apkPath, int minSignatureSchemeVersion)
            throws PackageParserException {

        // first try v2
        Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "certsOnlyV2");
        try {
            Certificate[][] signerCerts =
                    ApkSignatureSchemeV2Verifier.plsCertsNoVerifyOnlyCerts(apkPath);
            Signature[] signerSigs = convertToSignatures(signerCerts);
            return new Result(signerCerts, signerSigs, VERSION_APK_SIGNATURE_SCHEME_V2);
        } catch (SignatureNotFoundException e) {
            // not signed with v2, try older if allowed
            if (minSignatureSchemeVersion >= VERSION_APK_SIGNATURE_SCHEME_V2) {
                throw new PackageParserException(INSTALL_PARSE_FAILED_NO_CERTIFICATES,
                        "No APK Signature Scheme v2 signature in package " + apkPath, e);
            }
        } catch (Exception e) {
            // APK Signature Scheme v2 signature found but did not verify
            throw new  PackageParserException(INSTALL_PARSE_FAILED_NO_CERTIFICATES,
                    "Failed to collect certificates from " + apkPath
                            + " using APK Signature Scheme v2", e);
        } finally {
            Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
        }

        // v2 didn't work, try jarsigner
        return verifyV1Signature(apkPath, false);
    }

    /**
     * Result of a successful APK verification operation.
     */
    public static class Result {
        public final Certificate[][] certs;
        public final Signature[] sigs;
        public final int signatureSchemeVersion;

        public Result(Certificate[][] certs, Signature[] sigs, int signingVersion) {
            this.certs = certs;
            this.sigs = sigs;
            this.signatureSchemeVersion = signingVersion;
        }
    }
}
