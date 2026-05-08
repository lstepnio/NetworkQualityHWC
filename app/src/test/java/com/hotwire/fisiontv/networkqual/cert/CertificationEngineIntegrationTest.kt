package com.hotwire.fisiontv.networkqual.cert

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.hotwire.fisiontv.networkqual.cert.probes.LatencyResult
import com.hotwire.fisiontv.networkqual.cert.probes.ThroughputResult
import com.hotwire.fisiontv.networkqual.cert.probes.ookla.OoklaSpeedtestOutcome
import com.hotwire.fisiontv.networkqual.config.OoklaServer
import com.hotwire.fisiontv.networkqual.config.RuntimeConfigDefaults
import com.hotwire.fisiontv.networkqual.diagnostics.Transport
import com.hotwire.fisiontv.networkqual.fixtures.FakeDiagnostics
import com.hotwire.fisiontv.networkqual.fixtures.FakeProbeFactory
import com.hotwire.fisiontv.networkqual.fixtures.Fixtures
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * End-to-end orchestration test. Wires fake probes + a fake Ookla
 * source to the engine, runs a full certification, and asserts on the
 * events emitted. Locks in the sequence of phases, the final payload
 * shape, and the failure path so future probe-interface changes can't
 * silently break orchestration.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CertificationEngineIntegrationTest {

    private val config = RuntimeConfigDefaults.bundled

    private fun ooklaOutcome(
        latency: LatencyResult = LatencyResult(
            samples = listOf(30L), medianMs = 30, p95Ms = 35,
            jitterMs = 3, attempted = 5, lossPct = 0
        ),
        download: ThroughputResult = ThroughputResult(steadyMbps = 200.0, peakMbps = 200.0, durationSec = 10),
        upload: ThroughputResult = ThroughputResult(steadyMbps = 100.0, peakMbps = 100.0, durationSec = 5)
    ): OoklaSpeedtestOutcome = OoklaSpeedtestOutcome(
        server = OoklaServer(id = "ookla-fake", name = "Fake Test", host = "fake.example.com"),
        serverIp = "1.2.3.4",
        isp = "Test ISP",
        publicIp = "203.0.113.1",
        latency = latency,
        download = download,
        upload = upload,
        packetLossPct = 0.0
    )

    @Test fun `happy path runs every phase and emits Complete with the result`() = runTest {
        val engine = CertificationEngine(
            config = config,
            probes = FakeProbeFactory(),
            ookla = { onProgress -> onProgress(1f); ooklaOutcome() },
            collectDiagnostics = { FakeDiagnostics.build(Transport.WIFI) }
        )

        engine.run().test {
            val events = mutableListOf<EngineEvent>()
            while (true) {
                val ev = awaitItem()
                events += ev
                if (ev is EngineEvent.Complete || ev is EngineEvent.Failed) break
            }
            awaitComplete()

            val complete = events.filterIsInstance<EngineEvent.Complete>().single()
            val r = complete.result

            // Network tier reflects throughput/latency from Ookla source:
            // 200 Mbps DL, 30 ms latency, 3 ms jitter clears 4K HDR.
            // Playback tier is constrained by the 1080p canned playback
            // result, so it caps at HD.
            assertThat(r.achievedTier).isEqualTo(Tier.UHD_4K_HDR)
            assertThat(r.playbackAchievedTier).isEqualTo(Tier.HD)
            assertThat(r.serverProbes).hasSize(1)
            assertThat(r.serverProbes.single().selected).isTrue()
            assertThat(r.dns.failureCount).isEqualTo(0)
            assertThat(r.health.rating).isAnyOf(HealthRating.STRONG, HealthRating.EXCELLENT, HealthRating.GOOD)
            assertThat(r.wifiLink).isNotNull()
            assertThat(r.diagnostics.network.transport).isEqualTo(Transport.WIFI)
        }
    }

    @Test fun `Ookla failure surfaces as Failed at SPEEDTEST step`() = runTest {
        val engine = CertificationEngine(
            config = config,
            probes = FakeProbeFactory(),
            ookla = { _ -> error("simulated ookla failure") },
            collectDiagnostics = { FakeDiagnostics.build(Transport.WIFI) }
        )

        engine.run().test {
            var failedStep: TestStep? = null
            var sawComplete = false
            while (true) {
                val ev = awaitItem()
                when (ev) {
                    is EngineEvent.Failed -> { failedStep = ev.step; break }
                    is EngineEvent.Complete -> { sawComplete = true; break }
                    is EngineEvent.StepProgress -> Unit
                }
            }
            awaitComplete()
            assertThat(sawComplete).isFalse()
            assertThat(failedStep).isEqualTo(TestStep.SPEEDTEST)
        }
    }

    @Test fun `DNS probe failure surfaces as Failed at DNS step`() = runTest {
        val engine = CertificationEngine(
            config = config,
            probes = FakeProbeFactory(throwOn = "dns"),
            ookla = { _ -> ooklaOutcome() },
            collectDiagnostics = { FakeDiagnostics.build(Transport.WIFI) }
        )

        engine.run().test {
            var failedStep: TestStep? = null
            while (true) {
                val ev = awaitItem()
                if (ev is EngineEvent.Failed) { failedStep = ev.step; break }
                if (ev is EngineEvent.Complete) error("unexpected complete")
            }
            awaitComplete()
            assertThat(failedStep).isEqualTo(TestStep.DNS)
        }
    }

    @Test fun `ethernet transport produces null wifiLink and no dhcp`() = runTest {
        val engine = CertificationEngine(
            config = config,
            probes = FakeProbeFactory(),
            ookla = { _ -> ooklaOutcome() },
            collectDiagnostics = { FakeDiagnostics.build(Transport.ETHERNET) }
        )

        engine.run().test {
            var result: CertificationResult? = null
            while (true) {
                val ev = awaitItem()
                if (ev is EngineEvent.Complete) { result = ev.result; break }
                if (ev is EngineEvent.Failed) error("unexpected failure: ${ev.cause}")
            }
            awaitComplete()
            assertThat(result!!.wifiLink).isNull()
            assertThat(result.diagnostics.wifi).isNull()
            assertThat(result.diagnostics.network.transport).isEqualTo(Transport.ETHERNET)
            assertThat(result.diagnostics.network.dhcp).isNull()
        }
    }

    @Test fun `result carries config version round-tripped from RuntimeConfig`() = runTest {
        val engine = CertificationEngine(
            config = config,
            probes = FakeProbeFactory(),
            ookla = { _ -> ooklaOutcome() },
            collectDiagnostics = { FakeDiagnostics.build() }
        )

        engine.run().test {
            var result: CertificationResult? = null
            while (true) {
                val ev = awaitItem()
                if (ev is EngineEvent.Complete) { result = ev.result; break }
                if (ev is EngineEvent.Failed) error("unexpected failure: ${ev.cause}")
            }
            awaitComplete()
            assertThat(result!!.configVersion).isEqualTo(config.configVersion)
        }
    }

    @Test fun `not-certified path produces achievedTier NONE and FAILED health`() = runTest {
        val engine = CertificationEngine(
            config = config,
            probes = FakeProbeFactory(playback = Fixtures.playback(peakHeight = 240, rebuffers = 5)),
            ookla = { _ ->
                ooklaOutcome(
                    // Below SD floor on every metric.
                    latency = LatencyResult(
                        samples = listOf(900L), medianMs = 900, p95Ms = 950,
                        jitterMs = 200, attempted = 5, lossPct = 0
                    ),
                    download = ThroughputResult(steadyMbps = 0.5, peakMbps = 1.0, durationSec = 10),
                    upload = ThroughputResult(steadyMbps = 0.3, peakMbps = 0.5, durationSec = 5)
                )
            },
            collectDiagnostics = { FakeDiagnostics.build() }
        )

        engine.run().test {
            var result: CertificationResult? = null
            while (true) {
                val ev = awaitItem()
                if (ev is EngineEvent.Complete) { result = ev.result; break }
                if (ev is EngineEvent.Failed) error("unexpected: ${ev.cause}")
            }
            awaitComplete()
            assertThat(result!!.achievedTier).isEqualTo(Tier.NONE)
            assertThat(result.health.rating).isEqualTo(HealthRating.FAILED)
            assertThat(result.health.headroomPct).isEqualTo(0)
        }
    }
}
