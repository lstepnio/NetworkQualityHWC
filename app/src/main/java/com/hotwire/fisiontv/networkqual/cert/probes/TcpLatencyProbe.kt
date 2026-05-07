package com.hotwire.fisiontv.networkqual.cert.probes

import android.util.Log
import com.hotwire.fisiontv.networkqual.config.LatencyPhaseConfig
import com.hotwire.fisiontv.networkqual.cert.probes.LatencyResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.math.abs

/**
 * Latency via TCP-connect handshake. Real RTT-style measurement (no ICMP
 * required, which Android doesn't expose). Jitter is computed via Median
 * Absolute Deviation × 1.4826 so a single outlier sample (transient blip,
 * scheduler stall, GC pause) doesn't wreck the metric.
 */
class TcpLatencyProbe(private val cfg: LatencyPhaseConfig) : LatencyProbe {

    override suspend fun run(host: String, port: Int, onProgress: (Float) -> Unit): LatencyResult =
        withContext(Dispatchers.IO) {
            val rtts = mutableListOf<Long>()
            repeat(cfg.samples) { i ->
                val socket = Socket()
                try {
                    val startNs = System.nanoTime()
                    socket.connect(InetSocketAddress(host, port), cfg.timeoutMs)
                    rtts += (System.nanoTime() - startNs) / 1_000_000
                } catch (_: Throwable) {
                    // Skip failed sample. The result is judged on what we have;
                    // if every sample fails we return UNAVAILABLE below.
                } finally {
                    runCatching { socket.close() }
                }
                onProgress((i + 1).toFloat() / cfg.samples)
            }
            if (rtts.isEmpty()) {
                Log.w(TAG, "no successful samples to $host:$port")
                return@withContext LatencyResult.UNAVAILABLE
            }

            val sorted = rtts.sorted()
            val median = sorted[sorted.size / 2]
            val deviations = sorted.map { abs(it - median) }.sorted()
            val mad = deviations[deviations.size / 2]
            val jitter = (mad * 1.4826).toLong()
            Log.i(TAG, "median=${median}ms jitter=±${jitter}ms (MAD) samples=$rtts")
            LatencyResult(samples = rtts, medianMs = median, jitterMs = jitter)
        }

    companion object {
        private const val TAG = "LatencyProbe"
    }
}
