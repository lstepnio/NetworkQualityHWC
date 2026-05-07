package com.hotwire.fisiontv.networkqual.cert.probes

import android.content.Context
import com.hotwire.fisiontv.networkqual.config.RuntimeConfig

/**
 * Provides the per-phase probes the engine consumes. Engine depends on
 * this interface only — concrete probe classes live behind it. Swap in an
 * Ookla-SDK-backed download probe by writing a new ProbeFactory and
 * passing it to the engine.
 */
interface ProbeFactory {
    fun serverSelector(): ServerSelector
    fun dnsProbe(): DnsProbe
    fun latencyProbe(): LatencyProbe
    fun downloadProbe(): DownloadProbe
    fun uploadProbe(): UploadProbe
    fun playbackProbe(): PlaybackProbe
}

/**
 * Production probe factory backed by hand-rolled OkHttp / TCP / Media3
 * implementations. Constructs fresh probes per call so each phase gets an
 * isolated client; cheap given how rarely runs happen.
 */
class DefaultProbeFactory(
    private val context: Context,
    private val config: RuntimeConfig
) : ProbeFactory {
    override fun serverSelector(): ServerSelector = TcpServerSelectorProbe(config.servers)
    override fun dnsProbe(): DnsProbe = AndroidDnsProbe(context, config.dnsProbeHosts)
    override fun latencyProbe(): LatencyProbe = TcpLatencyProbe(config.tests.latency)
    override fun downloadProbe(): DownloadProbe = HttpDownloadProbe(config.tests.download)
    override fun uploadProbe(): UploadProbe = HttpUploadProbe(config.tests.upload)
    override fun playbackProbe(): PlaybackProbe = Media3PlaybackProbe(context, config.tests.playback)
}
