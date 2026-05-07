package com.hotwire.fisiontv.networkqual.fixtures

import com.hotwire.fisiontv.networkqual.cert.HealthAssessment
import com.hotwire.fisiontv.networkqual.cert.HealthRating
import com.hotwire.fisiontv.networkqual.cert.Tier
import com.hotwire.fisiontv.networkqual.cert.TierThreshold
import com.hotwire.fisiontv.networkqual.config.HealthAssessmentConfig
import com.hotwire.fisiontv.networkqual.config.OoklaServer
import com.hotwire.fisiontv.networkqual.config.RuntimeConfigDefaults
import com.hotwire.fisiontv.networkqual.config.WifiLinkQualityConfig
import com.hotwire.fisiontv.networkqual.cert.probes.LatencyResult
import com.hotwire.fisiontv.networkqual.cert.probes.PlaybackResult
import com.hotwire.fisiontv.networkqual.cert.probes.ThroughputResult

/**
 * Shared test fixtures. Keep this small and high-signal — every value
 * here is something the unit tests reason about.
 */
object Fixtures {

    val tiers: List<TierThreshold> = RuntimeConfigDefaults.bundled.tiers
    val healthCfg: HealthAssessmentConfig = RuntimeConfigDefaults.bundled.healthAssessment
    val wifiCfg: WifiLinkQualityConfig = RuntimeConfigDefaults.bundled.wifiLinkQuality

    fun server(id: String = "test", host: String = "test.example.com") =
        OoklaServer(id = id, name = id.uppercase(), host = host)

    fun throughput(steady: Double, peak: Double = steady * 1.1, durationSec: Int = 10) =
        ThroughputResult(steady, peak, durationSec)

    fun latency(median: Long, jitter: Long = 5L, samples: List<Long> = listOf(median)) =
        LatencyResult(samples = samples, medianMs = median, jitterMs = jitter)

    fun playback(
        peakHeight: Int = 1080,
        rebuffers: Int = 0,
        peakBitrateKbps: Int = 6000,
        startupMs: Long = 1200,
        playedSec: Int = 20
    ) = PlaybackResult(
        timeToFirstFrameMs = startupMs,
        rebufferCount = rebuffers,
        totalRebufferMs = if (rebuffers > 0) 1000L else 0L,
        peakBitrateKbps = peakBitrateKbps,
        peakHeight = peakHeight,
        bitrateSwitchCount = 0,
        playedSec = playedSec
    )

    fun health(rating: HealthRating, headroom: Int = 50): HealthAssessment =
        HealthAssessment(headroom, rating, "Download", Tier.HD, mapOf("Download" to headroom))
}
