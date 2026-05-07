package com.hotwire.fisiontv.networkqual.cert

/**
 * Pass criteria for a streaming tier. The values aren't a raw codec spec —
 * they're conservatism-buffered: passing one of these implies enough margin
 * to absorb a second device joining the home, peak congestion, or Wi-Fi
 * degradation after install.
 *
 * Concrete values live in [com.hotwire.fisiontv.networkqual.config.RuntimeConfigDefaults]
 * (and will eventually arrive from the cert-config API).
 */
data class TierThreshold(
    val tier: Tier,
    val minDownloadMbps: Double,
    val maxLatencyMs: Long,
    val maxJitterMs: Long,
    val maxRebuffers: Int,
    val playbackMinHeight: Int
) {
    init {
        require(minDownloadMbps > 0)
        require(maxLatencyMs > 0)
        require(maxJitterMs > 0)
        require(maxRebuffers >= 0)
        require(playbackMinHeight > 0)
    }
}
