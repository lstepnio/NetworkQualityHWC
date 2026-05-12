package com.hotwire.fisiontv.networkqual.config

import com.hotwire.fisiontv.networkqual.cert.Tier
import com.hotwire.fisiontv.networkqual.cert.TierThreshold

/**
 * Bundled fallback config baked into the APK. Used until the cert-config API
 * returns a fresher copy. Keep this in sync with contract/SPEC.md
 * §6.1; backend-served configs must produce identical behaviour.
 */
object RuntimeConfigDefaults {
    val bundled: RuntimeConfig = RuntimeConfig(
        schemaVersion = 1,
        configVersion = "local-defaults",
        tests = TestsConfig(
            // The only knob we actually pass to libookla.so is the conn
            // range — `--download-conn-range` / `--upload-conn-range`,
            // wired in OoklaSpeedtestRunner. Empirically-best values
            // from a 78-trial sweep on the lab STB: 8/16 cut DL stddev
            // from 17.8 to 1.9 Mbps and UL from 79.8 to 11.1. Phase
            // durations live in the Ookla embed-config response (see
            // contract SPEC §6.1.1) and are not tunable from this side.
            download = ThroughputPhaseConfig(parallel = 8),
            upload   = ThroughputPhaseConfig(parallel = 16),
            playback = PlaybackPhaseConfig(
                manifestUrl = "https://dash.akamaized.net/akamai/bbb_30fps/bbb_30fps.mpd",
                durationSec = 20
            )
        ),
        // Conservatism is baked in: throughput x1.5, latency relaxed to real
        // TCP-connect-across-the-internet timing, jitter robust via MAD.
        tiers = listOf(
            TierThreshold(Tier.SD,         minDownloadMbps = 5.0,  maxLatencyMs = 200, maxJitterMs = 50, maxRebuffers = 1, playbackMinHeight = 480),
            TierThreshold(Tier.HD,         minDownloadMbps = 12.0, maxLatencyMs = 120, maxJitterMs = 30, maxRebuffers = 0, playbackMinHeight = 1080),
            TierThreshold(Tier.UHD_4K,     minDownloadMbps = 40.0, maxLatencyMs = 80,  maxJitterMs = 20, maxRebuffers = 0, playbackMinHeight = 2160),
            TierThreshold(Tier.UHD_4K_HDR, minDownloadMbps = 55.0, maxLatencyMs = 50,  maxJitterMs = 15, maxRebuffers = 0, playbackMinHeight = 2160)
        ),
        // Each host is distinct so every lookup is a cold-cache resolve.
        // Mix of HWC infra, major OTT brands, and a CDN host the playback
        // test will also hit.
        dnsProbeHosts = listOf(
            "gethotwired.com",
            "google.com",
            "netflix.com",
            "youtube.com",
            "dash.akamaized.net",
            "cloudflare.com"
        ),
        healthAssessment = HealthAssessmentConfig(
            excellentMin = 80,
            strongMin = 55,
            goodMin = 30,
            topTierStretchUpFactor = 1.5,
            topTierStretchDownFactor = 0.66
        ),
        wifiLinkQuality = WifiLinkQualityConfig(
            excellentRssiMin = -55,
            strongRssiMin = -65,
            goodRssiMin = -75,
            rateAdaptationDegradedThreshold = 0.5
        ),
        // Pointed at the dev backend running in `make dev` on the lab Mac.
        // Until the GET /v1/cert-config fetch is wired into RuntimeConfigProvider,
        // this hardcoded endpoint is what the publish queue will POST to.
        resultsPublishing = ResultsPublishingConfig(
            enabled = true,
            endpoint = "http://192.168.10.233:8080/v1/certifications"
        ),

        // HWC's hosted Ookla embedded config URL. The cert engine routes
        // server selection + ping + download + upload through the bundled
        // ookla binary using this URL. CA bundle ships in assets/cacert.pem
        // so HTTPS works without depending on the device's CA store.
        ooklaConfigUrl = "https://config.speedtest.net/v1/embed/yl1umix4fygogu8l/config",
        // HTTP fallback. Used only if HTTPS fails before the test starts
        // (TLS handshake / config-fetch errors — typically a CA-bundle
        // mismatch on a customer network). Acceptable risk: the URL
        // returns config metadata, not customer data.
        ooklaConfigUrlFallback = "http://config.speedtest.net/v1/embed/yl1umix4fygogu8l/config"
    )
}
