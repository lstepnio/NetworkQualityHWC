package com.hotwire.fisiontv.networkqual.cert.probes

import android.content.Context
import com.hotwire.fisiontv.networkqual.config.RuntimeConfig

/**
 * Builds the per-phase probes that the engine consumes outside the
 * Ookla speedtest phase. Today: DNS + Playback only.
 *
 * Server selection, ping, throughput etc. are handled by
 * [com.hotwire.fisiontv.networkqual.cert.probes.ookla.OoklaSpeedtestPhase]
 * via the bundled Ookla embedded binary.
 */
interface ProbeFactory {
    fun dnsProbe(): DnsProbe
    fun playbackProbe(): PlaybackProbe
}

class DefaultProbeFactory(
    private val context: Context,
    private val config: RuntimeConfig
) : ProbeFactory {
    override fun dnsProbe(): DnsProbe = AndroidDnsProbe(context, config.dnsProbeHosts)
    override fun playbackProbe(): PlaybackProbe = Media3PlaybackProbe(context, config.tests.playback)
}
