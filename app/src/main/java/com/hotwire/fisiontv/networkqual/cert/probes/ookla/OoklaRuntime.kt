package com.hotwire.fisiontv.networkqual.cert.probes.ookla

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Locates the bundled Ookla binary + CA bundle on the device.
 *
 * The binary ships in jniLibs/armeabi-v7a/libookla.so so AGP installs it
 * to applicationInfo.nativeLibraryDir at app-install time, where SELinux
 * allows untrusted_app to execute it. The CA bundle ships as an asset
 * (cacert.pem) and gets extracted to filesDir on first use.
 *
 * Both paths are stable for the lifetime of the install — no need to
 * refresh between runs.
 */
class OoklaRuntime(context: Context) {

    val binaryPath: String = File(context.applicationInfo.nativeLibraryDir, "libookla.so").absolutePath

    val caBundlePath: String = ensureCaBundle(context)

    init {
        if (!File(binaryPath).exists()) {
            Log.e(TAG, "ookla binary missing at $binaryPath — APK packaging issue?")
        }
    }

    private fun ensureCaBundle(context: Context): String {
        val target = File(context.filesDir, CA_BUNDLE_FILE)
        if (target.exists() && target.length() > 0L) return target.absolutePath
        try {
            context.assets.open(CA_BUNDLE_FILE).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            Log.i(TAG, "extracted $CA_BUNDLE_FILE -> ${target.absolutePath} (${target.length()} bytes)")
        } catch (t: Throwable) {
            Log.e(TAG, "failed to extract CA bundle: ${t::class.simpleName}: ${t.message}")
        }
        return target.absolutePath
    }

    companion object {
        private const val TAG = "OoklaRuntime"
        private const val CA_BUNDLE_FILE = "cacert.pem"
    }
}
