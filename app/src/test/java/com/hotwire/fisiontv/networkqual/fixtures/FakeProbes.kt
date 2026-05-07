package com.hotwire.fisiontv.networkqual.fixtures

import com.hotwire.fisiontv.networkqual.cert.probes.DnsProbe
import com.hotwire.fisiontv.networkqual.cert.probes.DownloadProbe
import com.hotwire.fisiontv.networkqual.cert.probes.LatencyProbe
import com.hotwire.fisiontv.networkqual.cert.probes.PlaybackProbe
import com.hotwire.fisiontv.networkqual.cert.probes.ProbeFactory
import com.hotwire.fisiontv.networkqual.cert.probes.ServerSelector
import com.hotwire.fisiontv.networkqual.cert.probes.UploadProbe
import com.hotwire.fisiontv.networkqual.config.OoklaServer
import com.hotwire.fisiontv.networkqual.test.DnsResult
import com.hotwire.fisiontv.networkqual.test.DnsSample
import com.hotwire.fisiontv.networkqual.test.LatencyResult
import com.hotwire.fisiontv.networkqual.test.PlaybackResult
import com.hotwire.fisiontv.networkqual.test.ThroughputResult

class FakeProbeFactory(
    private val server: OoklaServer = Fixtures.server(id = "fake", host = "fake.example.com"),
    private val dns: DnsResult = okDns(),
    private val latency: LatencyResult = LatencyResult(samples = listOf(30L), medianMs = 30, jitterMs = 3),
    private val download: ThroughputResult = ThroughputResult(steadyMbps = 200.0, peakMbps = 230.0, durationSec = 10),
    private val upload: ThroughputResult = ThroughputResult(steadyMbps = 100.0, peakMbps = 120.0, durationSec = 5),
    private val playback: PlaybackResult = Fixtures.playback(peakHeight = 1080),
    private val throwOn: String? = null
) : ProbeFactory {

    override fun serverSelector() = object : ServerSelector {
        override suspend fun pick() = ServerSelector.Selection(
            selected = server,
            selectedRttMs = 50L,
            probes = listOf(ServerSelector.ProbeRtt(server, 50L, ok = true))
        )
    }

    override fun dnsProbe() = object : DnsProbe {
        override suspend fun run(onProgress: (Float) -> Unit): DnsResult {
            if (throwOn == "dns") error("simulated dns failure")
            return dns
        }
    }

    override fun latencyProbe() = object : LatencyProbe {
        override suspend fun run(host: String, port: Int, onProgress: (Float) -> Unit): LatencyResult {
            if (throwOn == "latency") error("simulated latency failure")
            return latency
        }
    }

    override fun downloadProbe() = object : DownloadProbe {
        override suspend fun run(server: OoklaServer, onProgress: (Float) -> Unit): ThroughputResult {
            if (throwOn == "download") error("simulated download failure")
            return download
        }
    }

    override fun uploadProbe() = object : UploadProbe {
        override suspend fun run(server: OoklaServer, onProgress: (Float) -> Unit): ThroughputResult {
            if (throwOn == "upload") error("simulated upload failure")
            return upload
        }
    }

    override fun playbackProbe() = object : PlaybackProbe {
        override suspend fun run(onProgress: (Float) -> Unit): PlaybackResult {
            if (throwOn == "playback") error("simulated playback failure")
            return playback
        }
    }

    companion object {
        fun okDns() = DnsResult(
            medianMs = 20L, maxMs = 30L, failureCount = 0,
            samples = listOf(DnsSample("example.com", 20L, true, listOf("1.2.3.4")))
        )
    }
}
