package com.hotwire.fisiontv.networkqual.cert.probes.internal

import android.util.Log
import com.hotwire.fisiontv.networkqual.config.ThroughputPhaseConfig
import com.hotwire.fisiontv.networkqual.test.ThroughputResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * Shared sampling loop used by both download and upload throughput probes.
 *
 * Spawns a caller-supplied set of workers that mutate [totalBytes] until
 * the time deadline, samples the byte counter every 500 ms, derives steady
 * (mean of post-warmup samples) and peak (max sample) Mbps, and reports
 * progress at 100 ms cadence on a separate ticker so the UI stays smooth
 * even if the workers are starving for CPU.
 */
internal object ThroughputSampler {

    private const val TAG = "ThroughputSampler"
    private const val SAMPLE_INTERVAL_MS = 500L
    private const val PROGRESS_INTERVAL_MS = 100L

    suspend fun sample(
        tag: String,
        cfg: ThroughputPhaseConfig,
        totalBytes: AtomicLong,
        onProgress: (Float) -> Unit,
        startWorkers: suspend CoroutineScope.(deadline: Long) -> List<Deferred<*>>
    ): ThroughputResult = coroutineScope {
        val startedAtMs = System.currentTimeMillis()
        val deadline = startedAtMs + cfg.durationSec * 1000L
        val workers = startWorkers(deadline)

        val progressTicker = launch(Dispatchers.Default) {
            while (System.currentTimeMillis() < deadline && isActive) {
                val frac = ((System.currentTimeMillis() - startedAtMs).toFloat() / (cfg.durationSec * 1000))
                    .coerceIn(0f, 1f)
                onProgress(frac)
                delay(PROGRESS_INTERVAL_MS)
            }
        }

        val samples = mutableListOf<Double>()
        var lastSampleAtMs = startedAtMs
        var lastSampleBytes = 0L
        while (System.currentTimeMillis() < deadline && isActive) {
            delay(SAMPLE_INTERVAL_MS)
            val now = System.currentTimeMillis()
            val elapsedSinceLast = (now - lastSampleAtMs) / 1000.0
            if (elapsedSinceLast <= 0) continue
            val current = totalBytes.get()
            val deltaBits = (current - lastSampleBytes) * 8.0
            samples += (deltaBits / 1_000_000.0) / elapsedSinceLast
            lastSampleAtMs = now
            lastSampleBytes = current
        }

        progressTicker.cancel()
        workers.forEach { it.cancel() }
        onProgress(1f)

        val warmupSkip = (samples.size * cfg.warmupFraction).toInt().coerceAtLeast(1)
        val steady = samples.drop(warmupSkip).takeIf { it.isNotEmpty() }?.average() ?: 0.0
        val peak = samples.maxOrNull() ?: 0.0

        Log.i(TAG, "$tag: totalBytes=${totalBytes.get()} samples=${samples.size} steady=$steady peak=$peak")
        ThroughputResult(steadyMbps = steady, peakMbps = peak, durationSec = cfg.durationSec)
    }
}
