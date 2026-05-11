package com.hotwire.fisiontv.networkqual.update

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Wire-level tests for [OkHttpAppUpdateClient]. Same shape as
 * `OkHttpResultPublisherTest` — MockWebServer, exercise status-code
 * mapping, ETag round-trip, header propagation, parse failures.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OkHttpAppUpdateClientTest {

    private lateinit var server: MockWebServer

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After fun tearDown() {
        server.shutdown()
    }

    private fun client() = OkHttpAppUpdateClient(
        endpoint = server.url("/v1/app/version").toString(),
        authProvider = { "Bearer test-token" },
        deviceId = "00000000-0000-0000-0000-000000000001",
        appVersion = "0.5.0",
        appVersionCode = 50
    )

    private val sampleBody = """
        {
          "schemaVersion": 1,
          "latestVersionName": "0.7.1",
          "latestVersionCode": 71,
          "minRequiredVersionCode": 68,
          "apkUrl": "https://example.com/x.apk",
          "apkSizeBytes": 12345678,
          "apkSha256": "${"a".repeat(64)}",
          "signingCertSha256": "${"b".repeat(64)}",
          "releaseNotes": "n",
          "publishedAt": "2026-05-09T18:00:00Z"
        }
    """.trimIndent()

    @Test fun `200 with valid body maps to Updated`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(sampleBody))
        val outcome = client().fetch()
        assertThat(outcome).isInstanceOf(AppUpdateFetchOutcome.Updated::class.java)
        val m = (outcome as AppUpdateFetchOutcome.Updated).manifest
        assertThat(m.latestVersionCode).isEqualTo(71)
        assertThat(m.minRequiredVersionCode).isEqualTo(68)
    }

    @Test fun `304 maps to NotModified`() = runTest {
        server.enqueue(MockResponse().setResponseCode(304))
        val outcome = client().fetch()
        assertThat(outcome).isEqualTo(AppUpdateFetchOutcome.NotModified)
    }

    @Test fun `unparseable body maps to Error`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("not json"))
        val outcome = client().fetch()
        assertThat(outcome).isInstanceOf(AppUpdateFetchOutcome.Error::class.java)
        assertThat((outcome as AppUpdateFetchOutcome.Error).cause).contains("parse")
    }

    @Test fun `5xx maps to Error`() = runTest {
        server.enqueue(MockResponse().setResponseCode(503))
        val outcome = client().fetch()
        assertThat(outcome).isInstanceOf(AppUpdateFetchOutcome.Error::class.java)
        assertThat((outcome as AppUpdateFetchOutcome.Error).cause).contains("503")
    }

    @Test fun `schemaVersion above supported maps to Error`() = runTest {
        val futureBody = sampleBody.replace("\"schemaVersion\": 1", "\"schemaVersion\": 99")
        server.enqueue(MockResponse().setResponseCode(200).setBody(futureBody))
        val outcome = client().fetch()
        assertThat(outcome).isInstanceOf(AppUpdateFetchOutcome.Error::class.java)
        assertThat((outcome as AppUpdateFetchOutcome.Error).cause).contains("schemaVersion")
    }

    @Test fun `etag is cached and replayed as If-None-Match on next fetch`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(sampleBody).setHeader("ETag", "\"abc123\""))
        server.enqueue(MockResponse().setResponseCode(304))

        val c = client()
        val first = c.fetch()
        assertThat(first).isInstanceOf(AppUpdateFetchOutcome.Updated::class.java)
        val firstReq = server.takeRequest()
        assertThat(firstReq.getHeader("If-None-Match")).isNull()

        val second = c.fetch()
        assertThat(second).isEqualTo(AppUpdateFetchOutcome.NotModified)
        val secondReq = server.takeRequest()
        assertThat(secondReq.getHeader("If-None-Match")).isEqualTo("\"abc123\"")
    }

    @Test fun `headers identify the device and the installed version`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(sampleBody))
        client().fetch()
        val req = server.takeRequest()
        assertThat(req.getHeader("Authorization")).isEqualTo("Bearer test-token")
        assertThat(req.getHeader("X-Device-Id")).isEqualTo("00000000-0000-0000-0000-000000000001")
        assertThat(req.getHeader("X-App-Version")).isEqualTo("0.5.0")
        assertThat(req.getHeader("X-App-Version-Code")).isEqualTo("50")
    }
}
