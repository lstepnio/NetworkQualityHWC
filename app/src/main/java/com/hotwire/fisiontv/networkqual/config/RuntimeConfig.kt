package com.hotwire.fisiontv.networkqual.config

import com.hotwire.fisiontv.networkqual.cert.TierThreshold

/**
 * Single source of truth for everything tunable in the certification suite.
 *
 * Today this is constructed from [RuntimeConfigDefaults]. The intent is that
 * once `GET /v1/cert-config` ships (see contract/SPEC.md §4.1), a
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
    val tests: TestsConfig,
    val tiers: List<TierThreshold>,
    val dnsProbeHosts: List<String>,
    val healthAssessment: HealthAssessmentConfig,
    val wifiLinkQuality: WifiLinkQualityConfig,
    val resultsPublishing: ResultsPublishingConfig,
    /**
     * Optional remote kill switch. When [KillswitchConfig.enabled] is
     * true the app calls `finishAffinity()` on receipt of this config —
     * no UI surface, no shared-prefs cache, no in-process polling. The
     * launch path on the next app start re-fetches the active config
     * and proceeds normally if the switch has flipped off.
     *
     * Distinct from [ResultsPublishingConfig.enabled], which only
     * suppresses POSTing results — under that flag certs still run
     * locally.
     */
    val killswitch: KillswitchConfig = KillswitchConfig(enabled = false),
    /**
     * Hosted-config URL for the Ookla embedded SDK. The binary fetches
     * the server list + per-phase durations + sample counts from this
     * URL — that's why none of those values live in cert-config
     * anymore. Should be HTTPS in production with the bundled CA cert;
     * HTTP is acceptable for lab/dev only.
     */
    val ooklaConfigUrl: String,
    /**
     * Optional fallback URL retried automatically when [ooklaConfigUrl]
     * fails before the test starts (TLS handshake / config-fetch
     * failures). Production should use HTTP variant of the same hosted
     * config so a CA bundle issue doesn't black out the cert. Set to
     * null to disable fallback.
     */
    val ooklaConfigUrlFallback: String? = null,
    /**
     * Optional DNS-policy declaration. When non-null, the cert engine
     * compares the STB's actual `network.dnsServers` against
     * [DnsPolicyConfig.preferredServers] and emits a `dnsAssessment`
     * block on the payload. Null → no assessment computed, no field
     * emitted. Operators that don't care about DNS posture pay nothing.
     *
     * Matching is **strict** (every actual server must be in the list)
     * and **literal** (string-equality, not CIDR / hostname / wildcard).
     * See `cert/DnsAssessor`.
     */
    val dnsPolicy: DnsPolicyConfig? = null
) {
    init {
        require(schemaVersion > 0) { "schemaVersion must be positive" }
        require(configVersion.isNotBlank()) { "configVersion must not be blank" }
        require(tiers.isNotEmpty()) { "tiers must not be empty" }
        require(dnsProbeHosts.isNotEmpty()) { "dnsProbeHosts must not be empty" }
        require(ooklaConfigUrl.isNotBlank()) { "ooklaConfigUrl must not be blank" }
    }
}

data class TestsConfig(
    val download: ThroughputPhaseConfig,
    val upload: ThroughputPhaseConfig,
    val playback: PlaybackPhaseConfig
)

/**
 * The only knob we control in throughput phases is the TCP stream
 * count — passed to libookla.so as `--download-conn-range` /
 * `--upload-conn-range`. Duration, chunk sizing, and warmup are all
 * baked into the Ookla embed-config (and were removed from this type
 * in contract v1.4.0).
 */
data class ThroughputPhaseConfig(
    val parallel: Int
) {
    init {
        require(parallel in 1..16) { "parallel out of range: $parallel" }
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

/**
 * True killswitch — see [RuntimeConfig.killswitch] for semantics.
 *
 * `enabled = false` is the safe default (app runs normally). When
 * `enabled = true`, the app exits cleanly the moment the config
 * applies. `reason` is informational only — emitted to logcat at
 * exit so techs can correlate.
 */
data class KillswitchConfig(
    val enabled: Boolean,
    val reason: String? = null
)

/**
 * Operator-declared list of nameservers the fleet is expected to use.
 * The cert engine compares actual `network.dnsServers` literally
 * (byte-for-byte; no CIDR / hostname / wildcard) against this list and
 * emits `CertificationResult.dnsAssessment`.
 *
 * Per-field tolerance in the parser keeps a config with an empty or
 * malformed `dnsPolicy` block usable — invalid policy degrades to "no
 * policy" (null) rather than rejecting the whole config.
 */
data class DnsPolicyConfig(
    val preferredServers: List<String>
) {
    init {
        require(preferredServers.isNotEmpty()) { "preferredServers must not be empty" }
    }
}
