package com.hotwire.fisiontv.networkqual.publish

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Wire-level tests for [OkHttpCertConfigClient]. Mirrors the style of
 * [OkHttpAppUpdateClientTest] — MockWebServer + RecordedRequest assertions
 * on the headers we promise the backend.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OkHttpCertConfigClientTest {

    private lateinit var server: MockWebServer

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After fun tearDown() {
        server.shutdown()
    }

    private fun client(
        manufacturer: String = "SEI Robotics",
        model: String = "SFT2200",
        buildFingerprint: String = "SEI/SFT2200/SFT2200:11/RTV-MR1/123:user/release-keys"
    ) = OkHttpCertConfigClient(
        endpoint = server.url("/v1/cert-config").toString(),
        authProvider = { _, _, _ -> "Bearer test-token" },
        deviceId = "00000000-0000-0000-0000-000000000001",
        appVersion = "0.7.0",
        manufacturer = manufacturer,
        model = model,
        buildFingerprint = buildFingerprint
    )

    private val sampleBody = """
        {
          "schemaVersion": 1,
          "configVersion": "2026-05-12.1",
          "tests": {
            "download": { "parallel": 4 },
            "upload":   { "parallel": 2 },
            "playback": { "manifestUrl": "https://x/y.mpd", "durationSec": 20 }
          },
          "tiers": [
            { "id": "sd", "displayName": "SD", "minDownloadMbps": 5,  "maxLatencyMs": 200, "maxJitterMs": 50, "minPlaybackHeight": 480 },
            { "id": "hd", "displayName": "HD", "minDownloadMbps": 12, "maxLatencyMs": 120, "maxJitterMs": 30, "minPlaybackHeight": 1080 }
          ],
          "uploadResults": { "enabled": true, "endpoint": "https://api.example/v1/certifications" }
        }
    """.trimIndent()

    @Test fun `targeting headers are sent when non-empty`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(sampleBody))
        client().fetch()
        val req = server.takeRequest()
        assertThat(req.getHeader("X-Device-Manufacturer")).isEqualTo("SEI Robotics")
        assertThat(req.getHeader("X-Device-Model")).isEqualTo("SFT2200")
        assertThat(req.getHeader("X-Device-Build-Fingerprint"))
            .isEqualTo("SEI/SFT2200/SFT2200:11/RTV-MR1/123:user/release-keys")
    }

    @Test fun `targeting headers are omitted when empty`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(sampleBody))
        client(manufacturer = "", model = "", buildFingerprint = "").fetch()
        val req = server.takeRequest()
        assertThat(req.getHeader("X-Device-Manufacturer")).isNull()
        assertThat(req.getHeader("X-Device-Model")).isNull()
        assertThat(req.getHeader("X-Device-Build-Fingerprint")).isNull()
    }

    @Test fun `existing identity headers still travel alongside the targeting hints`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(sampleBody))
        client().fetch()
        val req = server.takeRequest()
        assertThat(req.getHeader("Authorization")).isEqualTo("Bearer test-token")
        assertThat(req.getHeader("X-Device-Id")).isEqualTo("00000000-0000-0000-0000-000000000001")
        assertThat(req.getHeader("X-App-Version")).isEqualTo("0.7.0")
        assertThat(req.getHeader("X-Schema-Version")).isEqualTo("1")
    }

    @Test fun `partially-empty values drop only the empty ones`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(sampleBody))
        client(manufacturer = "Acme", model = "", buildFingerprint = "fp").fetch()
        val req = server.takeRequest()
        assertThat(req.getHeader("X-Device-Manufacturer")).isEqualTo("Acme")
        assertThat(req.getHeader("X-Device-Model")).isNull()
        assertThat(req.getHeader("X-Device-Build-Fingerprint")).isEqualTo("fp")
    }
}
