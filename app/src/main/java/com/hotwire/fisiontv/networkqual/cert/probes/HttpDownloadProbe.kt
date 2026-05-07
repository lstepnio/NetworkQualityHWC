package com.hotwire.fisiontv.networkqual.cert.probes

import android.util.Log
import com.hotwire.fisiontv.networkqual.cert.probes.internal.Insecure
import com.hotwire.fisiontv.networkqual.cert.probes.internal.ThroughputSampler
import com.hotwire.fisiontv.networkqual.config.OoklaServer
import com.hotwire.fisiontv.networkqual.config.ThroughputPhaseConfig
import com.hotwire.fisiontv.networkqual.test.ThroughputResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.isActive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Sustained HTTP download throughput against the legacy
 * `/speedtest/random{N}x{N}.jpg` endpoint that the Ookla server still
 * serves real bytes from. Spawns [ThroughputPhaseConfig.parallel] workers
 * that chain requests until the time deadline.
 *
 * To swap to the Ookla SDK, write `OoklaSdkDownloadProbe` implementing
 * [DownloadProbe] and switch the wiring in `ProbeFactory`.
 */
class HttpDownloadProbe(private val cfg: ThroughputPhaseConfig) : DownloadProbe {

    private val client: OkHttpClient = Insecure.unsafeClientBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(cfg.durationSec.toLong() + 10, TimeUnit.SECONDS)
        .callTimeout(cfg.durationSec.toLong() + 15, TimeUnit.SECONDS)
        .build()

    override suspend fun run(server: OoklaServer, onProgress: (Float) -> Unit): ThroughputResult {
        val totalBytes = AtomicLong(0)
        Log.i(TAG, "starting against $server")
        return ThroughputSampler.sample(
            tag = "download",
            cfg = cfg,
            totalBytes = totalBytes,
            onProgress = onProgress,
            startWorkers = { deadline ->
                (1..cfg.parallel).map {
                    async(Dispatchers.IO) { workerLoop(server, totalBytes, deadline) }
                }
            }
        )
    }

    private suspend fun CoroutineScope.workerLoop(
        server: OoklaServer,
        totalBytes: AtomicLong,
        deadline: Long
    ) {
        val buffer = ByteArray(64 * 1024)
        var requestNum = 0
        while (isActive && System.currentTimeMillis() < deadline) {
            requestNum++
            val url = server.downloadUrl(cfg.perRequestBytes)
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "okhttp/5.3.2")
                .header("Accept", "*/*")
                .header("Origin", "https://www.speedtest.net")
                .header("Referer", "https://www.speedtest.net/")
                .build()
            try {
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        Log.w(TAG, "request #$requestNum non-success code=${resp.code}; skipping body")
                        return@use
                    }
                    val stream = resp.body?.byteStream() ?: return@use
                    while (System.currentTimeMillis() < deadline) {
                        val n = stream.read(buffer)
                        if (n <= 0) break
                        totalBytes.addAndGet(n.toLong())
                    }
                }
            } catch (t: Throwable) {
                if (System.currentTimeMillis() < deadline - 500) {
                    Log.w(TAG, "request #$requestNum failed: ${t::class.simpleName}: ${t.message}")
                }
            }
        }
    }

    companion object {
        private const val TAG = "DownloadProbe"
    }
}
