package com.hotwire.fisiontv.networkqual.cert.probes

/**
 * Per-phase probe interfaces the engine still consumes.
 *
 * Server selection + ping + download + upload now run as one Ookla
 * speedtest (see ookla/OoklaSpeedtestPhase). DNS and Playback remain
 * standalone phases with their own probe interfaces.
 */

interface DnsProbe {
    suspend fun run(onProgress: (Float) -> Unit = {}): DnsResult
}

interface PlaybackProbe {
    suspend fun run(onProgress: (Float) -> Unit = {}): PlaybackResult
}
