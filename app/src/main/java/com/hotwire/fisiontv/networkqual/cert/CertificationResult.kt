package com.hotwire.fisiontv.networkqual.cert

import com.hotwire.fisiontv.networkqual.config.OoklaServer
import com.hotwire.fisiontv.networkqual.diagnostics.NetworkDiagnostics
import com.hotwire.fisiontv.networkqual.cert.probes.DnsResult
import com.hotwire.fisiontv.networkqual.cert.probes.LatencyResult
import com.hotwire.fisiontv.networkqual.cert.probes.PlaybackResult
import com.hotwire.fisiontv.networkqual.cert.probes.ThroughputResult

data class CertificationResult(
    val certificationId: String,
    val configVersion: String,
    val startedAtMs: Long,
    val timestampMs: Long,
    /**
     * The certification headline — what the **network** can support
     * (throughput + latency + jitter), independent of what the connected
     * display managed to render. This is the number that matches what
     * FisionTV+ would actually deliver given a sufficient display.
     */
    val achievedTier: Tier,
    /**
     * What the **playback** test actually achieved during this run,
     * constrained by the connected display + HDMI link. May be lower
     * than [achievedTier] when a 1080p TV is plugged into an STB on a
     * 4K-capable connection. Equal to [achievedTier] when the display
     * isn't the bottleneck.
     */
    val playbackAchievedTier: Tier,
    val selectedServer: OoklaServer,
    val selectedServerRttMs: Long,
    val serverProbes: List<ServerProbe>,
    val dns: DnsResult,
    val latency: LatencyResult,
    val download: ThroughputResult,
    val upload: ThroughputResult,
    val playback: PlaybackResult,
    val tierBreakdown: List<TierEvaluation>,
    val diagnostics: NetworkDiagnostics,
    val health: HealthAssessment,
    val wifiLink: WifiLinkQuality?,
    /**
     * Device + radio state captured immediately before / after the
     * speedtest phase. Used post-hoc to correlate throughput variance
     * with thermal status, CPU frequency, or Wi-Fi link drift. Nullable
     * so tests can construct without wiring an Android Context.
     */
    val environmentAtSpeedtestStart: EnvironmentSnapshot? = null,
    val environmentAtSpeedtestEnd: EnvironmentSnapshot? = null
)

data class ServerProbe(
    val id: String,
    val name: String,
    val host: String,
    val rttMs: Long,
    val ok: Boolean,
    val selected: Boolean
)

data class TierEvaluation(
    val tier: Tier,
    val passed: Boolean,
    val failingReasons: List<String>
)
