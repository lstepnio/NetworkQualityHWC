package com.hotwire.fisiontv.networkqual.cert.probes

import com.hotwire.fisiontv.networkqual.config.OoklaServer
import com.hotwire.fisiontv.networkqual.test.DnsResult
import com.hotwire.fisiontv.networkqual.test.LatencyResult
import com.hotwire.fisiontv.networkqual.test.PlaybackResult
import com.hotwire.fisiontv.networkqual.test.ThroughputResult

/**
 * Per-phase probe interfaces. The certification engine depends on these,
 * never on concrete implementations. Today these are backed by hand-rolled
 * OkHttp / TCP / Media3 code in [com.hotwire.fisiontv.networkqual.test];
 * dropping in the Ookla SDK later means writing new implementations and
 * pointing [ProbeFactory] at them.
 *
 * Every implementation must:
 *   - be pure with respect to its config (no globals, no singletons besides
 *     the platform services it inherently needs)
 *   - clean up resources before returning (no leaked sockets / players)
 *   - throw on hard failure rather than returning a sentinel; the engine
 *     catches and surfaces the failure as `EngineEvent.Failed`.
 */

interface ServerSelector {
    suspend fun pick(): Selection

    data class Selection(
        val selected: OoklaServer,
        val selectedRttMs: Long,
        val probes: List<ProbeRtt>
    )

    data class ProbeRtt(val server: OoklaServer, val rttMs: Long, val ok: Boolean)
}

interface DnsProbe {
    suspend fun run(onProgress: (Float) -> Unit = {}): DnsResult
}

interface LatencyProbe {
    suspend fun run(host: String, port: Int, onProgress: (Float) -> Unit = {}): LatencyResult
}

interface DownloadProbe {
    suspend fun run(server: OoklaServer, onProgress: (Float) -> Unit = {}): ThroughputResult
}

interface UploadProbe {
    suspend fun run(server: OoklaServer, onProgress: (Float) -> Unit = {}): ThroughputResult
}

interface PlaybackProbe {
    suspend fun run(onProgress: (Float) -> Unit = {}): PlaybackResult
}
