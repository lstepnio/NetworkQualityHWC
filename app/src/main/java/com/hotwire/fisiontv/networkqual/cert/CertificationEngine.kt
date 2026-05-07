package com.hotwire.fisiontv.networkqual.cert

import android.content.Context
import android.util.Log
import com.hotwire.fisiontv.networkqual.cert.probes.DefaultProbeFactory
import com.hotwire.fisiontv.networkqual.cert.probes.ProbeFactory
import com.hotwire.fisiontv.networkqual.cert.probes.ServerSelector
import com.hotwire.fisiontv.networkqual.config.RuntimeConfig
import com.hotwire.fisiontv.networkqual.diagnostics.NetworkDiagnostics
import com.hotwire.fisiontv.networkqual.diagnostics.NetworkDiagnosticsCollector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import java.util.UUID

/**
 * Orchestrates a certification run end-to-end.
 *
 * The engine knows nothing about HTTP, OkHttp, ExoPlayer, or Ookla — every
 * test is hidden behind a probe interface in [com.hotwire.fisiontv.networkqual.cert.probes].
 * Configuration arrives via [RuntimeConfig]; nothing is hardcoded here.
 *
 * Failures in any phase are routed to [EngineEvent.Failed]. We deliberately
 * don't continue past a failed phase: subsequent metrics would be either
 * irrelevant (no server selected) or misleading (bandwidth without latency).
 */
class CertificationEngine(
    private val config: RuntimeConfig,
    private val probes: ProbeFactory,
    private val collectDiagnostics: () -> NetworkDiagnostics,
    private val tierEvaluator: TierEvaluator = TierEvaluator(config.tiers),
    private val healthAssessor: HealthAssessor = HealthAssessor(config.tiers, config.healthAssessment),
    private val wifiAssessor: WifiLinkQualityAssessor = WifiLinkQualityAssessor(config.wifiLinkQuality)
) {

    /**
     * Production constructor. Resolves probes and diagnostics from the
     * Android context. Tests should use the primary constructor with
     * fakes instead.
     */
    constructor(context: Context, config: RuntimeConfig) : this(
        config = config,
        probes = DefaultProbeFactory(context, config),
        collectDiagnostics = { NetworkDiagnosticsCollector.collect(context) }
    )

    private class HaltSignal : Throwable() {
        override fun fillInStackTrace(): Throwable = this
    }

    fun run(): Flow<EngineEvent> = channelFlow {
        val totalWeight = TestStep.values().sumOf { it.weight.toDouble() }
        fun weightOf(step: TestStep): Float = (step.weight / totalWeight).toFloat()

        val certificationId = UUID.randomUUID().toString()
        val startedAtMs = System.currentTimeMillis()
        var priorOverall = 0f

        fun reportProgress(step: TestStep, stepFrac: Float) {
            val sf = stepFrac.coerceIn(0f, 1f)
            val overall = (priorOverall + sf * weightOf(step)).coerceIn(0f, 1f)
            trySend(EngineEvent.StepProgress(step, sf, overall))
        }

        fun completeStep(step: TestStep) {
            priorOverall = (priorOverall + weightOf(step)).coerceIn(0f, 1f)
            trySend(EngineEvent.StepProgress(step, 1f, priorOverall))
        }

        suspend fun <T> phase(step: TestStep, body: suspend ((Float) -> Unit) -> T): T {
            reportProgress(step, 0f)
            return try {
                val result = body { p -> reportProgress(step, p) }
                completeStep(step)
                result
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Log.e(TAG, "phase ${step.name} failed: ${t::class.simpleName}: ${t.message}", t)
                send(EngineEvent.Failed(step, t.message ?: t.toString()))
                throw HaltSignal()
            }
        }

        try {
            val diagnostics = collectDiagnostics()

            val dns = phase(TestStep.DNS) { progress -> probes.dnsProbe().run(progress) }

            val selection = phase(TestStep.SERVER_SELECT) { _ -> probes.serverSelector().pick() }
            val server = selection.selected
            val serverProbes = selection.probes.map { p ->
                ServerProbe(
                    id = p.server.id,
                    name = p.server.name,
                    host = p.server.host,
                    rttMs = p.rttMs,
                    ok = p.ok,
                    selected = p.server.id == server.id
                )
            }

            val latency = phase(TestStep.LATENCY) { progress ->
                probes.latencyProbe().run(server.host, server.port, progress)
            }
            val download = phase(TestStep.DOWNLOAD) { progress ->
                probes.downloadProbe().run(server, progress)
            }
            val upload = phase(TestStep.UPLOAD) { progress ->
                probes.uploadProbe().run(server, progress)
            }
            val playback = phase(TestStep.PLAYBACK) { progress ->
                probes.playbackProbe().run(progress)
            }

            val outcome = tierEvaluator.evaluate(latency, download, playback)
            val health = healthAssessor.assess(outcome.networkAchieved, download, latency)
            val wifiLink = diagnostics.wifi?.let { wifiAssessor.assess(it) }

            val result = CertificationResult(
                certificationId = certificationId,
                configVersion = config.configVersion,
                startedAtMs = startedAtMs,
                timestampMs = System.currentTimeMillis(),
                achievedTier = outcome.networkAchieved,
                playbackAchievedTier = outcome.playbackAchieved,
                selectedServer = server,
                selectedServerRttMs = selection.selectedRttMs,
                serverProbes = serverProbes,
                dns = dns,
                latency = latency,
                download = download,
                upload = upload,
                playback = playback,
                tierBreakdown = outcome.breakdown,
                diagnostics = diagnostics,
                health = health,
                wifiLink = wifiLink
            )
            logSummary(result)
            CertificationPayload.logJson(result)
            send(EngineEvent.Complete(result))
        } catch (_: HaltSignal) {
            // Failed event already emitted; let the flow complete normally.
        }
    }

    private fun logSummary(r: CertificationResult) {
        Log.i(
            TAG,
            "complete: id=${r.certificationId} network=${r.achievedTier} playback=${r.playbackAchievedTier} headroom=${r.health.headroomPct}% rating=${r.health.rating} limitedBy=${r.health.limitingMetric}"
        )
        r.wifiLink?.let {
            Log.i(TAG, "wifiLink: rating=${it.rating} rssi=${it.rssiDbm}dBm rate=${it.linkSpeedMbps}/${it.maxSupportedMbps ?: "?"}Mbps")
        }
        r.tierBreakdown.forEach { e ->
            Log.i(TAG, "tier ${e.tier}: passed=${e.passed} failingReasons=${e.failingReasons}")
        }
    }

    companion object {
        private const val TAG = "CertificationEngine"
    }
}

sealed interface EngineEvent {
    data class StepProgress(
        val step: TestStep,
        val stepFrac: Float,
        val overallFrac: Float
    ) : EngineEvent

    data class Complete(val result: CertificationResult) : EngineEvent
    data class Failed(val step: TestStep, val cause: String) : EngineEvent
}
