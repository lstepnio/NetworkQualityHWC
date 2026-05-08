package com.hotwire.fisiontv.networkqual.cert.probes.ookla

/** A typed slice of one Ookla `jsonl` stream. */
sealed interface OoklaEvent {
    data class Started(
        val server: OoklaServerSelection,
        val isp: String,
        val publicIp: String
    ) : OoklaEvent

    /** One ping sample. `progress` runs 0.0..1.0 across the ping phase. */
    data class PingTick(
        val progress: Float,
        val latencyMs: Double,
        val jitterMs: Double
    ) : OoklaEvent

    /**
     * Streaming download throughput sample. `bandwidthBytesPerSec` is the
     * Ookla-internal unit; convert to Mbps via `* 8 / 1_000_000`.
     */
    data class DownloadTick(
        val progress: Float,
        val bandwidthBytesPerSec: Long,
        val bytesTotal: Long,
        val elapsedMs: Long,
        val latencyUnderLoadMs: Double?
    ) : OoklaEvent

    data class UploadTick(
        val progress: Float,
        val bandwidthBytesPerSec: Long,
        val bytesTotal: Long,
        val elapsedMs: Long,
        val latencyUnderLoadMs: Double?
    ) : OoklaEvent

    /**
     * Final aggregated result emitted at the end of the run. We mostly
     * derive our typed result objects from this; per-tick events are
     * for progress reporting.
     */
    data class Result(
        val pingMedianMs: Double,
        val pingJitterMs: Double,
        val pingLowMs: Double?,
        val pingHighMs: Double?,
        val downloadBytesPerSec: Long,
        val downloadBytesTotal: Long,
        val downloadElapsedMs: Long,
        val uploadBytesPerSec: Long,
        val uploadBytesTotal: Long,
        val uploadElapsedMs: Long,
        val packetLossPct: Double?,
        val resultUrl: String?
    ) : OoklaEvent

    data class Failed(val cause: String) : OoklaEvent
}

data class OoklaServerSelection(
    val id: Int,
    val name: String,
    val location: String,
    val country: String,
    val host: String,
    val port: Int,
    val ip: String
)
