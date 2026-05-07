package com.hotwire.fisiontv.networkqual.config

import com.hotwire.fisiontv.networkqual.cert.Tier
import com.hotwire.fisiontv.networkqual.cert.TierThreshold

/**
 * Bundled fallback config baked into the APK. Used until the cert-config API
 * returns a fresher copy. Keep this in sync with docs/BACKEND_API_SPEC.md
 * §6.1; backend-served configs must produce identical behaviour.
 */
object RuntimeConfigDefaults {
    val bundled: RuntimeConfig = RuntimeConfig(
        schemaVersion = 1,
        configVersion = "local-defaults",
        servers = listOf(
            OoklaServer(id = "mia",  name = "Miami",            host = "speedtestmia.gethotwired.com"),
            OoklaServer(id = "apf",  name = "Apopka",           host = "speedtestapf.gethotwired.com"),
            OoklaServer(id = "atl",  name = "Atlanta",          host = "speedtestatl.gethotwired.com"),
            OoklaServer(id = "boca", name = "Boca Raton",       host = "speedtestboca.gethotwired.com"),
            OoklaServer(id = "dfw",  name = "Dallas/Fort Worth", host = "speedtestdfw.gethotwired.com"),
            OoklaServer(id = "la",   name = "Los Angeles",      host = "speedtestla.gethotwired.com"),
            OoklaServer(id = "nc",   name = "North Carolina",   host = "speedtestnc.gethotwired.com")
        ),
        tests = TestsConfig(
            download = ThroughputPhaseConfig(durationSec = 10, parallel = 4, perRequestBytes = 100_000_000L, warmupFraction = 0.33),
            upload   = ThroughputPhaseConfig(durationSec = 5,  parallel = 2, perRequestBytes = 50_000_000L,  warmupFraction = 0.33),
            latency  = LatencyPhaseConfig(samples = 10, timeoutMs = 2_000),
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
        // Off until the backend exists; flipping this to `true` with a real
        // endpoint will start posting results without an APK push.
        resultsPublishing = ResultsPublishingConfig(enabled = false, endpoint = null)
    )
}
