package com.hotwire.fisiontv.networkqual.config

import com.hotwire.fisiontv.networkqual.cert.TierThreshold

/**
 * Single source of truth for everything tunable in the certification suite.
 *
 * Today this is constructed from [RuntimeConfigDefaults]. The intent is that
 * once `GET /v1/cert-config` ships (see docs/BACKEND_API_SPEC.md §4.1), a
 * remote-fetched copy replaces the bundled defaults — without an APK push.
 *
 * Validation is enforced in `init` blocks so a bad config (locally or
 * remotely sourced) throws at load rather than producing nonsense results
 * mid-run. Add `require(...)` calls liberally; configs are small and the
 * blast radius of silent garbage is large.
 */
data class RuntimeConfig(
    val schemaVersion: Int,
    val configVersion: String,
    val servers: List<OoklaServer>,
    val tests: TestsConfig,
    val tiers: List<TierThreshold>,
    val dnsProbeHosts: List<String>,
    val healthAssessment: HealthAssessmentConfig,
    val wifiLinkQuality: WifiLinkQualityConfig,
    val resultsPublishing: ResultsPublishingConfig
) {
    init {
        require(schemaVersion > 0) { "schemaVersion must be positive" }
        require(configVersion.isNotBlank()) { "configVersion must not be blank" }
        require(servers.isNotEmpty()) { "servers must not be empty" }
        require(tiers.isNotEmpty()) { "tiers must not be empty" }
        require(dnsProbeHosts.isNotEmpty()) { "dnsProbeHosts must not be empty" }
        val ids = servers.map { it.id }
        require(ids.toSet().size == ids.size) { "server ids must be unique: $ids" }
    }
}

data class TestsConfig(
    val download: ThroughputPhaseConfig,
    val upload: ThroughputPhaseConfig,
    val latency: LatencyPhaseConfig,
    val playback: PlaybackPhaseConfig
)

data class ThroughputPhaseConfig(
    val durationSec: Int,
    val parallel: Int,
    val perRequestBytes: Long,
    val warmupFraction: Double
) {
    init {
        require(durationSec in 1..120) { "durationSec out of range: $durationSec" }
        require(parallel in 1..16) { "parallel out of range: $parallel" }
        require(perRequestBytes in 1_000_000L..2_000_000_000L) {
            "perRequestBytes out of range: $perRequestBytes"
        }
        require(warmupFraction in 0.0..0.9) {
            "warmupFraction out of range: $warmupFraction"
        }
    }
}

data class LatencyPhaseConfig(
    val samples: Int,
    val timeoutMs: Int
) {
    init {
        require(samples in 3..100) { "samples out of range: $samples" }
        require(timeoutMs in 100..30_000) { "timeoutMs out of range: $timeoutMs" }
    }
}

data class PlaybackPhaseConfig(
    val manifestUrl: String,
    val durationSec: Int
) {
    init {
        require(manifestUrl.isNotBlank()) { "manifestUrl must not be blank" }
        require(durationSec in 5..120) { "durationSec out of range: $durationSec" }
    }
}

data class HealthAssessmentConfig(
    val excellentMin: Int,
    val strongMin: Int,
    val goodMin: Int,
    val topTierStretchUpFactor: Double,
    val topTierStretchDownFactor: Double
) {
    init {
        require(excellentMin in 1..100)
        require(strongMin in 1..100)
        require(goodMin in 1..100)
        require(excellentMin > strongMin) { "excellentMin must exceed strongMin" }
        require(strongMin > goodMin) { "strongMin must exceed goodMin" }
        require(topTierStretchUpFactor > 1.0) { "topTierStretchUpFactor must be > 1.0" }
        require(topTierStretchDownFactor in 0.0..1.0) {
            "topTierStretchDownFactor must be in [0.0, 1.0]"
        }
    }
}

data class WifiLinkQualityConfig(
    val excellentRssiMin: Int,
    val strongRssiMin: Int,
    val goodRssiMin: Int,
    val rateAdaptationDegradedThreshold: Double
) {
    init {
        require(excellentRssiMin > strongRssiMin) { "excellentRssiMin must exceed strongRssiMin" }
        require(strongRssiMin > goodRssiMin) { "strongRssiMin must exceed goodRssiMin" }
        require(rateAdaptationDegradedThreshold in 0.0..1.0)
    }
}

data class ResultsPublishingConfig(
    val enabled: Boolean,
    val endpoint: String?
)
