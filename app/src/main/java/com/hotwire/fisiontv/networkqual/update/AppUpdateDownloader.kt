package com.hotwire.fisiontv.networkqual.update

import android.util.Log
import com.hotwire.fisiontv.networkqual.publish.OkHttpResultPublisher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Streams the APK bytes for a target [AppVersionManifest] into the app's
 * cache directory, hashing as it goes, and verifying integrity on
 * completion. Emits a [Progress] flow so the UI can render a real
 * percentage instead of an indeterminate spinner.
 *
 * On any verification failure (size mismatch, SHA mismatch, IO error,
 * cancellation) the partial file is deleted before the exception
 * propagates. This guarantees that a half-downloaded APK never makes it
 * to [AppUpdateInstaller].
 *
 * The downloader is **content-integrity only** — it does NOT verify the
 * signing certificate. That happens in [AppUpdateInstaller] after the
 * file is on disk, because cert verification needs to parse the APK
 * structure (signed-data block) and depends on Android's PackageManager.
 */
class AppUpdateDownloader(
    private val cacheDir: File,
    private val client: OkHttpClient = OkHttpResultPublisher.defaultClient()
) {

    sealed interface Progress {
        /** Bytes streamed so far + total expected (from manifest). */
        data class Downloading(val bytesRead: Long, val totalBytes: Long) : Progress {
            val fraction: Float get() = if (totalBytes > 0) (bytesRead.toFloat() / totalBytes) else 0f
        }

        /** Hash verified, bytes flushed. The APK is at [apkFile] and ready to install. */
        data class Done(val apkFile: File) : Progress
    }

    /**
     * Returned by [download] when verification fails. The file has been
     * deleted at this point — never trust it.
     */
    class IntegrityException(message: String) : IOException(message)

    /**
     * Streams [manifest.apkUrl] to disk and emits [Progress.Downloading]
     * updates followed by a final [Progress.Done].
     *
     * Throws [IntegrityException] for SHA / size mismatches,
     * [IOException] for network failures. Cancellation deletes the
     * partial file before propagating.
     */
    fun download(manifest: AppVersionManifest): Flow<Progress> = flow {
        if (!cacheDir.exists()) cacheDir.mkdirs()
        val apkFile = File(cacheDir, "update-${manifest.latestVersionCode}.apk")
        // Clean up any prior attempt's leftovers so we don't append.
        if (apkFile.exists() && !apkFile.delete()) {
            Log.w(TAG, "couldn't delete pre-existing ${apkFile.absolutePath}; will overwrite via stream")
        }

        val req = Request.Builder().url(manifest.apkUrl).build()
        val digest = MessageDigest.getInstance("SHA-256")
        var totalRead = 0L

        try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw IOException("HTTP ${resp.code} fetching APK")
                }
                val body = resp.body ?: throw IOException("empty body fetching APK")
                val source = body.byteStream()
                val sink = apkFile.outputStream()
                val buf = ByteArray(BUFFER_BYTES)

                sink.use { out ->
                    source.use { input ->
                        while (true) {
                            val n = input.read(buf)
                            if (n == -1) break
                            out.write(buf, 0, n)
                            digest.update(buf, 0, n)
                            totalRead += n
                            emit(Progress.Downloading(totalRead, manifest.apkSizeBytes))
                        }
                    }
                }
            }

            if (totalRead != manifest.apkSizeBytes) {
                throw IntegrityException("size mismatch: read $totalRead, manifest said ${manifest.apkSizeBytes}")
            }
            val actualSha = digest.digest().toHex()
            if (actualSha != manifest.apkSha256) {
                throw IntegrityException("sha256 mismatch: got $actualSha, manifest said ${manifest.apkSha256}")
            }

            Log.i(TAG, "downloaded ${manifest.latestVersionName} (${manifest.apkSizeBytes} B) sha=${actualSha.take(12)}…")
            emit(Progress.Done(apkFile))
        } catch (ce: CancellationException) {
            apkFile.delete()
            throw ce
        } catch (t: Throwable) {
            apkFile.delete()
            throw t
        }
    }.flowOn(Dispatchers.IO)

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it) }

    companion object {
        private const val TAG = "AppUpdateDownloader"
        // 64 KiB tuned for SD-card-class I/O on STB hardware; big enough to
        // amortize syscall overhead, small enough to emit Progress updates
        // smoothly for a 12-MB APK (~190 emissions).
        private const val BUFFER_BYTES = 64 * 1024
    }
}
