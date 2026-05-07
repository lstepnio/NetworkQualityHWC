package com.hotwire.fisiontv.networkqual.cert.probes

data class DnsResult(
    val medianMs: Long,
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

data class LatencyResult(
    val samples: List<Long>,
    val medianMs: Long,
    val jitterMs: Long
) {
    companion object {
        val UNAVAILABLE = LatencyResult(emptyList(), Long.MAX_VALUE, Long.MAX_VALUE)
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
