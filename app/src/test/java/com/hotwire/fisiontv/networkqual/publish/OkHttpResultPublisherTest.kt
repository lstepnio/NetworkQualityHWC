package com.hotwire.fisiontv.networkqual.publish

import com.google.common.truth.Truth.assertThat
import com.hotwire.fisiontv.networkqual.cert.CertificationResult
import com.hotwire.fisiontv.networkqual.cert.HealthAssessor
import com.hotwire.fisiontv.networkqual.cert.ServerProbe
import com.hotwire.fisiontv.networkqual.cert.TierEvaluator
import com.hotwire.fisiontv.networkqual.cert.WifiLinkQualityAssessor
import com.hotwire.fisiontv.networkqual.cert.probes.DnsResult
import com.hotwire.fisiontv.networkqual.cert.probes.DnsSample
import com.hotwire.fisiontv.networkqual.cert.probes.LatencyResult
import com.hotwire.fisiontv.networkqual.config.RuntimeConfigDefaults
import com.hotwire.fisiontv.networkqual.fixtures.FakeDiagnostics
import com.hotwire.fisiontv.networkqual.fixtures.Fixtures
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Wire-level tests for the publisher: real HTTP via MockWebServer,
 * exercising status-code mapping and retry semantics. Catches
 * regressions where, e.g., changing the OkHttp client config silently
 * breaks header propagation or where retry logic fails to back off
 * correctly on 5xx.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OkHttpResultPublisherTest {

    private lateinit var server: MockWebServer

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After fun tearDown() {
        server.shutdown()
    }

    private fun publisher(maxAttempts: Int = 3, baseBackoffMs: Long = 1L) =
        OkHttpResultPublisher(
            endpoint = server.url("/v1/certifications").toString(),
            authProvider = { "Bearer test-token" },
            deviceId = "00000000-0000-0000-0000-000000000001",
            appVersion = "0.0.0-test",
            schemaVersion = 1,
            maxAttempts = maxAttempts,
            baseBackoffMs = baseBackoffMs
        )

    private fun sampleResult(): CertificationResult {
        val cfg = RuntimeConfigDefaults.bundled
        val download = Fixtures.throughput(steady = 100.0)
        val latency = LatencyResult(samples = listOf(30L), medianMs = 30, jitterMs = 3)
        val playback = Fixtures.playback(peakHeight = 1080)
        val outcome = TierEvaluator(cfg.tiers).evaluate(latency, download, playback)
        val health = HealthAssessor(cfg.tiers, cfg.healthAssessment).assess(outcome.achieved, download, latency)
        val diagnostics = FakeDiagnostics.build()
        val wifiLink = diagnostics.wifi?.let { WifiLinkQualityAssessor(cfg.wifiLinkQuality).assess(it) }
        return CertificationResult(
            certificationId = "cert-id-1",
            configVersion = cfg.configVersion,
            startedAtMs = 1L,
            timestampMs = 2L,
            achievedTier = outcome.achieved,
            selectedServer = Fixtures.server(),
            selectedServerRttMs = 50L,
            serverProbes = listOf(ServerProbe("test", "Test", "test.example.com", 50L, ok = true, selected = true)),
            dns = DnsResult(
                medianMs = 20L, maxMs = 25L, failureCount = 0,
                samples = listOf(DnsSample("example.com", 20L, true, listOf("1.2.3.4")))
            ),
            latency = latency,
            download = download,
            upload = Fixtures.throughput(steady = 50.0, durationSec = 5),
            playback = playback,
            tierBreakdown = outcome.breakdown,
            diagnostics = diagnostics,
            health = health,
            wifiLink = wifiLink
        )
    }

    @Test fun `201 maps to Success`() = runTest {
        server.enqueue(MockResponse().setResponseCode(201))
        val outcome = publisher().publish(sampleResult())
        assertThat(outcome).isEqualTo(PublishOutcome.Success)
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test fun `200 maps to Duplicate`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200))
        val outcome = publisher().publish(sampleResult())
        assertThat(outcome).isEqualTo(PublishOutcome.Duplicate)
    }

    @Test fun `4xx maps to PermanentFailure and does not retry`() = runTest {
        server.enqueue(MockResponse().setResponseCode(400))
        server.enqueue(MockResponse().setResponseCode(201)) // would succeed if we retried — we shouldn't
        val outcome = publisher().publish(sampleResult())
        assertThat(outcome).isInstanceOf(PublishOutcome.PermanentFailure::class.java)
        assertThat((outcome as PublishOutcome.PermanentFailure).httpStatus).isEqualTo(400)
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test fun `5xx retries and eventually succeeds`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setResponseCode(201))
        val outcome = publisher().publish(sampleResult())
        assertThat(outcome).isEqualTo(PublishOutcome.Success)
        assertThat(server.requestCount).isEqualTo(3)
    }

    @Test fun `5xx exhausts retries and surfaces TransientFailure`() = runTest {
        repeat(3) { server.enqueue(MockResponse().setResponseCode(500)) }
        val outcome = publisher().publish(sampleResult())
        assertThat(outcome).isInstanceOf(PublishOutcome.TransientFailure::class.java)
        assertThat(server.requestCount).isEqualTo(3)
    }

    @Test fun `auth and device headers are sent on every request`() = runTest {
        server.enqueue(MockResponse().setResponseCode(201))
        publisher().publish(sampleResult())
        val recorded = server.takeRequest()
        assertThat(recorded.getHeader("Authorization")).isEqualTo("Bearer test-token")
        assertThat(recorded.getHeader("X-Device-Id")).isEqualTo("00000000-0000-0000-0000-000000000001")
        assertThat(recorded.getHeader("X-App-Version")).isEqualTo("0.0.0-test")
        assertThat(recorded.getHeader("X-Schema-Version")).isEqualTo("1")
        assertThat(recorded.getHeader("Content-Type")).contains("application/json")
    }

    @Test fun `request body is the spec-shape JSON payload`() = runTest {
        server.enqueue(MockResponse().setResponseCode(201))
        publisher().publish(sampleResult())
        val recorded = server.takeRequest()
        val body = recorded.body.readUtf8()
        // org.json toString() produces compact JSON (no whitespace).
        assertThat(body).contains("\"certificationId\":\"cert-id-1\"")
        assertThat(body).contains("\"schemaVersion\":1")
        assertThat(body).contains("\"configVersion\"")
    }
}
