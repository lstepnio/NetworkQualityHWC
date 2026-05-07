package com.hotwire.fisiontv.networkqual.cert

import com.hotwire.fisiontv.networkqual.cert.probes.LatencyResult
import com.hotwire.fisiontv.networkqual.cert.probes.PlaybackResult
import com.hotwire.fisiontv.networkqual.cert.probes.ThroughputResult

class TierEvaluator(private val tiers: List<TierThreshold>) {

    data class Outcome(
        val achieved: Tier,
        val breakdown: List<TierEvaluation>
    )

    fun evaluate(
        latency: LatencyResult,
        download: ThroughputResult,
        playback: PlaybackResult
    ): Outcome {
        val breakdown = tiers.map { thr ->
            val reasons = buildList {
                if (download.steadyMbps < thr.minDownloadMbps) {
                    add("Download ${"%.1f".format(download.steadyMbps)} Mbps is below the ${thr.minDownloadMbps} Mbps minimum.")
                }
                if (latency.medianMs > thr.maxLatencyMs) {
                    add("Latency ${latency.medianMs} ms exceeds the ${thr.maxLatencyMs} ms maximum.")
                }
                if (latency.jitterMs > thr.maxJitterMs) {
                    add("Jitter ${latency.jitterMs} ms exceeds the ${thr.maxJitterMs} ms maximum.")
                }
                if (playback.rebufferCount > thr.maxRebuffers) {
                    add("Playback rebuffered ${playback.rebufferCount} times (max ${thr.maxRebuffers}).")
                }
                if (playback.peakHeight in 1 until thr.playbackMinHeight) {
                    add("Playback peaked at ${playback.peakHeight}p; tier requires ${thr.playbackMinHeight}p.")
                }
            }
            TierEvaluation(thr.tier, reasons.isEmpty(), reasons)
        }

        val achieved = breakdown.filter { it.passed }
            .maxByOrNull { it.tier.ordinal }
            ?.tier
            ?: Tier.NONE

        return Outcome(achieved, breakdown)
    }
}
