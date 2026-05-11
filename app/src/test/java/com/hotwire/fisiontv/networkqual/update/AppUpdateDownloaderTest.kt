package com.hotwire.fisiontv.networkqual.update

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.MessageDigest

/**
 * Unit-level tests for [AppUpdateDownloader]. Use a real MockWebServer
 * so we exercise the HTTP path end-to-end and a TemporaryFolder for
 * the cache dir so the partial-file cleanup behavior is observable
 * against a real filesystem.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppUpdateDownloaderTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After fun tearDown() {
        server.shutdown()
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun manifest(
        bytes: ByteArray,
        url: String = server.url("/x.apk").toString(),
        sha: String = sha256Hex(bytes),
        signingSha: String = "b".repeat(64)
    ) = AppVersionManifest(
        schemaVersion = 1,
        latestVersionName = "0.7.1",
        latestVersionCode = 71,
        minRequiredVersionCode = 68,
        apkUrl = url,
        apkSizeBytes = bytes.size.toLong(),
        apkSha256 = sha,
        signingCertSha256 = signingSha,
        releaseNotes = null,
        publishedAt = null
    )

    @Test fun `happy path streams to disk and emits a Done progress with the apk file`() = runTest {
        val bytes = ByteArray(50_000) { it.toByte() }
        server.enqueue(MockResponse().setBody(Buffer().write(bytes)))

        val downloader = AppUpdateDownloader(tmp.root)
        val progress = downloader.download(manifest(bytes)).toList()

        val done = progress.last() as AppUpdateDownloader.Progress.Done
        assertThat(done.apkFile.exists()).isTrue()
        assertThat(done.apkFile.length()).isEqualTo(bytes.size.toLong())
        // Earlier elements should all be Downloading frames advancing toward 1.0.
        val downloading = progress.filterIsInstance<AppUpdateDownloader.Progress.Downloading>()
        assertThat(downloading).isNotEmpty()
        assertThat(downloading.last().fraction).isWithin(0.001f).of(1f)
    }

    @Test fun `sha256 mismatch throws IntegrityException and deletes the partial file`() = runTest {
        val bytes = ByteArray(10_000) { it.toByte() }
        server.enqueue(MockResponse().setBody(Buffer().write(bytes)))

        val downloader = AppUpdateDownloader(tmp.root)
        val wrongSha = "0".repeat(64)
        val t = runCatching {
            downloader.download(manifest(bytes, sha = wrongSha)).toList()
        }.exceptionOrNull()

        assertThat(t).isInstanceOf(AppUpdateDownloader.IntegrityException::class.java)
        assertThat(t!!.message).contains("sha256 mismatch")
        // Partial file must be cleaned up so the installer never sees it.
        val apkFile = java.io.File(tmp.root, "update-71.apk")
        assertThat(apkFile.exists()).isFalse()
    }

    @Test fun `size mismatch throws IntegrityException`() = runTest {
        // Server returns 10_000 bytes but manifest claims 20_000.
        val bytes = ByteArray(10_000) { it.toByte() }
        server.enqueue(MockResponse().setBody(Buffer().write(bytes)))

        val downloader = AppUpdateDownloader(tmp.root)
        val claimedLarger = manifest(bytes).copy(apkSizeBytes = 20_000L)

        val t = runCatching {
            downloader.download(claimedLarger).toList()
        }.exceptionOrNull()

        assertThat(t).isInstanceOf(AppUpdateDownloader.IntegrityException::class.java)
        assertThat(t!!.message).contains("size mismatch")
    }

    @Test fun `HTTP non-2xx surfaces as IOException`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        val downloader = AppUpdateDownloader(tmp.root)
        val bytes = ByteArray(100) { 0 }
        val t = runCatching {
            downloader.download(manifest(bytes)).toList()
        }.exceptionOrNull()
        assertThat(t).isInstanceOf(java.io.IOException::class.java)
        assertThat(t!!.message).contains("500")
    }
}
