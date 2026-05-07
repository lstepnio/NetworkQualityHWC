package com.hotwire.fisiontv.networkqual.cert

import com.google.common.truth.Truth.assertThat
import com.hotwire.fisiontv.networkqual.fixtures.Fixtures
import org.junit.Test

class HealthAssessorTest {

    private val assessor = HealthAssessor(Fixtures.tiers, Fixtures.healthCfg)

    @Test fun `NONE tier produces FAILED rating with 0 headroom`() {
        val a = assessor.assess(
            achieved = Tier.NONE,
            download = Fixtures.throughput(steady = 1.0),
            latency = Fixtures.latency(median = 500)
        )
        assertThat(a.rating).isEqualTo(HealthRating.FAILED)
        assertThat(a.headroomPct).isEqualTo(0)
    }

    @Test fun `barely-achieved tier with min download yields low headroom`() {
        // HD: floor 12 Mbps, next tier (4K) floor 40 Mbps.
        // Measured 13 Mbps -> (13-12)/(40-12) ≈ 3.5%, which falls in the
        // MARGINAL bucket (< goodMin=30).
        val a = assessor.assess(
            achieved = Tier.HD,
            download = Fixtures.throughput(steady = 13.0),
            latency = Fixtures.latency(median = 30, jitter = 5)
        )
        assertThat(a.rating).isEqualTo(HealthRating.MARGINAL)
        assertThat(a.limitingMetric).isEqualTo("Download")
    }

    @Test fun `comfortable HD certification reaches EXCELLENT`() {
        val a = assessor.assess(
            achieved = Tier.HD,
            download = Fixtures.throughput(steady = 200.0),
            latency = Fixtures.latency(median = 25, jitter = 3)
        )
        assertThat(a.rating).isEqualTo(HealthRating.EXCELLENT)
        assertThat(a.headroomPct).isEqualTo(100)
        assertThat(a.nextTier).isEqualTo(Tier.UHD_4K)
    }

    @Test fun `top tier scores against stretch target, not infinity`() {
        val a = assessor.assess(
            achieved = Tier.UHD_4K_HDR,
            download = Fixtures.throughput(steady = 60.0),
            latency = Fixtures.latency(median = 39, jitter = 5)
        )
        assertThat(a.nextTier).isNull()
        assertThat(a.headroomPct).isAtLeast(0)
        assertThat(a.headroomPct).isAtMost(100)
    }

    @Test fun `limiting metric is the one with smallest progress`() {
        // HD floor: download 12 Mbps, latency 120ms, jitter 30ms.
        // 4K next-floor: download 40, latency 80, jitter 20.
        // Measured: download 38 (close to 4K), latency 30 (way under),
        // jitter 5 (way under). Download should be the limiter.
        val a = assessor.assess(
            achieved = Tier.HD,
            download = Fixtures.throughput(steady = 38.0),
            latency = Fixtures.latency(median = 30, jitter = 5)
        )
        assertThat(a.limitingMetric).isEqualTo("Download")
    }

    @Test fun `headroom never negative, never above 100`() {
        // Pathological: measured below the achieved floor (shouldn't
        // happen if achieved is set correctly, but test the clamp).
        val a = assessor.assess(
            achieved = Tier.HD,
            download = Fixtures.throughput(steady = 5.0),
            latency = Fixtures.latency(median = 1000)
        )
        assertThat(a.headroomPct).isAtLeast(0)
        assertThat(a.headroomPct).isAtMost(100)
    }
}
