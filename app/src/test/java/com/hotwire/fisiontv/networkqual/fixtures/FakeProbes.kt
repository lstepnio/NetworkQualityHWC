package com.hotwire.fisiontv.networkqual.fixtures

import com.hotwire.fisiontv.networkqual.cert.probes.DnsProbe
import com.hotwire.fisiontv.networkqual.cert.probes.DnsResult
import com.hotwire.fisiontv.networkqual.cert.probes.DnsSample
import com.hotwire.fisiontv.networkqual.cert.probes.PlaybackProbe
import com.hotwire.fisiontv.networkqual.cert.probes.PlaybackResult
import com.hotwire.fisiontv.networkqual.cert.probes.ProbeFactory

class FakeProbeFactory(
    private val dns: DnsResult = okDns(),
    private val playback: PlaybackResult = Fixtures.playback(peakHeight = 1080),
    private val throwOn: String? = null
) : ProbeFactory {

    override fun dnsProbe() = object : DnsProbe {
        override suspend fun run(onProgress: (Float) -> Unit): DnsResult {
            if (throwOn == "dns") error("simulated dns failure")
            return dns
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
            medianMs = 20L, p95Ms = 28L, maxMs = 30L, failureCount = 0,
            samples = listOf(DnsSample("example.com", 20L, true, listOf("1.2.3.4")))
        )
    }
}
