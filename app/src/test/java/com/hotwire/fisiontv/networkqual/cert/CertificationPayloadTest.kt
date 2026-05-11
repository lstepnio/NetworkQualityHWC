package com.hotwire.fisiontv.networkqual.cert

import com.google.common.truth.Truth.assertThat
import com.hotwire.fisiontv.networkqual.config.RuntimeConfigDefaults
import com.hotwire.fisiontv.networkqual.fixtures.FakeDiagnostics
import com.hotwire.fisiontv.networkqual.fixtures.Fixtures
import com.hotwire.fisiontv.networkqual.cert.probes.DnsResult
import com.hotwire.fisiontv.networkqual.cert.probes.DnsSample
import com.hotwire.fisiontv.networkqual.cert.probes.LatencyResult
import org.json.JSONObject
import org.junit.Test

/**
 * Locks in the JSON payload shape against the openapi.yaml contract.
 * If a backend dev pulls fixtures/certification.example.json from the
 * contract repo, this is what their server will see.
 */
class CertificationPayloadTest {

    private val config = RuntimeConfigDefaults.bundled

    private fun sampleResult(): CertificationResult {
        val tiers = config.tiers
        val download = Fixtures.throughput(steady = 200.0)
        val latency = LatencyResult(
            samples = listOf(30L, 32L, 31L),
            medianMs = 31, p95Ms = 32, jitterMs = 2,
            attempted = 3, lossPct = 0
        )
        val playback = Fixtures.playback(peakHeight = 1080)
        val outcome = TierEvaluator(tiers).evaluate(latency, download, playback)
        val health = HealthAssessor(tiers, config.healthAssessment).assess(outcome.networkAchieved, download, latency)
        val diagnostics = FakeDiagnostics.build()
        val wifiLink = diagnostics.wifi?.let { WifiLinkQualityAssessor(config.wifiLinkQuality).assess(it) }

        return CertificationResult(
            certificationId = "00000000-0000-0000-0000-000000000abc",
            configVersion = config.configVersion,
            startedAtMs = 1_700_000_000_000L,
            timestampMs = 1_700_000_060_000L,
            achievedTier = outcome.networkAchieved,
            playbackAchievedTier = outcome.playbackAchieved,
            selectedServer = Fixtures.server(id = "apf", host = "speedtestapf.gethotwired.com"),
            selectedServerRttMs = 50L,
            serverProbes = listOf(
                ServerProbe(id = "apf", name = "APF", host = "speedtestapf.gethotwired.com", rttMs = 50L, ok = true, selected = true)
            ),
            dns = DnsResult(
                medianMs = 20L, p95Ms = 28L, maxMs = 30L, failureCount = 0,
                samples = listOf(DnsSample("example.com", 20L, true, listOf("1.2.3.4")))
            ),
            latency = latency,
            download = download,
            upload = Fixtures.throughput(steady = 100.0, durationSec = 5),
            playback = playback,
            tierBreakdown = outcome.breakdown,
            diagnostics = diagnostics,
            health = health,
            wifiLink = wifiLink
        )
    }

    @Test fun `top-level required fields populated`() {
        val json = CertificationPayload.toJson(sampleResult())
        assertThat(json.getInt("schemaVersion")).isEqualTo(1)
        assertThat(json.getString("configVersion")).isNotEmpty()
        assertThat(json.getString("certificationId")).isNotEmpty()
        assertThat(json.getString("deviceId")).isNotEmpty()
        assertThat(json.getString("startedAt")).contains("T")
        assertThat(json.getString("completedAt")).contains("T")
    }

    @Test fun `device, identity, capabilities, network, wifi, result, metrics blocks all present`() {
        val json = CertificationPayload.toJson(sampleResult())
        val expected = listOf("device", "identity", "capabilities", "network", "wifi", "result", "metrics")
        expected.forEach {
            assertThat(json.has(it)).isTrue()
        }
    }

    @Test fun `selectedServer uses the id field, not a parsed host`() {
        val json = CertificationPayload.toJson(sampleResult())
        val sel = json.getJSONObject("metrics").getJSONObject("selectedServer")
        assertThat(sel.getString("id")).isEqualTo("apf")
        assertThat(sel.getString("host")).isEqualTo("speedtestapf.gethotwired.com")
    }

    @Test fun `tier ids in result use spec lowercase form, not enum names`() {
        val json = CertificationPayload.toJson(sampleResult())
        val achieved = json.getJSONObject("result").getString("achievedTier")
        assertThat(achieved).isAnyOf("sd", "hd", "uhd", "uhd_hdr", "none")
        val breakdown = json.getJSONObject("result").getJSONArray("tierBreakdown")
        for (i in 0 until breakdown.length()) {
            val id = breakdown.getJSONObject(i).getString("tierId")
            assertThat(id).isAnyOf("sd", "hd", "uhd", "uhd_hdr", "none")
        }
    }

    @Test fun `wifiLink is null when transport is ethernet`() {
        val ethResult = sampleResult().copy(
            diagnostics = FakeDiagnostics.build(transport = com.hotwire.fisiontv.networkqual.diagnostics.Transport.ETHERNET),
            wifiLink = null
        )
        val json = CertificationPayload.toJson(ethResult)
        assertThat(json.getJSONObject("result").isNull("wifiLink")).isTrue()
        assertThat(json.isNull("wifi")).isTrue()
    }

    @Test fun `payload survives round-trip through JSONObject parse`() {
        val source = CertificationPayload.toJson(sampleResult()).toString()
        val parsed = JSONObject(source)
        assertThat(parsed.getString("certificationId")).isEqualTo("00000000-0000-0000-0000-000000000abc")
    }

    @Test fun `stampSubmission adds submittedAt and enqueuedAt without touching cert timestamps`() {
        val frozen = CertificationPayload.toJson(sampleResult()).toString()
        val stamped = CertificationPayload.stampSubmission(
            payloadJson = frozen,
            submittedAtMs = 1_700_000_900_000L, // ~14 min after completedAt
            enqueuedAtMs = 1_700_000_065_000L   // ~5 s after completedAt
        )
        val parsed = JSONObject(stamped)
        // Cert run timestamps must survive untouched — these are what the
        // backend should key cert storage off of.
        assertThat(parsed.getString("startedAt")).isEqualTo("2023-11-14T22:13:20Z")
        assertThat(parsed.getString("completedAt")).isEqualTo("2023-11-14T22:14:20Z")
        // Submission timestamps live alongside, not on top.
        assertThat(parsed.getString("enqueuedAt")).isEqualTo("2023-11-14T22:14:25Z")
        assertThat(parsed.getString("submittedAt")).isEqualTo("2023-11-14T22:28:20Z")
    }

    @Test fun `stampSubmission returns input unchanged when payload is malformed`() {
        val junk = "not-json-at-all"
        val stamped = CertificationPayload.stampSubmission(junk, 1L, 2L)
        // Defensive: a corrupted queue row still gets a POST attempt.
        // The backend's own validation catches the malformed body.
        assertThat(stamped).isEqualTo(junk)
    }
}
