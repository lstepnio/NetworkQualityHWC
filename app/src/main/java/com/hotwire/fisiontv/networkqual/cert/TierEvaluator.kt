package com.hotwire.fisiontv.networkqual.cert

import com.hotwire.fisiontv.networkqual.cert.probes.LatencyResult
import com.hotwire.fisiontv.networkqual.cert.probes.PlaybackResult
import com.hotwire.fisiontv.networkqual.cert.probes.ThroughputResult

class TierEvaluator(private val tiers: List<TierThreshold>) {

    data class Outcome(
        val networkAchieved: Tier,
        val playbackAchieved: Tier,
        val breakdown: List<TierEvaluation>
    )

    /**
     * Two evaluations, one call:
     *
     * - **Network tier** — what the connection can sustain, judged on
     *   throughput + latency + jitter alone. This is the certification
     *   number, because FisionTV+ adapts streaming bitrate based on
     *   network capacity; the customer pays for the connection, not for
     *   their TV.
     *
     * - **Playback tier** — what actually rendered during the DASH test
     *   run. Constrained additionally by the connected display's
     *   capabilities. Useful as a per-install observation: "this TV
     *   shows 1080p" vs. "this TV shows 4K HDR."
     *
     * The breakdown is the *playback* evaluation (the strictest of the
     * two) so support tooling sees the full set of failing reasons. The
     * `networkAchieved` field exposes the looser evaluation.
     */
    fun evaluate(
        latency: LatencyResult,
        download: ThroughputResult,
        playback: PlaybackResult
    ): Outcome {
        val playbackBreakdown = tiers.map { thr ->
            val reasons = buildList {
                addAll(networkReasons(thr, download, latency))
                if (playback.rebufferCount > thr.maxRebuffers) {
                    add("Playback rebuffered ${playback.rebufferCount} times (max ${thr.maxRebuffers}).")
                }
                if (playback.peakHeight in 1 until thr.playbackMinHeight) {
                    add("Playback peaked at ${playback.peakHeight}p; tier requires ${thr.playbackMinHeight}p.")
                }
            }
            TierEvaluation(thr.tier, reasons.isEmpty(), reasons)
        }

        val networkAchieved = tiers
            .filter { networkReasons(it, download, latency).isEmpty() }
            .maxByOrNull { it.tier.ordinal }?.tier ?: Tier.NONE

        val playbackAchieved = playbackBreakdown
            .filter { it.passed }.maxByOrNull { it.tier.ordinal }?.tier ?: Tier.NONE

        return Outcome(
            networkAchieved = networkAchieved,
            playbackAchieved = playbackAchieved,
            breakdown = playbackBreakdown
        )
    }

    private fun networkReasons(
        thr: TierThreshold,
        download: ThroughputResult,
        latency: LatencyResult
    ): List<String> = buildList {
        if (download.steadyMbps < thr.minDownloadMbps) {
            add("Download ${"%.1f".format(download.steadyMbps)} Mbps is below the ${thr.minDownloadMbps} Mbps minimum.")
        }
        if (latency.medianMs > thr.maxLatencyMs) {
            add("Latency ${latency.medianMs} ms exceeds the ${thr.maxLatencyMs} ms maximum.")
        }
        if (latency.jitterMs > thr.maxJitterMs) {
            add("Jitter ${latency.jitterMs} ms exceeds the ${thr.maxJitterMs} ms maximum.")
        }
    }
}
