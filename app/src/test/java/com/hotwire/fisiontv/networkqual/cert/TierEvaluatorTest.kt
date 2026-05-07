package com.hotwire.fisiontv.networkqual.cert

import com.google.common.truth.Truth.assertThat
import com.hotwire.fisiontv.networkqual.fixtures.Fixtures
import org.junit.Test

/**
 * Locks in the tier-passing math. These tests should fail loudly if a
 * threshold change unexpectedly drops a tier the field has been
 * certifying against — protect against silent regressions when tweaking
 * RuntimeConfigDefaults.
 */
class TierEvaluatorTest {

    private val evaluator = TierEvaluator(Fixtures.tiers)

    @Test fun `passes 4K HDR when every metric clears the top tier`() {
        val outcome = evaluator.evaluate(
            latency = Fixtures.latency(median = 30, jitter = 5),
            download = Fixtures.throughput(steady = 200.0),
            playback = Fixtures.playback(peakHeight = 2160)
        )
        assertThat(outcome.achieved).isEqualTo(Tier.UHD_4K_HDR)
        assertThat(outcome.breakdown.last().passed).isTrue()
    }

    @Test fun `falls back to HD when latency exceeds 4K ceiling`() {
        val outcome = evaluator.evaluate(
            latency = Fixtures.latency(median = 100),
            download = Fixtures.throughput(steady = 200.0),
            playback = Fixtures.playback(peakHeight = 2160)
        )
        assertThat(outcome.achieved).isEqualTo(Tier.HD)
    }

    @Test fun `falls back to NONE when no tier passes`() {
        val outcome = evaluator.evaluate(
            latency = Fixtures.latency(median = 500, jitter = 200),
            download = Fixtures.throughput(steady = 1.0),
            playback = Fixtures.playback(peakHeight = 240, rebuffers = 5)
        )
        assertThat(outcome.achieved).isEqualTo(Tier.NONE)
    }

    @Test fun `failingReasons names the metric below threshold`() {
        val outcome = evaluator.evaluate(
            latency = Fixtures.latency(median = 50),
            download = Fixtures.throughput(steady = 8.0),
            playback = Fixtures.playback(peakHeight = 1080)
        )
        val hd = outcome.breakdown.first { it.tier == Tier.HD }
        assertThat(hd.passed).isFalse()
        assertThat(hd.failingReasons.joinToString()).contains("Download")
    }

    @Test fun `playback height above 0 but below tier minimum fails the tier`() {
        val outcome = evaluator.evaluate(
            latency = Fixtures.latency(median = 30),
            download = Fixtures.throughput(steady = 100.0),
            playback = Fixtures.playback(peakHeight = 720)
        )
        val uhd = outcome.breakdown.first { it.tier == Tier.UHD_4K }
        assertThat(uhd.passed).isFalse()
        assertThat(uhd.failingReasons.joinToString()).contains("720p")
    }

    @Test fun `playback height of 0 (no video at all) does not fail tiers on height`() {
        // peakHeight == 0 means playback didn't decode anything, which
        // shouldn't be conflated with "tried but achieved insufficient
        // resolution". Throughput-only certification is still meaningful.
        val outcome = evaluator.evaluate(
            latency = Fixtures.latency(median = 30),
            download = Fixtures.throughput(steady = 100.0),
            playback = Fixtures.playback(peakHeight = 0)
        )
        val hd = outcome.breakdown.first { it.tier == Tier.HD }
        assertThat(hd.failingReasons.joinToString()).doesNotContain("requires")
    }
}
