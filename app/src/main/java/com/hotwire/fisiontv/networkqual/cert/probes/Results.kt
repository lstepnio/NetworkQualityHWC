package com.hotwire.fisiontv.networkqual.cert.probes

data class DnsResult(
    val medianMs: Long,
    val p95Ms: Long,
    val maxMs: Long,
    val failureCount: Int,
    val samples: List<DnsSample>
)

data class DnsSample(
    val host: String,
    val resolveMs: Long,
    val success: Boolean,
    val resolvedIps: List<String>,
    val error: String? = null
)

/**
 * Latency / loss summary for a single phase run.
 *
 * Customer-facing surface uses [medianMs] (P50, "typical experience"),
 * [p95Ms] (felt-spike envelope), and [lossPct] (timeouts as % of attempts).
 * [jitterMs] is the MAD-based dispersion; kept for advanced/debug views
 * but not the primary number on screen — operationally P95 is the more
 * actionable spike metric.
 *
 * [attempted] tracks how many samples were tried; len([samples]) is how
 * many succeeded. lossPct = (attempted - samples.size) / attempted.
 */
data class LatencyResult(
    val samples: List<Long>,
    val medianMs: Long,
    val p95Ms: Long,
    val jitterMs: Long,
    val attempted: Int,
    val lossPct: Int
) {
    companion object {
        val UNAVAILABLE = LatencyResult(
            samples = emptyList(),
            medianMs = Long.MAX_VALUE,
            p95Ms = Long.MAX_VALUE,
            jitterMs = Long.MAX_VALUE,
            attempted = 0,
            lossPct = 100
        )
    }
}

data class ThroughputResult(
    val steadyMbps: Double,
    val peakMbps: Double,
    val durationSec: Int
)

data class PlaybackResult(
    val timeToFirstFrameMs: Long,
    val rebufferCount: Int,
    val totalRebufferMs: Long,
    val peakBitrateKbps: Int,
    val peakHeight: Int,
    val bitrateSwitchCount: Int,
    val playedSec: Int
)
