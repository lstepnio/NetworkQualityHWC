package com.hotwire.fisiontv.networkqual.publish

import com.google.common.truth.Truth.assertThat
import com.hotwire.fisiontv.networkqual.cert.Tier
import org.junit.Test

class RuntimeConfigParserTest {

    private val sample = """
    {
      "schemaVersion": 1,
      "configVersion": "2026-05-06.1",
      "servers": [
        { "id": "mia", "name": "Miami", "host": "speedtestmia.gethotwired.com", "port": 8080, "secure": true }
      ],
      "tests": {
        "download": { "durationSec": 10, "parallel": 4, "perRequestBytes": 100000000, "warmupFraction": 0.33 },
        "upload":   { "durationSec": 5,  "parallel": 2, "perRequestBytes": 50000000,  "warmupFraction": 0.33 },
        "latency":  { "samples": 10, "timeoutMs": 2000 },
        "playback": { "manifestUrl": "https://x/y.mpd", "durationSec": 20 }
      },
      "tiers": [
        { "id": "sd", "displayName": "SD", "minDownloadMbps": 5,  "maxLatencyMs": 200, "maxJitterMs": 50, "minPlaybackHeight": 480 },
        { "id": "hd", "displayName": "HD", "minDownloadMbps": 12, "maxLatencyMs": 120, "maxJitterMs": 30, "minPlaybackHeight": 1080 }
      ],
      "uploadResults": { "enabled": true, "endpoint": "https://api.example/v1/certifications" }
    }
    """.trimIndent()

    @Test fun `parses spec-shape JSON cleanly`() {
        val cfg = RuntimeConfigParser.parse(sample)
        assertThat(cfg.configVersion).isEqualTo("2026-05-06.1")
        assertThat(cfg.servers).hasSize(1)
        assertThat(cfg.servers[0].id).isEqualTo("mia")
        assertThat(cfg.tiers.map { it.tier }).containsExactly(Tier.SD, Tier.HD)
        assertThat(cfg.tests.download.parallel).isEqualTo(4)
        assertThat(cfg.resultsPublishing.enabled).isTrue()
        assertThat(cfg.resultsPublishing.endpoint).isEqualTo("https://api.example/v1/certifications")
    }

    @Test fun `falls back to bundled defaults for missing optional fields`() {
        // Same JSON minus dnsProbeHosts and uploadResults — multiline
        // regex strips the trailing entries together.
        val minimal = sample.replace(
            Regex(""",\s*"uploadResults"\s*:\s*\{[^}]*}"""),
            ""
        )
        val cfg = RuntimeConfigParser.parse(minimal)
        assertThat(cfg.dnsProbeHosts).isNotEmpty() // inherited from defaults
        assertThat(cfg.resultsPublishing.enabled).isFalse()
    }

    @Test fun `unknown tier id throws`() {
        val bad = sample.replace(""""id": "sd"""", """"id": "foo"""")
        try {
            RuntimeConfigParser.parse(bad)
            error("expected parse to throw")
        } catch (t: Throwable) {
            assertThat(t.message).contains("foo")
        }
    }

    @Test fun `parsed config passes RuntimeConfig validation`() {
        val cfg = RuntimeConfigParser.parse(sample)
        assertThat(cfg.servers).isNotEmpty()
        assertThat(cfg.tiers).isNotEmpty()
        assertThat(cfg.dnsProbeHosts).isNotEmpty()
    }
}
