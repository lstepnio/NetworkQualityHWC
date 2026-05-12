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
    private val runtime: OoklaRuntime,
    private val primaryConfigUrl: String,
    /**
     * Parallel-stream count for download. Forwarded to the Ookla binary
     * as `--download-conn-range`. Source of truth: cert-config's
     * `tests.download.parallel`. Default mirrors the empirically-best
     * value found on the lab STB.
     */
    private val downloadConnRange: Int = 8,
    /** Same as [downloadConnRange] for upload; defaults to 16. */
    private val uploadConnRange: Int = 16,
    /**
     * Optional fallback URL the phase retries against if the primary URL
     * fails before emitting a Started event (the typical TLS / config-
     * fetch failure mode). Null disables fallback.
     */
    private val fallbackConfigUrl: String? = null,
    /**
     * Acquired around the speedtest to keep the Wi-Fi radio out of
     * power-save and the CPU awake. Tests pass [PerformanceLocks.NOOP];
     * production wires [AndroidPerformanceLocks].
     */
    private val perfLocks: PerformanceLocks = PerformanceLocks.NOOP,
    private val runnerFactory: (String) -> OoklaSpeedtestRunner = { url ->
        OoklaSpeedtestRunner(runtime, url, downloadConnRange, uploadConnRange)
    }
) {
    suspend fun run(onProgress: (Float) -> Unit): OoklaSpeedtestOutcome {
        // Hold the Wi-Fi high-perf + CPU wake locks for the entirety of
        // the speedtest (primary attempt + optional fallback). The radio
        // being out of doze before TCP starts ramping eliminates a real
        // source of first-second variance.
        perfLocks.acquire().use {
            // Try primary first. If it fails before emitting a Started
            // event, the failure is almost always config-fetch (TLS / DNS
            // / network-unreachable) — retry on the fallback URL.
            // Failures *after* Started are mid-test and aren't retried
            // (would distort metrics).
            val primary = runOnce(primaryConfigUrl, onProgress)
            if (primary.outcome != null) return primary.outcome
            if (primary.startedBeforeFailure || fallbackConfigUrl == null) {
                throw OoklaFailure(primary.failure ?: "ookla speedtest failed")
            }
            Log.w(TAG, "primary URL failed before start; retrying against fallback: $fallbackConfigUrl")
            // Reset visible progress so the bar restarts cleanly.
            onProgress(0f)
            val secondary = runOnce(fallbackConfigUrl, onProgress)
            return secondary.outcome ?: throw OoklaFailure(secondary.failure ?: "ookla speedtest failed (fallback)")
        }
    }

    private data class Attempt(
        val outcome: OoklaSpeedtestOutcome?,
        val failure: String?,
        val startedBeforeFailure: Boolean
    )

    private suspend fun runOnce(configUrl: String, onProgress: (Float) -> Unit): Attempt {
        var server: OoklaServerSelection? = null
        var isp: String? = null
        var publicIp: String? = null
        var lastResult: OoklaEvent.Result? = null
        var failure: String? = null
        var started = false

        runnerFactory(configUrl).run().collect { event ->
            when (event) {
                is OoklaEvent.Started -> {
                    started = true
                    server = event.server
                    isp = event.isp
                    publicIp = event.publicIp
                    Log.i(TAG, "started: ${event.server.name} ${event.server.location} (${event.server.id}) via $configUrl")
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

        if (failure != null) {
            return Attempt(null, failure, started)
        }
        val resolvedServer = server ?: return Attempt(null, "no testStart event", started)
        val resolved = lastResult ?: return Attempt(null, "no result event", started)

        return Attempt(
            outcome = OoklaSpeedtestOutcome(
                server = OoklaServer(
                    id = "ookla-${resolvedServer.id}",
                    name = "${resolvedServer.name} (${resolvedServer.location})",
                    host = resolvedServer.host,
                    port = resolvedServer.port,
                    secure = false
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
            ),
            failure = null,
            startedBeforeFailure = true
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
