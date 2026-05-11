package com.hotwire.fisiontv.networkqual.update

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.hotwire.fisiontv.networkqual.BuildConfig
import java.io.File
import java.security.MessageDigest

/**
 * Installs a verified APK via the platform [PackageInstaller].
 *
 * Pre-install verification (all-or-nothing):
 *   1. The APK's signing certificate SHA-256 matches `BuildConfig.APP_SIGNING_CERT_SHA256`
 *      AND matches the manifest's `signingCertSha256`. This is the
 *      key defense against a compromised API host.
 *   2. The APK's `versionCode` is strictly greater than the installed
 *      versionCode (no downgrades).
 *
 * Install flow:
 *   - Open a [PackageInstaller.Session]
 *   - Stream the verified APK bytes in
 *   - Commit with a [PendingIntent] targeting [UpdateInstallReceiver]
 *   - PackageInstaller either installs silently (if we hold
 *     INSTALL_PACKAGES) or emits `STATUS_PENDING_USER_ACTION` so the
 *     receiver can launch the system "Install update?" dialog
 *   - When done, the receiver fires with `STATUS_SUCCESS` /
 *     `STATUS_FAILURE`
 *
 * Silent vs. interactive is decided at **runtime** by whether the
 * platform actually granted [Manifest.permission.INSTALL_PACKAGES]
 * (a signature permission only granted to apps signed with the
 * platform key). The same APK works in both modes: a sideloaded
 * build sees no grant and falls back to the system confirm dialog;
 * a firmware-embedded, platform-signed build of the same APK gets
 * the grant and installs silently. No build-variant coordination
 * required when the OEM signing path lands.
 */
class AppUpdateInstaller(private val context: Context) {

    sealed interface VerifyOutcome {
        data object Ok : VerifyOutcome
        data class Reject(val reason: String) : VerifyOutcome
    }

    /**
     * Result returned by [beginInstall] before the actual install runs.
     * SUCCESS / FAILURE arrive later as broadcasts to [UpdateInstallReceiver].
     */
    sealed interface BeginOutcome {
        /** Session created and bytes committed. Watch the receiver for the actual install result. */
        data object Pending : BeginOutcome

        /** Pre-install verification failed; no install was attempted. */
        data class Refused(val reason: String) : BeginOutcome

        /** Couldn't even open the install session (no permission, ENOSPC, etc). */
        data class Failed(val cause: String) : BeginOutcome
    }

    /**
     * Runs pre-install integrity checks. Call this on the downloaded
     * APK before [beginInstall]. Separated out so it's unit-testable
     * without an Android context (well, almost — it does use
     * PackageManager, but the surface is mockable).
     */
    fun verify(apkFile: File, manifest: AppVersionManifest): VerifyOutcome {
        val pkg = readApkInfo(apkFile)
            ?: return VerifyOutcome.Reject("could not read APK package info")

        // 1. versionCode monotonicity. Refuse downgrades or sideways moves.
        val installedCode = installedVersionCode()
        val apkCode = pkg.longVersionCodeCompat()
        if (apkCode <= installedCode) {
            return VerifyOutcome.Reject(
                "downgrade refused: apk=$apkCode <= installed=$installedCode"
            )
        }
        if (apkCode.toInt() != manifest.latestVersionCode) {
            return VerifyOutcome.Reject(
                "versionCode mismatch: apk=$apkCode, manifest=${manifest.latestVersionCode}"
            )
        }

        // 2. signingCertSha256 — checked against both the pinned compile-time
        //    value AND the manifest. Belt-and-suspenders: a compromised API
        //    can't redirect to a malicious signer because the compile-time
        //    pin is hard-coded in firmware; a compromised compile-time pin
        //    is unreachable from this code path (the device would have to
        //    be rebuilt).
        val pinnedSha = BuildConfig.APP_SIGNING_CERT_SHA256.lowercase()
        if (pinnedSha.isNotBlank() && manifest.signingCertSha256 != pinnedSha) {
            return VerifyOutcome.Reject(
                "manifest signingCertSha256 (${manifest.signingCertSha256.take(12)}…) != pinned (${pinnedSha.take(12)}…)"
            )
        }
        val apkSigSha = apkSigningCertSha256(apkFile)
            ?: return VerifyOutcome.Reject("could not extract APK signing cert")
        if (apkSigSha != manifest.signingCertSha256) {
            return VerifyOutcome.Reject(
                "apk signingCertSha256 (${apkSigSha.take(12)}…) != manifest (${manifest.signingCertSha256.take(12)}…)"
            )
        }
        if (pinnedSha.isNotBlank() && apkSigSha != pinnedSha) {
            return VerifyOutcome.Reject(
                "apk signingCertSha256 (${apkSigSha.take(12)}…) != pinned (${pinnedSha.take(12)}…)"
            )
        }

        Log.i(TAG, "verify ok: code=$apkCode signingCert=${apkSigSha.take(12)}…")
        return VerifyOutcome.Ok
    }

    /**
     * Opens a PackageInstaller session, streams the APK bytes in, and
     * commits. The actual install result arrives later as a broadcast
     * to [UpdateInstallReceiver].
     *
     * Must only be called after [verify] returns Ok.
     */
    fun beginInstall(apkFile: File, manifest: AppVersionManifest): BeginOutcome {
        val pi = context.packageManager.packageInstaller
        val silent = canInstallSilently()
        Log.i(TAG, "begin install (silent=$silent) for code=${manifest.latestVersionCode}")

        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL
        ).apply {
            setAppPackageName(context.packageName)
            // On S+ the OS gates silent install behind setRequireUserAction()
            // EVEN when the caller holds INSTALL_PACKAGES. Set NOT_REQUIRED
            // only when we actually have the permission; otherwise the OS
            // will refuse the session. On pre-S the API doesn't exist and
            // the permission grant alone controls silent-vs-prompt.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(
                    if (silent) PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED
                    else PackageInstaller.SessionParams.USER_ACTION_REQUIRED
                )
            }
        }

        val sessionId = try {
            pi.createSession(params)
        } catch (t: Throwable) {
            return BeginOutcome.Failed("createSession: ${t::class.simpleName}: ${t.message}")
        }

        try {
            pi.openSession(sessionId).use { session ->
                session.openWrite(
                    "fision-update-${manifest.latestVersionCode}",
                    0L,
                    apkFile.length()
                ).use { sessionOut ->
                    apkFile.inputStream().use { fileIn ->
                        fileIn.copyTo(sessionOut)
                    }
                    session.fsync(sessionOut)
                }

                val intent = Intent(context, UpdateInstallReceiver::class.java).apply {
                    action = UpdateInstallReceiver.ACTION_INSTALL_RESULT
                }
                val pending = PendingIntent.getBroadcast(
                    context,
                    sessionId,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                )
                session.commit(pending.intentSender)
            }
        } catch (t: Throwable) {
            try {
                pi.abandonSession(sessionId)
            } catch (_: Throwable) { /* best-effort */ }
            return BeginOutcome.Failed("commit: ${t::class.simpleName}: ${t.message}")
        }

        return BeginOutcome.Pending
    }

    /**
     * Runtime probe for the silent-install path. `INSTALL_PACKAGES` is a
     * signature-protected permission; the OS only grants it to apps
     * signed with the platform key (i.e., when shipped as a system app
     * baked into firmware). Sideloaded builds of the same APK return
     * false here and fall back to the [Manifest.permission.REQUEST_INSTALL_PACKAGES]
     * intent flow + system "Install update?" dialog. No build-variant
     * gymnastics — the same binary works in both modes.
     */
    fun canInstallSilently(): Boolean =
        context.checkSelfPermission(Manifest.permission.INSTALL_PACKAGES) ==
            PackageManager.PERMISSION_GRANTED

    private fun readApkInfo(apkFile: File): PackageInfo? {
        @Suppress("DEPRECATION")
        val flags =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES
            else PackageManager.GET_SIGNATURES
        return context.packageManager.getPackageArchiveInfo(apkFile.absolutePath, flags)
    }

    @Suppress("DEPRECATION")
    private fun PackageInfo.longVersionCodeCompat(): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) longVersionCode
        else versionCode.toLong()

    /**
     * Hex SHA-256 of the APK's signing certificate(s). For the
     * common single-signer case the value is unique to the keystore;
     * APK Signature Scheme v3 rotation produces multiple certs, in
     * which case we hash the concatenation (deterministic ordering).
     */
    private fun apkSigningCertSha256(apkFile: File): String? {
        val info = readApkInfo(apkFile) ?: return null
        val certBytes: ByteArray = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signers = info.signingInfo ?: return null
            val sigs = if (signers.hasMultipleSigners()) signers.apkContentsSigners
                       else signers.signingCertificateHistory
            if (sigs.isNullOrEmpty()) return null
            sigs.fold(ByteArray(0)) { acc, sig -> acc + sig.toByteArray() }
        } else {
            @Suppress("DEPRECATION")
            val sigs = info.signatures ?: return null
            if (sigs.isEmpty()) return null
            sigs.fold(ByteArray(0)) { acc, sig -> acc + sig.toByteArray() }
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(certBytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun installedVersionCode(): Long {
        val pkg = context.packageManager.getPackageInfo(context.packageName, 0)
        @Suppress("DEPRECATION")
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pkg.longVersionCode
            else pkg.versionCode.toLong()
    }

    companion object {
        private const val TAG = "AppUpdateInstaller"
    }
}
