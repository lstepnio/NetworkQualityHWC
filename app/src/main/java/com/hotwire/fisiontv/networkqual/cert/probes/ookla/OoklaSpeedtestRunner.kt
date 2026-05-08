package com.hotwire.fisiontv.networkqual.cert.probes.ookla

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
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
    private val configUrl: String
) {

    fun run(): Flow<OoklaEvent> = callbackFlow {
        val process: Process = try {
            ProcessBuilder(
                runtime.binaryPath,
                "-c", configUrl,
                "--ca-certificate", runtime.caBundlePath,
                "-f", "jsonl"
            ).redirectErrorStream(true).start()
        } catch (t: Throwable) {
            trySend(OoklaEvent.Failed("spawn: ${t::class.simpleName}: ${t.message}"))
            close()
            return@callbackFlow
        }

        // Reader thread keeps stdout from blocking the binary. Each
        // line is one complete JSON object per the jsonl spec.
        val reader = thread(name = "ookla-stdout", isDaemon = true) {
            try {
                BufferedReader(InputStreamReader(process.inputStream)).use { br ->
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
    }
}
