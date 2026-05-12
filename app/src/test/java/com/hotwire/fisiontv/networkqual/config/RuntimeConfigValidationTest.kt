package com.hotwire.fisiontv.networkqual.config

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The validation in RuntimeConfig.init blocks is the line of defence
 * against a remote cert-config arriving with garbage values. These tests
 * ensure that line of defence actually catches the obvious garbage.
 *
 * As of contract v1.4.0 the `servers[]`, `tests.latency`, and per-phase
 * `durationSec`/`perRequestBytes`/`warmupFraction` fields have been
 * dropped from the data classes (they were never consumed by the Ookla
 * code path), so the validation surface here is correspondingly smaller.
 * `OoklaServer` is kept because the result-side `selectedServer` still
 * uses it.
 */
class RuntimeConfigValidationTest {

    @Test fun `bundled defaults validate cleanly`() {
        // Constructing the singleton runs all init blocks; just access it.
        val cfg = RuntimeConfigDefaults.bundled
        assertThat(cfg.tiers).isNotEmpty()
        assertThat(cfg.dnsProbeHosts).isNotEmpty()
    }

    @Test fun `throughput parallel out of range rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            ThroughputPhaseConfig(parallel = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ThroughputPhaseConfig(parallel = 17)
        }
    }

    @Test fun `playback durationSec below floor rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            PlaybackPhaseConfig(manifestUrl = "https://x/y.mpd", durationSec = 1)
        }
    }

    @Test fun `health buckets out of order rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            HealthAssessmentConfig(
                excellentMin = 50, strongMin = 80, goodMin = 30,
                topTierStretchUpFactor = 1.5,
                topTierStretchDownFactor = 0.66
            )
        }
    }

    @Test fun `wifi RSSI bands out of order rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            WifiLinkQualityConfig(
                excellentRssiMin = -65, strongRssiMin = -55, goodRssiMin = -75,
                rateAdaptationDegradedThreshold = 0.5
            )
        }
    }

    @Test fun `OoklaServer with blank id rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            OoklaServer(id = "", name = "Test", host = "example.com")
        }
    }

    @Test fun `OoklaServer with bad port rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            OoklaServer(id = "x", name = "X", host = "example.com", port = 0)
        }
    }
}
