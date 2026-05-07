package com.hotwire.fisiontv.networkqual.cert.probes

import android.util.Log
import com.hotwire.fisiontv.networkqual.cert.probes.internal.Insecure
import com.hotwire.fisiontv.networkqual.cert.probes.internal.ThroughputSampler
import com.hotwire.fisiontv.networkqual.config.OoklaServer
import com.hotwire.fisiontv.networkqual.config.ThroughputPhaseConfig
import com.hotwire.fisiontv.networkqual.cert.probes.ThroughputResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.isActive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Sustained HTTP upload throughput against `/speedtest/upload.php`. Uses
 * a streaming form-encoded body so per-request bytes scale to whatever
 * config dictates without RAM allocations.
 */
class HttpUploadProbe(private val cfg: ThroughputPhaseConfig) : UploadProbe {

    private val client: OkHttpClient = Insecure.unsafeClientBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(cfg.durationSec.toLong() + 10, TimeUnit.SECONDS)
        .callTimeout(cfg.durationSec.toLong() + 15, TimeUnit.SECONDS)
        .build()

    override suspend fun run(server: OoklaServer, onProgress: (Float) -> Unit): ThroughputResult {
        val totalBytes = AtomicLong(0)
        Log.i(TAG, "starting against $server")
        return ThroughputSampler.sample(
            tag = "upload",
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
        while (isActive && System.currentTimeMillis() < deadline) {
            val body = StreamingByteBody(cfg.perRequestBytes, totalBytes, deadline)
            val req = Request.Builder()
                .url(server.uploadUrl)
                .header("User-Agent", "okhttp/5.3.2")
                .header("Accept", "*/*")
                .header("Origin", "https://www.speedtest.net")
                .header("Referer", "https://www.speedtest.net/")
                .post(body)
                .build()
            try {
                client.newCall(req).execute().close()
            } catch (t: Throwable) {
                if (System.currentTimeMillis() < deadline - 500) {
                    Log.w(TAG, "upload request failed: ${t::class.simpleName}: ${t.message}")
                }
            }
        }
    }

    private class StreamingByteBody(
        private val length: Long,
        private val totalCounter: AtomicLong,
        private val deadline: Long
    ) : RequestBody() {
        private val mediaType = "application/x-www-form-urlencoded".toMediaType()
        private val prefix = "content1=".toByteArray(Charsets.US_ASCII)
        private val chunk = ByteArray(64 * 1024) { 'A'.code.toByte() }

        override fun contentType() = mediaType
        override fun contentLength(): Long = length

        override fun writeTo(sink: BufferedSink) {
            sink.write(prefix)
            totalCounter.addAndGet(prefix.size.toLong())
            var remaining = length - prefix.size
            while (remaining > 0 && System.currentTimeMillis() < deadline) {
                val toWrite = minOf(chunk.size.toLong(), remaining).toInt()
                sink.write(chunk, 0, toWrite)
                remaining -= toWrite
                totalCounter.addAndGet(toWrite.toLong())
            }
        }
    }

    companion object {
        private const val TAG = "UploadProbe"
    }
}
