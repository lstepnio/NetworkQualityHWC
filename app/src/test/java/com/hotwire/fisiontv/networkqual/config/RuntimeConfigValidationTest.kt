package com.hotwire.fisiontv.networkqual.config

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The validation in RuntimeConfig.init blocks is the line of defence
 * against a remote cert-config arriving with garbage values. These tests
 * ensure that line of defence actually catches the obvious garbage.
 */
class RuntimeConfigValidationTest {

    @Test fun `bundled defaults validate cleanly`() {
        // Constructing the singleton runs all init blocks; just access it.
        val cfg = RuntimeConfigDefaults.bundled
        assertThat(cfg.servers).isNotEmpty()
        assertThat(cfg.tiers).isNotEmpty()
        assertThat(cfg.dnsProbeHosts).isNotEmpty()
    }

    @Test fun `empty server list rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            RuntimeConfigDefaults.bundled.copy(servers = emptyList())
        }
    }

    @Test fun `duplicate server ids rejected`() {
        val dupes = RuntimeConfigDefaults.bundled.servers.let {
            listOf(it[0], it[0])
        }
        assertThrows(IllegalArgumentException::class.java) {
            RuntimeConfigDefaults.bundled.copy(servers = dupes)
        }
    }

    @Test fun `negative throughput duration rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            ThroughputPhaseConfig(
                durationSec = -1, parallel = 4,
                perRequestBytes = 1_000_000L, warmupFraction = 0.3
            )
        }
    }

    @Test fun `warmup fraction over 0_9 rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            ThroughputPhaseConfig(
                durationSec = 10, parallel = 4,
                perRequestBytes = 1_000_000L, warmupFraction = 0.95
            )
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
