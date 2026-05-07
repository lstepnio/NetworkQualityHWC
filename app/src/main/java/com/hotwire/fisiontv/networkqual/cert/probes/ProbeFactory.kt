package com.hotwire.fisiontv.networkqual.cert.probes

import android.content.Context
import com.hotwire.fisiontv.networkqual.config.RuntimeConfig

/**
 * Builds the per-phase probes for the engine. Centralizing construction
 * here means the engine never imports concrete probe classes — swap in an
 * Ookla-SDK-backed download probe by editing one method.
 */
class ProbeFactory(
    private val context: Context,
    private val config: RuntimeConfig
) {
    fun serverSelector(): ServerSelector = TcpServerSelectorProbe(config.servers)
    fun dnsProbe(): DnsProbe = AndroidDnsProbe(context, config.dnsProbeHosts)
    fun latencyProbe(): LatencyProbe = TcpLatencyProbe(config.tests.latency)
    fun downloadProbe(): DownloadProbe = HttpDownloadProbe(config.tests.download)
    fun uploadProbe(): UploadProbe = HttpUploadProbe(config.tests.upload)
    fun playbackProbe(): PlaybackProbe = Media3PlaybackProbe(context, config.tests.playback)
}
