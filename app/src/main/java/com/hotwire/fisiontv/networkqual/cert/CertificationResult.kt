package com.hotwire.fisiontv.networkqual.cert

import com.hotwire.fisiontv.networkqual.config.OoklaServer
import com.hotwire.fisiontv.networkqual.diagnostics.NetworkDiagnostics
import com.hotwire.fisiontv.networkqual.test.DnsResult
import com.hotwire.fisiontv.networkqual.test.LatencyResult
import com.hotwire.fisiontv.networkqual.test.PlaybackResult
import com.hotwire.fisiontv.networkqual.test.ThroughputResult

data class CertificationResult(
    val certificationId: String,
    val configVersion: String,
    val startedAtMs: Long,
    val timestampMs: Long,
    val achievedTier: Tier,
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
    val wifiLink: WifiLinkQuality?
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
