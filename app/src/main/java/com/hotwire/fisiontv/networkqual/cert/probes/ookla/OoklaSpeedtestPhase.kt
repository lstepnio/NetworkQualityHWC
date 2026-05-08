package com.hotwire.fisiontv.networkqual.cert.probes.ookla

import android.util.Log
import com.hotwire.fisiontv.networkqual.cert.probes.LatencyResult
import com.hotwire.fisiontv.networkqual.cert.probes.ThroughputResult
import com.hotwire.fisiontv.networkqual.config.OoklaServer
import kotlinx.coroutines.flow.collect

/**
 * High-level wrapper that runs one Ookla speedtest and returns the
 * combined results in our domain types. The engine treats this as a
 * single "speedtest" phase and forwards Ookla's per-sub-phase progress
 * via [onProgress] mapped onto the phase's overall 0..1 weight:
 *
 *   ping     -> 0.00 .. 0.10
 *   download -> 0.10 .. 0.65
 *   upload   -> 0.65 .. 1.00
 *
 * If the binary fails or produces no Result event, throws so the
 * engine routes to its `phase()` failure handler.
 */
class OoklaSpeedtestPhase(
    private val runner: OoklaSpeedtestRunner
) {
    suspend fun run(onProgress: (Float) -> Unit): OoklaSpeedtestOutcome {
        var server: OoklaServerSelection? = null
        var isp: String? = null
        var publicIp: String? = null
        var lastResult: OoklaEvent.Result? = null
        var failure: String? = null

        runner.run().collect { event ->
            when (event) {
                is OoklaEvent.Started -> {
                    server = event.server
                    isp = event.isp
                    publicIp = event.publicIp
                    Log.i(TAG, "started: ${event.server.name} ${event.server.location} (${event.server.id})")
                }
                is OoklaEvent.PingTick ->
                    onProgress((event.progress * PING_FRAC).coerceIn(0f, 1f))
                is OoklaEvent.DownloadTick ->
                    onProgress((PING_FRAC + event.progress * DOWNLOAD_FRAC).coerceIn(0f, 1f))
                is OoklaEvent.UploadTick ->
                    onProgress((PING_FRAC + DOWNLOAD_FRAC + event.progress * UPLOAD_FRAC).coerceIn(0f, 1f))
                is OoklaEvent.Result -> lastResult = event
                is OoklaEvent.Failed -> failure = event.cause
            }
        }
        onProgress(1f)

        failure?.let { throw OoklaFailure(it) }
        val resolvedServer = server ?: throw OoklaFailure("no testStart event")
        val resolved = lastResult ?: throw OoklaFailure("no result event")

        return OoklaSpeedtestOutcome(
            server = OoklaServer(
                id = "ookla-${resolvedServer.id}",
                name = "${resolvedServer.name} (${resolvedServer.location})",
                host = resolvedServer.host,
                port = resolvedServer.port,
                secure = false // Ookla server traffic is over the published port; secure flag is informational
            ),
            serverIp = resolvedServer.ip,
            isp = isp ?: "",
            publicIp = publicIp ?: "",
            latency = toLatencyResult(resolved),
            download = toThroughputResult(
                bytesPerSec = resolved.downloadBytesPerSec,
                durationMs = resolved.downloadElapsedMs
            ),
            upload = toThroughputResult(
                bytesPerSec = resolved.uploadBytesPerSec,
                durationMs = resolved.uploadElapsedMs
            ),
            packetLossPct = resolved.packetLossPct
        )
    }

    private fun toLatencyResult(r: OoklaEvent.Result): LatencyResult {
        // Ookla doesn't expose per-sample arrays in `result`; we use the
        // aggregate stats it provides. P95 isn't published directly so we
        // approximate it as median + 2*jitter (Ookla's jitter is the mean
        // absolute deviation between samples).
        val median = r.pingMedianMs.toLong()
        val jitter = r.pingJitterMs.toLong().coerceAtLeast(0L)
        val p95 = (r.pingHighMs ?: (r.pingMedianMs + 2 * r.pingJitterMs)).toLong()
        val lossPct = (r.packetLossPct ?: 0.0).toInt()
        return LatencyResult(
            samples = listOfNotNull(
                r.pingLowMs?.toLong(),
                median,
                r.pingHighMs?.toLong()
            ),
            medianMs = median,
            p95Ms = p95,
            jitterMs = jitter,
            attempted = 5, // ookla pings 5 times by default
            lossPct = lossPct
        )
    }

    private fun toThroughputResult(bytesPerSec: Long, durationMs: Long): ThroughputResult {
        val mbps = bytesPerSec * 8.0 / 1_000_000.0
        // Ookla's reported bandwidth is the steady throughput. Peak isn't
        // exposed in the final result; use steady for both. Per-tick max
        // could be tracked in the runner if a real peak is needed later.
        return ThroughputResult(
            steadyMbps = mbps,
            peakMbps = mbps,
            durationSec = (durationMs / 1000).toInt().coerceAtLeast(1)
        )
    }

    companion object {
        private const val TAG = "OoklaPhase"
        private const val PING_FRAC = 0.10f
        private const val DOWNLOAD_FRAC = 0.55f
        private const val UPLOAD_FRAC = 0.35f
    }
}

class OoklaFailure(message: String) : RuntimeException(message)

/**
 * Output of a single Ookla speedtest run, in our domain types.
 *
 * [serverIp], [isp], [publicIp] aren't currently part of the engine's
 * result schema — they're available here for future enrichment of the
 * payload (the network.publicIp field, ISP attribution, etc.).
 */
data class OoklaSpeedtestOutcome(
    val server: OoklaServer,
    val serverIp: String,
    val isp: String,
    val publicIp: String,
    val latency: LatencyResult,
    val download: ThroughputResult,
    val upload: ThroughputResult,
    val packetLossPct: Double?
)
