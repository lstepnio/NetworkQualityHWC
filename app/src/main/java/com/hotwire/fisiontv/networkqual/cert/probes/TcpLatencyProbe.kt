package com.hotwire.fisiontv.networkqual.cert.probes

import android.util.Log
import com.hotwire.fisiontv.networkqual.config.LatencyPhaseConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.math.abs

/**
 * Latency / loss via TCP-connect handshake. Real RTT-style measurement
 * (no ICMP required — Android doesn't expose it).
 *
 * Reports the metrics service-provider operations care about:
 *   - P50 (median)  — typical customer experience
 *   - P95           — meaningful spike envelope, what causes felt stutters
 *   - loss %        — sample timeouts as a fraction of attempts
 *
 * Also retains MAD-based jitter for advanced/debug views.
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
                    // Sample timed out / refused — counts toward loss.
                } finally {
                    runCatching { socket.close() }
                }
                onProgress((i + 1).toFloat() / cfg.samples)
            }
            val attempted = cfg.samples
            val lost = attempted - rtts.size
            val lossPct = (lost * 100) / attempted

            if (rtts.isEmpty()) {
                Log.w(TAG, "no successful samples to $host:$port (loss=100%)")
                return@withContext LatencyResult.UNAVAILABLE
            }

            val sorted = rtts.sorted()
            val median = percentile(sorted, 0.50)
            val p95 = percentile(sorted, 0.95)

            // MAD-based jitter retained for advanced views; not surfaced
            // as primary on screen because P95 is more actionable.
            val deviations = sorted.map { abs(it - median) }.sorted()
            val mad = deviations[deviations.size / 2]
            val jitter = (mad * 1.4826).toLong()

            Log.i(TAG, "median=${median}ms p95=${p95}ms loss=${lossPct}% jitter=±${jitter}ms samples=$rtts")
            LatencyResult(
                samples = rtts,
                medianMs = median,
                p95Ms = p95,
                jitterMs = jitter,
                attempted = attempted,
                lossPct = lossPct
            )
        }

    companion object {
        private const val TAG = "LatencyProbe"

        /**
         * Linearly-interpolated percentile. Stable for small samples
         * where the index falls between two values.
         */
        internal fun percentile(sorted: List<Long>, p: Double): Long {
            if (sorted.isEmpty()) return Long.MAX_VALUE
            if (sorted.size == 1) return sorted[0]
            val rank = p * (sorted.size - 1)
            val lo = rank.toInt()
            val hi = (lo + 1).coerceAtMost(sorted.size - 1)
            val frac = rank - lo
            return (sorted[lo] * (1 - frac) + sorted[hi] * frac).toLong()
        }
    }
}
