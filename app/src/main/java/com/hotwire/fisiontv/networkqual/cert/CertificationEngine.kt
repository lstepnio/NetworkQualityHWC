package com.hotwire.fisiontv.networkqual.cert

import android.content.Context
import android.util.Log
import com.hotwire.fisiontv.networkqual.cert.probes.DefaultProbeFactory
import com.hotwire.fisiontv.networkqual.cert.probes.ProbeFactory
import com.hotwire.fisiontv.networkqual.cert.probes.ookla.AndroidPerformanceLocks
import com.hotwire.fisiontv.networkqual.cert.probes.ookla.OoklaRuntime
import com.hotwire.fisiontv.networkqual.cert.probes.ookla.OoklaSpeedtestPhase
import com.hotwire.fisiontv.networkqual.cert.probes.ookla.OoklaSpeedtestRunner
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
/**
 * Function the engine calls to perform the Ookla speedtest phase. The
 * function is the speedtest — `OoklaSpeedtestPhase.run` in production,
 * a pre-canned fake in tests. Lets the engine stay constructable with
 * pure Kotlin in unit tests (no Android Context needed).
 */
typealias OoklaSource = suspend (onProgress: (Float) -> Unit) -> com.hotwire.fisiontv.networkqual.cert.probes.ookla.OoklaSpeedtestOutcome

class CertificationEngine(
    private val config: RuntimeConfig,
    private val probes: ProbeFactory,
    private val ookla: OoklaSource,
    private val collectDiagnostics: () -> NetworkDiagnostics,
    private val tierEvaluator: TierEvaluator = TierEvaluator(config.tiers),
    private val healthAssessor: HealthAssessor = HealthAssessor(config.tiers, config.healthAssessment),
    private val wifiAssessor: WifiLinkQualityAssessor = WifiLinkQualityAssessor(config.wifiLinkQuality),
    /**
     * Optional. When provided, the engine sandwiches the Ookla phase
     * between two [EnvironmentSnapshot]s (start + end) so the payload
     * records the thermal + CPU + Wi-Fi state across the measurement
     * window. Null in tests that don't need this signal.
     */
    private val environmentCollector: EnvironmentSnapshotCollector? = null
) {

    /**
     * Production constructor. Resolves probes and diagnostics from the
     * Android context and reuses the process-wide OoklaRuntime so the
     * CA bundle is extracted once at app start rather than per-run.
     * Tests should use the primary constructor with fakes instead.
     */
    constructor(
        context: Context,
        config: RuntimeConfig,
        ooklaRuntime: OoklaRuntime = OoklaRuntime(context)
    ) : this(
        config = config,
        probes = DefaultProbeFactory(context, config),
        ookla = { onProgress ->
            OoklaSpeedtestPhase(
                runtime = ooklaRuntime,
                primaryConfigUrl = config.ooklaConfigUrl,
                fallbackConfigUrl = config.ooklaConfigUrlFallback,
                perfLocks = AndroidPerformanceLocks(context)
            ).run(onProgress)
        },
        collectDiagnostics = { NetworkDiagnosticsCollector.collect(context) },
        environmentCollector = EnvironmentSnapshotCollector(context)
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

            // Pre-flight: if there's no validated network, fail fast with
            // a clear message instead of plowing through 60s of DNS +
            // Ookla timeouts. validated == false means the OS has the
            // interface up but hasn't confirmed internet connectivity.
            if (diagnostics.network.transport.name == "OTHER" || !diagnostics.network.validated) {
                Log.w(TAG, "pre-flight failed: transport=${diagnostics.network.transport} validated=${diagnostics.network.validated}")
                send(EngineEvent.Failed(TestStep.DNS, "No working internet connection detected. Check the STB's network and try again."))
                return@channelFlow
            }

            val dns = phase(TestStep.DNS) { progress -> probes.dnsProbe().run(progress) }

            // Sandwich the speedtest phase between two environment
            // snapshots. Cheap (~ms; sysfs reads + system-service calls)
            // and lets support post-hoc correlate throughput variance with
            // thermal status, CPU frequency, and Wi-Fi link drift.
            val envStart = environmentCollector?.snapshot()

            // One Ookla execution covers server selection + ping + download
            // + upload. Sub-progress within the run is mapped onto this
            // phase's overall slice (see OoklaSpeedtestPhase).
            val ookla = phase(TestStep.SPEEDTEST) { progress ->
                this@CertificationEngine.ookla(progress)
            }
            val envEnd = environmentCollector?.snapshot()
            val server = ookla.server
            val serverProbes = listOf(
                ServerProbe(
                    id = server.id,
                    name = server.name,
                    host = server.host,
                    rttMs = ookla.latency.medianMs,
                    ok = true,
                    selected = true
                )
            )

            val playback = phase(TestStep.PLAYBACK) { progress ->
                probes.playbackProbe().run(progress)
            }

            val outcome = tierEvaluator.evaluate(ookla.latency, ookla.download, playback)
            val health = healthAssessor.assess(outcome.networkAchieved, ookla.download, ookla.latency)
            val wifiLink = diagnostics.wifi?.let { wifiAssessor.assess(it) }

            val result = CertificationResult(
                certificationId = certificationId,
                configVersion = config.configVersion,
                startedAtMs = startedAtMs,
                timestampMs = System.currentTimeMillis(),
                achievedTier = outcome.networkAchieved,
                playbackAchievedTier = outcome.playbackAchieved,
                selectedServer = server,
                selectedServerRttMs = ookla.latency.medianMs,
                serverProbes = serverProbes,
                dns = dns,
                latency = ookla.latency,
                download = ookla.download,
                upload = ookla.upload,
                playback = playback,
                tierBreakdown = outcome.breakdown,
                diagnostics = diagnostics,
                health = health,
                wifiLink = wifiLink,
                environmentAtSpeedtestStart = envStart,
                environmentAtSpeedtestEnd = envEnd
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
