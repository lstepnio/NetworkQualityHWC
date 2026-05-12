package com.hotwire.fisiontv.networkqual.cert.probes.ookla

import android.os.Process as AndroidProcess
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * One Ookla execution emits a stream of JSONL events. We parse them
 * into typed [OoklaEvent]s and surface them as a Flow. The engine
 * consumes the Flow and translates events into per-phase progress +
 * final results.
 *
 * Why ProcessBuilder + a reader thread instead of native bindings:
 * the embedded SDK ships as a CLI binary, not a JNI library. Spawning
 * it as a subprocess from `nativeLibraryDir` is the supported pattern.
 *
 * Throws nothing — failures surface as [OoklaEvent.Failed].
 */
class OoklaSpeedtestRunner(
    private val runtime: OoklaRuntime,
    private val configUrl: String,
    /**
     * Hard ceiling for one Ookla execution — ping + download + upload
     * usually finish in ~50 s; this kills a hung subprocess at 2 minutes
     * so a frozen test doesn't strand the whole certification.
     */
    private val timeoutSec: Long = 120
) {

    fun run(): Flow<OoklaEvent> = callbackFlow {
        // Defensive: fail fast if the binary or CA bundle didn't extract
        // cleanly. Either case is unrecoverable without a reinstall.
        if (!File(runtime.binaryPath).canExecute()) {
            trySend(OoklaEvent.Failed("ookla binary missing or not executable at ${runtime.binaryPath}"))
            close(); return@callbackFlow
        }
        if (!File(runtime.caBundlePath).exists()) {
            trySend(OoklaEvent.Failed("CA bundle missing at ${runtime.caBundlePath}"))
            close(); return@callbackFlow
        }

        val process: Process = try {
            ProcessBuilder(
                runtime.binaryPath,
                "-c", configUrl,
                "--ca-certificate", runtime.caBundlePath,
                "-f", "jsonl",
                // Force the binary to ping each server in the embed pool
                // and pick the one with lowest latency. Without this flag
                // it picks the FIRST server in the embed-config response,
                // which is order-of-list — not lowest-latency. We caught
                // this in the field: a Florida lab was consistently being
                // routed to Dallas (188ms) instead of Miami (77ms),
                // driving large download-throughput variance run-to-run.
                // The flag adds <1s of probing per cert; well worth it.
                "--selection-details"
            ).redirectErrorStream(true).start()
        } catch (t: Throwable) {
            trySend(OoklaEvent.Failed("spawn: ${t::class.simpleName}: ${t.message}"))
            close(); return@callbackFlow
        }

        // Watchdog: if the subprocess outlives [timeoutSec], kill it.
        // The reader thread will see EOF and emit Failed via exit code.
        val watchdog = thread(name = "ookla-watchdog", isDaemon = true) {
            try {
                if (!process.waitFor(timeoutSec, TimeUnit.SECONDS)) {
                    Log.w(TAG, "ookla exceeded ${timeoutSec}s timeout; killing")
                    process.destroyForcibly()
                }
            } catch (_: Throwable) { /* no-op; reader handles teardown */ }
        }

        // Reader thread keeps stdout drained so the binary never blocks
        // on a full pipe. Each line is one complete JSON object per the
        // jsonl spec; a partial line at EOF is silently dropped.
        val reader = thread(name = "ookla-stdout", isDaemon = true) {
            // Elevate to near-RT priority. The reader is on the critical
            // path: if it stalls (GC pause, scheduler delay, IO-pool
            // contention) the binary's stdout pipe fills, the binary
            // blocks on write, and the test sample windows distort. On a
            // Cortex-A55 ATV STB with default nice=0 the reader can lose
            // a 50–150 ms window to a competing UI thread; URGENT_AUDIO
            // gives it strict preemption over normal threads while
            // staying out of true RT scheduling.
            try { AndroidProcess.setThreadPriority(AndroidProcess.THREAD_PRIORITY_URGENT_AUDIO) }
            catch (t: Throwable) { Log.w(TAG, "set reader priority: ${t.message}") }
            try {
                BufferedReader(InputStreamReader(process.inputStream), READ_BUFFER_BYTES).use { br ->
                    var line: String?
                    while (br.readLine().also { line = it } != null) {
                        val parsed = parseLine(line!!)
                        if (parsed != null) trySend(parsed)
                    }
                }
                val exit = process.waitFor()
                if (exit != 0) trySend(OoklaEvent.Failed("ookla exit=$exit"))
                close()
            } catch (t: Throwable) {
                trySend(OoklaEvent.Failed("${t::class.simpleName}: ${t.message}"))
                close()
            }
        }

        awaitClose {
            try { process.destroy() } catch (_: Throwable) {}
            try { reader.interrupt() } catch (_: Throwable) {}
            try { watchdog.interrupt() } catch (_: Throwable) {}
        }
    }.flowOn(Dispatchers.IO)

    private fun parseLine(line: String): OoklaEvent? {
        if (line.isEmpty() || line[0] != '{') return null
        return try {
            val o = JSONObject(line)
            when (o.optString("type")) {
                "testStart" -> parseStart(o)
                "ping" -> parsePing(o)
                "download" -> parseDownload(o)
                "upload" -> parseUpload(o)
                "result" -> parseResult(o)
                "log" -> {
                    Log.i(TAG, "ookla log: ${o.optString("message")}")
                    null
                }
                else -> null
            }
        } catch (t: Throwable) {
            Log.w(TAG, "bad line: ${line.take(120)} — ${t.message}")
            null
        }
    }

    private fun parseStart(o: JSONObject): OoklaEvent.Started {
        val s = o.getJSONObject("server")
        val iface = o.optJSONObject("interface")
        return OoklaEvent.Started(
            server = OoklaServerSelection(
                id = s.optInt("id"),
                name = s.optString("name"),
                location = s.optString("location"),
                country = s.optString("country"),
                host = s.optString("host"),
                port = s.optInt("port", 8080),
                ip = s.optString("ip")
            ),
            isp = o.optString("isp"),
            publicIp = iface?.optString("externalIp") ?: ""
        )
    }

    private fun parsePing(o: JSONObject): OoklaEvent.PingTick {
        val p = o.getJSONObject("ping")
        return OoklaEvent.PingTick(
            progress = p.optDouble("progress", 0.0).toFloat(),
            latencyMs = p.optDouble("latency", 0.0),
            jitterMs = p.optDouble("jitter", 0.0)
        )
    }

    private fun parseDownload(o: JSONObject): OoklaEvent.DownloadTick {
        val d = o.getJSONObject("download")
        return OoklaEvent.DownloadTick(
            progress = d.optDouble("progress", 0.0).toFloat(),
            bandwidthBytesPerSec = d.optLong("bandwidth"),
            bytesTotal = d.optLong("bytes"),
            elapsedMs = d.optLong("elapsed"),
            latencyUnderLoadMs = d.optJSONObject("latency")?.optDouble("iqm")
        )
    }

    private fun parseUpload(o: JSONObject): OoklaEvent.UploadTick {
        val u = o.getJSONObject("upload")
        return OoklaEvent.UploadTick(
            progress = u.optDouble("progress", 0.0).toFloat(),
            bandwidthBytesPerSec = u.optLong("bandwidth"),
            bytesTotal = u.optLong("bytes"),
            elapsedMs = u.optLong("elapsed"),
            latencyUnderLoadMs = u.optJSONObject("latency")?.optDouble("iqm")
        )
    }

    private fun parseResult(o: JSONObject): OoklaEvent.Result {
        val ping = o.optJSONObject("ping")
        val dl = o.optJSONObject("download")
        val ul = o.optJSONObject("upload")
        val packetLoss = o.optDouble("packetLoss", -1.0).takeIf { it >= 0 }
        return OoklaEvent.Result(
            pingMedianMs = ping?.optDouble("latency") ?: 0.0,
            pingJitterMs = ping?.optDouble("jitter") ?: 0.0,
            pingLowMs = ping?.optDouble("low"),
            pingHighMs = ping?.optDouble("high"),
            downloadBytesPerSec = dl?.optLong("bandwidth") ?: 0L,
            downloadBytesTotal = dl?.optLong("bytes") ?: 0L,
            downloadElapsedMs = dl?.optLong("elapsed") ?: 0L,
            uploadBytesPerSec = ul?.optLong("bandwidth") ?: 0L,
            uploadBytesTotal = ul?.optLong("bytes") ?: 0L,
            uploadElapsedMs = ul?.optLong("elapsed") ?: 0L,
            packetLossPct = packetLoss,
            resultUrl = o.optJSONObject("result")?.optString("url")
        )
    }

    companion object {
        private const val TAG = "OoklaRunner"
        // Larger than the BufferedReader default (8 KiB) so the streaming
        // jsonl events don't bottleneck on syscalls during fast phases.
        private const val READ_BUFFER_BYTES = 64 * 1024
    }
}
