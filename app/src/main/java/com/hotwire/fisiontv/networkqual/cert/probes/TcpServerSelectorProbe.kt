package com.hotwire.fisiontv.networkqual.cert.probes

import android.util.Log
import com.hotwire.fisiontv.networkqual.config.OoklaServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Picks the lowest-RTT server by parallel TCP-connect probes. Cheap (~1 s
 * total) and works around the lack of a sanctioned Ookla server-list API.
 */
class TcpServerSelectorProbe(
    private val candidates: List<OoklaServer>,
    private val timeoutMs: Int = 1500
) : ServerSelector {

    override suspend fun pick(): ServerSelector.Selection = withContext(Dispatchers.IO) {
        val results = coroutineScope {
            candidates.map { server -> async { probe(server) } }.awaitAll()
        }
        results.forEach { Log.d(TAG, "${it.server.name} (${it.server.host}) -> ${it.rttMs}ms ok=${it.ok}") }

        val winner = results.filter { it.ok }.minByOrNull { it.rttMs }
        if (winner != null) {
            Log.i(TAG, "selected ${winner.server.name} (${winner.server.host}) at ${winner.rttMs}ms")
            ServerSelector.Selection(winner.server, winner.rttMs, results)
        } else {
            // No probe succeeded. Fall back to the first server so the run
            // continues; downstream tests will report whatever errors they
            // encounter against it. Prevents a single network blip from
            // aborting the entire certification.
            Log.w(TAG, "no probes succeeded; falling back to ${candidates.first().name}")
            ServerSelector.Selection(candidates.first(), Long.MAX_VALUE, results)
        }
    }

    private fun probe(server: OoklaServer): ServerSelector.ProbeRtt {
        val socket = Socket()
        return try {
            val startNs = System.nanoTime()
            socket.connect(InetSocketAddress(server.host, server.port), timeoutMs)
            val elapsedMs = (System.nanoTime() - startNs) / 1_000_000
            ServerSelector.ProbeRtt(server, elapsedMs, ok = true)
        } catch (_: Throwable) {
            ServerSelector.ProbeRtt(server, Long.MAX_VALUE, ok = false)
        } finally {
            runCatching { socket.close() }
        }
    }

    companion object {
        private const val TAG = "ServerSelector"
    }
}
