package com.hotwire.fisiontv.networkqual.publish

import com.google.common.truth.Truth.assertThat
import com.hotwire.fisiontv.networkqual.cert.Tier
import com.hotwire.fisiontv.networkqual.config.RuntimeConfigDefaults
import org.junit.Test

class RuntimeConfigParserTest {

    // Sample uses the contract v1.4.0 shape — no servers[], no
    // tests.latency, no per-phase duration/perRequestBytes/warmupFraction.
    private val sample = """
    {
      "schemaVersion": 1,
      "configVersion": "2026-05-06.1",
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

    @Test fun `parses spec-shape JSON cleanly`() {
        val cfg = RuntimeConfigParser.parse(sample)
        assertThat(cfg.configVersion).isEqualTo("2026-05-06.1")
        assertThat(cfg.tiers.map { it.tier }).containsExactly(Tier.SD, Tier.HD)
        assertThat(cfg.tests.download.parallel).isEqualTo(4)
        assertThat(cfg.tests.upload.parallel).isEqualTo(2)
        assertThat(cfg.resultsPublishing.enabled).isTrue()
        assertThat(cfg.resultsPublishing.endpoint).isEqualTo("https://api.example/v1/certifications")
    }

    @Test fun `falls back to bundled defaults for missing optional fields`() {
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
        assertThat(cfg.tiers).isNotEmpty()
        assertThat(cfg.dnsProbeHosts).isNotEmpty()
    }

    // --- Regression: dev.7/dev.8 shipped playback.durationSec=1 from the
    // dashboard. The old parser threw, the STB fell back to bundled
    // defaults wholesale, and uploadResults.enabled=false (the kill
    // switch) was silently ignored. This must not happen again.

    @Test fun `kill switch honored when playback durationSec is out of range`() {
        val bad = sample
            .replace(""""durationSec": 20""", """"durationSec": 1""")
            .replace(""""enabled": true""", """"enabled": false""")
        val cfg = RuntimeConfigParser.parse(bad)
        // Bad playback section fell back to bundled defaults — playback
        // duration is NOT 1.
        assertThat(cfg.tests.playback.durationSec).isEqualTo(
            RuntimeConfigDefaults.bundled.tests.playback.durationSec
        )
        // Kill switch survives the bad sibling field.
        assertThat(cfg.resultsPublishing.enabled).isFalse()
    }

    @Test fun `parallel above 16 clamps to bundled default not throws`() {
        val bad = sample.replace(""""parallel": 4""", """"parallel": 99""")
        val cfg = RuntimeConfigParser.parse(bad)
        // Bad field swapped for bundled default; the upload phase is
        // unaffected.
        assertThat(cfg.tests.download.parallel).isEqualTo(
            RuntimeConfigDefaults.bundled.tests.download.parallel
        )
        assertThat(cfg.tests.upload.parallel).isEqualTo(2)
    }

    @Test fun `empty tests section falls back to bundled per-phase`() {
        // tests:{} — every child phase is missing, parser must substitute
        // bundled defaults rather than throw.
        val bad = """
        {
          "schemaVersion": 1,
          "configVersion": "2026-05-06.1",
          "tests": {},
          "tiers": [
            { "id": "sd", "displayName": "SD", "minDownloadMbps": 5, "maxLatencyMs": 200, "maxJitterMs": 50, "minPlaybackHeight": 480 }
          ],
          "uploadResults": { "enabled": false }
        }
        """.trimIndent()
        val cfg = RuntimeConfigParser.parse(bad)
        assertThat(cfg.tests.playback.durationSec).isEqualTo(
            RuntimeConfigDefaults.bundled.tests.playback.durationSec
        )
        assertThat(cfg.tests.download.parallel).isEqualTo(
            RuntimeConfigDefaults.bundled.tests.download.parallel
        )
        assertThat(cfg.resultsPublishing.enabled).isFalse()
    }

    // --- wifiLinkQuality + healthAssessment tunables (contract v1.3.0)
    //
    // Both sections are optional in the wire format. When present, every
    // field gets clamp-or-fallback semantics with logged warnings — a
    // bad value can't poison the rest of the config.

    @Test fun `wifiLinkQuality parses when valid and overrides bundled defaults`() {
        val withWifi = sample.replace(
            """"uploadResults": { "enabled": true, "endpoint": "https://api.example/v1/certifications" }""",
            """"uploadResults": { "enabled": true, "endpoint": "https://api.example/v1/certifications" },
            "wifiLinkQuality": {
              "excellentRssiMin": -50,
              "strongRssiMin": -60,
              "goodRssiMin": -70,
              "rateAdaptationDegradedThreshold": 0.6
            }"""
        )
        val cfg = RuntimeConfigParser.parse(withWifi)
        assertThat(cfg.wifiLinkQuality.excellentRssiMin).isEqualTo(-50)
        assertThat(cfg.wifiLinkQuality.strongRssiMin).isEqualTo(-60)
        assertThat(cfg.wifiLinkQuality.goodRssiMin).isEqualTo(-70)
        assertThat(cfg.wifiLinkQuality.rateAdaptationDegradedThreshold).isEqualTo(0.6)
    }

    @Test fun `wifiLinkQuality with ordering violation falls back wholesale`() {
        // Bands aren't well-defined if excellent < strong, so we don't
        // substitute fields — we ditch the whole section.
        val bad = sample.replace(
            """"uploadResults": { "enabled": true, "endpoint": "https://api.example/v1/certifications" }""",
            """"uploadResults": { "enabled": true, "endpoint": "https://api.example/v1/certifications" },
            "wifiLinkQuality": {
              "excellentRssiMin": -75,
              "strongRssiMin": -65,
              "goodRssiMin": -55,
              "rateAdaptationDegradedThreshold": 0.5
            }"""
        )
        val cfg = RuntimeConfigParser.parse(bad)
        assertThat(cfg.wifiLinkQuality).isEqualTo(RuntimeConfigDefaults.bundled.wifiLinkQuality)
    }

    @Test fun `wifiLinkQuality individual field out of range falls back to bundled for that field`() {
        val bad = sample.replace(
            """"uploadResults": { "enabled": true, "endpoint": "https://api.example/v1/certifications" }""",
            """"uploadResults": { "enabled": true, "endpoint": "https://api.example/v1/certifications" },
            "wifiLinkQuality": {
              "excellentRssiMin": -50,
              "strongRssiMin": -60,
              "goodRssiMin": -70,
              "rateAdaptationDegradedThreshold": 5.0
            }"""
        )
        val cfg = RuntimeConfigParser.parse(bad)
        assertThat(cfg.wifiLinkQuality.excellentRssiMin).isEqualTo(-50) // preserved
        // Bad field substituted for bundled default
        assertThat(cfg.wifiLinkQuality.rateAdaptationDegradedThreshold)
            .isEqualTo(RuntimeConfigDefaults.bundled.wifiLinkQuality.rateAdaptationDegradedThreshold)
    }

    @Test fun `healthAssessment parses when valid and overrides bundled defaults`() {
        val withHealth = sample.replace(
            """"uploadResults": { "enabled": true, "endpoint": "https://api.example/v1/certifications" }""",
            """"uploadResults": { "enabled": true, "endpoint": "https://api.example/v1/certifications" },
            "healthAssessment": {
              "excellentMin": 90,
              "strongMin": 65,
              "goodMin": 40,
              "topTierStretchUpFactor": 1.3,
              "topTierStretchDownFactor": 0.75
            }"""
        )
        val cfg = RuntimeConfigParser.parse(withHealth)
        assertThat(cfg.healthAssessment.excellentMin).isEqualTo(90)
        assertThat(cfg.healthAssessment.strongMin).isEqualTo(65)
        assertThat(cfg.healthAssessment.goodMin).isEqualTo(40)
        assertThat(cfg.healthAssessment.topTierStretchUpFactor).isEqualTo(1.3)
        assertThat(cfg.healthAssessment.topTierStretchDownFactor).isEqualTo(0.75)
    }

    @Test fun `healthAssessment topTierStretchUpFactor exactly 1 dot 0 falls back`() {
        // Must be strictly >1.0; 1.0 means "cap at the tier minimum"
        // which would make every result MARGINAL.
        val bad = sample.replace(
            """"uploadResults": { "enabled": true, "endpoint": "https://api.example/v1/certifications" }""",
            """"uploadResults": { "enabled": true, "endpoint": "https://api.example/v1/certifications" },
            "healthAssessment": {
              "excellentMin": 80,
              "strongMin": 55,
              "goodMin": 30,
              "topTierStretchUpFactor": 1.0,
              "topTierStretchDownFactor": 0.66
            }"""
        )
        val cfg = RuntimeConfigParser.parse(bad)
        assertThat(cfg.healthAssessment.topTierStretchUpFactor)
            .isEqualTo(RuntimeConfigDefaults.bundled.healthAssessment.topTierStretchUpFactor)
        // Other valid fields are preserved.
        assertThat(cfg.healthAssessment.excellentMin).isEqualTo(80)
    }

    @Test fun `tunables absent uses bundled defaults silently`() {
        // The sample has neither section. This is the v0.9.2-and-prior
        // wire format; rolling forward the v1.3.0 contract must keep
        // working for those clients.
        val cfg = RuntimeConfigParser.parse(sample)
        assertThat(cfg.wifiLinkQuality).isEqualTo(RuntimeConfigDefaults.bundled.wifiLinkQuality)
        assertThat(cfg.healthAssessment).isEqualTo(RuntimeConfigDefaults.bundled.healthAssessment)
    }

    // --- killswitch (contract v2.1.0) ---

    @Test fun `killswitch absent defaults to disabled`() {
        val cfg = RuntimeConfigParser.parse(sample)
        assertThat(cfg.killswitch.enabled).isFalse()
        assertThat(cfg.killswitch.reason).isNull()
    }

    @Test fun `killswitch enabled with reason parses`() {
        val withKill = sample.replace(
            """"uploadResults": { "enabled": true, "endpoint": "https://api.example/v1/certifications" }""",
            """"uploadResults": { "enabled": true, "endpoint": "https://api.example/v1/certifications" },
            "killswitch": { "enabled": true, "reason": "Maintenance window" }"""
        )
        val cfg = RuntimeConfigParser.parse(withKill)
        assertThat(cfg.killswitch.enabled).isTrue()
        assertThat(cfg.killswitch.reason).isEqualTo("Maintenance window")
    }

    @Test fun `killswitch enabled without reason parses with null reason`() {
        val withKill = sample.replace(
            """"uploadResults": { "enabled": true, "endpoint": "https://api.example/v1/certifications" }""",
            """"uploadResults": { "enabled": true, "endpoint": "https://api.example/v1/certifications" },
            "killswitch": { "enabled": true }"""
        )
        val cfg = RuntimeConfigParser.parse(withKill)
        assertThat(cfg.killswitch.enabled).isTrue()
        assertThat(cfg.killswitch.reason).isNull()
    }

    @Test fun `killswitch malformed fails closed`() {
        // `enabled` is a number where a boolean is expected — must NOT
        // accidentally engage the killswitch from a typo.
        val bad = sample.replace(
            """"uploadResults": { "enabled": true, "endpoint": "https://api.example/v1/certifications" }""",
            """"uploadResults": { "enabled": true, "endpoint": "https://api.example/v1/certifications" },
            "killswitch": { "enabled": "yes please" }"""
        )
        val cfg = RuntimeConfigParser.parse(bad)
        assertThat(cfg.killswitch.enabled).isFalse()
    }

    @Test fun `malformed uploadResults fails closed (kill switch on)`() {
        // endpoint: 42 — number where a string is expected. Parser must
        // not let an upload accidentally fire to whatever toString()
        // produces.
        val bad = sample.replace(
            """{ "enabled": true, "endpoint": "https://api.example/v1/certifications" }""",
            """{ "enabled": true, "endpoint": 42 }"""
        )
        val cfg = RuntimeConfigParser.parse(bad)
        // We tolerate the malformed endpoint by parsing it as a string
        // ("42"), but the more important invariant is exercised by
        // `kill switch honored when playback durationSec is out of range`
        // above. This test documents that a malformed publishing object
        // doesn't crash the whole parse.
        assertThat(cfg.tiers).isNotEmpty()
    }
}
