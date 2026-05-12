package com.hotwire.fisiontv.networkqual

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hotwire.fisiontv.networkqual.cert.CertificationEngine
import com.hotwire.fisiontv.networkqual.cert.CertificationResult
import com.hotwire.fisiontv.networkqual.cert.EngineEvent
import com.hotwire.fisiontv.networkqual.cert.TestStep
import com.hotwire.fisiontv.networkqual.config.RuntimeConfig
import com.hotwire.fisiontv.networkqual.data.HistoryEntity
import com.hotwire.fisiontv.networkqual.data.toEntity
import com.hotwire.fisiontv.networkqual.update.AppUpdateDownloader
import com.hotwire.fisiontv.networkqual.update.AppUpdateInstaller
import com.hotwire.fisiontv.networkqual.update.AppVersionManifest
import com.hotwire.fisiontv.networkqual.update.InstallStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException

private const val TAG = "MainViewModel"

class MainViewModel(application: Application) : AndroidViewModel(application) {

    sealed interface UiState {
        data object Idle : UiState

        /**
         * Pre-cert / update phase. Single state with optional progress —
         * covers "checking for updates", "downloading update", "installing
         * update", and the brief "retrying…" between attempts. Mostly
         * invisible to the tech in the no-update case (it transitions
         * straight through to Running within a few hundred ms).
         */
        data class Preparing(
            val title: String,
            val subtitle: String? = null,
            val frac: Float? = null
        ) : UiState

        data class Running(
            val currentStep: TestStep,
            val stepFrac: Float,
            val overallFrac: Float
        ) : UiState
        data class Done(val result: CertificationResult) : UiState
        data class Failed(val step: TestStep, val message: String) : UiState
    }

    private val app = application as FisionApp
    private val container = app.container
    private val historyDao = container.database.historyDao()
    private val publishQueue = container.publishQueue
    private val configProvider = container.configProvider

    private val _state = MutableStateFlow<UiState>(UiState.Idle)
    val state: StateFlow<UiState> = _state.asStateFlow()

    val recentHistory: StateFlow<List<HistoryEntity>> = historyDao.observeRecent()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Exposed for the always-visible version footer in [com.hotwire.fisiontv.networkqual.ui.AppRoot]. */
    val installedVersionName: String = container.installedVersionName

    /**
     * Live config version. Footer displays this next to [installedVersionName]
     * so a support call can sanity-check that the STB picked up the most
     * recent cert-config (vs. still on bundled defaults after a parse
     * failure). Updates synchronously when [configProvider.apply] swaps
     * the cached config.
     */
    val configVersion: StateFlow<String> = configProvider.flow
        .map { it.configVersion }
        .stateIn(viewModelScope, SharingStarted.Eagerly, configProvider.current().configVersion)

    private var runJob: Job? = null

    init {
        // Drain anything left over from a prior session — STB killed
        // before the publish completed, network was down, etc.
        viewModelScope.launch { drainQueue(configProvider.current()) }

        // If the previous process committed an "update then cert" flow
        // and the OS replaced us, the new boot fires the cert
        // automatically so the tech doesn't have to tap twice.
        if (app.consumeResumeCertAfterUpdateFlag()) {
            Log.i(TAG, "resuming cert after successful update install")
            startCertification()
        }
    }

    /**
     * Tech-facing entry point for "I want a cert". Always tries to update
     * before running so the cert reflects the latest tier thresholds,
     * server list, etc. If the update can't complete (network failure,
     * integrity rejection, etc.) the cert runs on the installed version
     * anyway — "ultimately still run the certification and post the
     * results" beats "blocked in the field".
     */
    fun startCertification() {
        if (runJob?.isActive == true) return
        runJob = viewModelScope.launch {
            // Phase 1: refresh manifest, apply update if newer.
            if (!preCertUpdate()) {
                // Install was committed — the OS will replace this
                // process imminently. The resume flag is set; the new
                // process's init will see it and re-fire startCertification
                // on the new version. We stay in Preparing.Installing
                // until the OS kills us.
                awaitCancellation()
            }
            // Phase 2: run the actual cert.
            runCertEngine()
        }
    }

    /**
     * Refresh the manifest, then — if a newer version exists — try to
     * download + verify + install it before the cert.
     *
     * Returns `true` when the cert should proceed in this process
     * (no update needed, or update failed and we're falling through).
     * Returns `false` when the install committed — the caller should
     * NOT start the cert; the OS will replace the process and the new
     * boot's resume flag will re-trigger startCertification on the new
     * version.
     */
    private suspend fun preCertUpdate(): Boolean {
        // Intentionally NOT setting a "Checking for updates" Preparing
        // state here. With ETag the manifest fetch is a sub-200 ms 304
        // in the common case — long enough to flash visibly but far
        // too short to read, which looked like a UI glitch in the lab.
        // The Start screen stays on screen until we either know an
        // update is needed (transition to Downloading below) or we're
        // proceeding straight to the cert (runCertEngine sets Running).
        // The button-tap ripple covers the perceived gap.
        //
        // FORCE refresh (bypass the 30s rate-limit). The cost is one 304
        // round-trip; the cost of skipping is running the cert on an
        // outdated client because we read the stale cached manifest.
        val refreshJob = container.refreshManifest(force = true)
        // Wait for the in-flight fetch to actually land before reading
        // the manifest value. Without this join() the StateFlow's first()
        // returns whatever was cached at boot — a classic read-then-fetch
        // race that lets a fresh "1002 available" verdict be missed
        // because the cache still says "1001".
        if (refreshJob != null) {
            withTimeoutOrNull(3_000L) { refreshJob.join() }
        }
        val manifest = container.manifest.value
        if (manifest == null) {
            Log.w(TAG, "pre-cert: no manifest available; proceeding with installed version")
            return true
        }
        if (manifest.latestVersionCode <= container.installedVersionCode) {
            Log.i(TAG, "pre-cert: installed (${container.installedVersionCode}) >= latest (${manifest.latestVersionCode}); no update needed")
            return true
        }

        Log.i(TAG, "pre-cert: update available v${manifest.latestVersionName} (code ${manifest.latestVersionCode}); applying before cert")
        return runPreCertUpdate(manifest)
    }

    private suspend fun runPreCertUpdate(manifest: AppVersionManifest): Boolean {
        val maxAttempts = 2
        val backoffMs = 5_000L

        repeat(maxAttempts) { attempt ->
            Log.i(TAG, "pre-cert update v${manifest.latestVersionName}: attempt ${attempt + 1}/$maxAttempts")
            when (val r = attemptUpdate(manifest)) {
                UpdateAttempt.Success -> {
                    // Install was committed. Set the resume flag so the
                    // post-swap process auto-fires startCertification, then
                    // park the UI in "Installing…" — either the OS replaces
                    // us (success), or the user cancels the system dialog
                    // (we fall through and run the cert on this version).
                    app.markResumeCertAfterUpdate(manifest.latestVersionCode)
                    _state.value = UiState.Preparing(
                        title = "Installing update v${manifest.latestVersionName}",
                        subtitle = "The app will restart and run the certification automatically."
                    )
                    val terminal = container.installStatus.first {
                        it is InstallStatus.Success || it is InstallStatus.Failed
                    }
                    return when (terminal) {
                        is InstallStatus.Success -> false  // caller awaits OS swap
                        is InstallStatus.Failed -> {
                            Log.w(TAG, "pre-cert install failed post-commit: ${terminal.reason}")
                            app.clearResumeCertAfterUpdate()
                            true  // fall through to cert
                        }
                        else -> true
                    }
                }
                UpdateAttempt.Permanent -> {
                    Log.w(TAG, "pre-cert update permanent failure; proceeding with cert on installed version")
                    return true
                }
                is UpdateAttempt.Transient -> {
                    val isLast = attempt >= maxAttempts - 1
                    if (isLast) {
                        Log.w(TAG, "pre-cert update exhausted retries (${r.cause}); proceeding with cert")
                        return true
                    }
                    Log.w(TAG, "pre-cert update transient (${r.cause}); retrying in ${backoffMs}ms")
                    _state.value = UiState.Preparing(
                        title = "Updating to v${manifest.latestVersionName}",
                        subtitle = "Retrying — ${r.cause}"
                    )
                    delay(backoffMs)
                }
            }
        }
        return true
    }

    private suspend fun attemptUpdate(manifest: AppVersionManifest): UpdateAttempt {
        // 1. Download with live progress reporting into the UI.
        _state.value = UiState.Preparing(
            title = "Updating to v${manifest.latestVersionName}",
            subtitle = "Downloading…",
            frac = 0f
        )
        var apkFile: File? = null
        try {
            container.updateDownloader.download(manifest).collect { p ->
                when (p) {
                    is AppUpdateDownloader.Progress.Downloading ->
                        _state.value = UiState.Preparing(
                            title = "Updating to v${manifest.latestVersionName}",
                            subtitle = "Downloading…",
                            frac = p.fraction
                        )
                    is AppUpdateDownloader.Progress.Done -> apkFile = p.apkFile
                }
            }
        } catch (e: AppUpdateDownloader.IntegrityException) {
            Log.w(TAG, "pre-cert download integrity: ${e.message}")
            return UpdateAttempt.Permanent
        } catch (e: IOException) {
            return UpdateAttempt.Transient("download IO: ${e.message ?: e::class.simpleName}")
        } catch (e: Throwable) {
            return UpdateAttempt.Transient("download: ${e::class.simpleName}: ${e.message}")
        }
        val file = apkFile ?: return UpdateAttempt.Transient("download completed without Done frame")

        // 2. Verify (deterministic — permanent on rejection).
        when (val v = container.updateInstaller.verify(file, manifest)) {
            AppUpdateInstaller.VerifyOutcome.Ok -> { /* proceed */ }
            is AppUpdateInstaller.VerifyOutcome.Reject -> {
                Log.w(TAG, "pre-cert verify rejected: ${v.reason}")
                file.delete()
                return UpdateAttempt.Permanent
            }
        }

        // 3. Commit install. Pending → caller will park in Installing state
        //    and wait for the receiver's Success/Failed.
        return when (val b = container.updateInstaller.beginInstall(file, manifest)) {
            AppUpdateInstaller.BeginOutcome.Pending -> UpdateAttempt.Success
            is AppUpdateInstaller.BeginOutcome.Refused -> {
                Log.w(TAG, "pre-cert install refused: ${b.reason}")
                UpdateAttempt.Permanent
            }
            is AppUpdateInstaller.BeginOutcome.Failed -> {
                UpdateAttempt.Transient("install session: ${b.cause}")
            }
        }
    }

    private sealed interface UpdateAttempt {
        data object Success : UpdateAttempt
        data object Permanent : UpdateAttempt
        data class Transient(val cause: String) : UpdateAttempt
    }

    private suspend fun runCertEngine() {
        _state.value = UiState.Running(TestStep.DNS, 0f, 0f)
        // Re-read config per run so a refresh that arrived between runs
        // (once the cert-config API is wired) takes effect on the next
        // click of "Run again" without an app restart.
        val config = configProvider.current()
        val engine = CertificationEngine(getApplication(), config, container.ooklaRuntime)
        engine.run().collect { event ->
            when (event) {
                is EngineEvent.StepProgress ->
                    _state.value = UiState.Running(event.step, event.stepFrac, event.overallFrac)
                is EngineEvent.Complete -> {
                    // Persist BEFORE flipping UI to Done. If the local
                    // insert fails (disk full, DB corrupt, schema drift
                    // post-migration) we'd otherwise show a tech
                    // "passed" on a result that was just lost from both
                    // history AND the publish queue. Fail loud instead.
                    try {
                        withContext(Dispatchers.IO) {
                            historyDao.insert(event.result.toEntity())
                        }
                    } catch (t: Throwable) {
                        Log.e(
                            TAG,
                            "history insert failed for ${event.result.certificationId}: ${t::class.simpleName}: ${t.message}",
                            t
                        )
                        // No TestStep enum value cleanly maps to "post-engine
                        // persistence failed"; tag as PLAYBACK (the last phase)
                        // so the Failed screen at least anchors to "the cert
                        // finished but storage broke" mentally.
                        _state.value = UiState.Failed(
                            TestStep.PLAYBACK,
                            "Could not record result locally: ${t::class.simpleName}: ${t.message ?: "unknown error"}"
                        )
                        return@collect
                    }
                    // Enqueue failure is non-fatal — the result is already
                    // in HistoryDao, so on the next launch PublishQueue
                    // won't see it but the local row is preserved. Log
                    // loudly so the gap is visible.
                    try {
                        enqueueAndDrain(event.result, config)
                    } catch (t: Throwable) {
                        Log.e(
                            TAG,
                            "enqueue failed for ${event.result.certificationId}: ${t::class.simpleName}: ${t.message}",
                            t
                        )
                    }
                    _state.value = UiState.Done(event.result)
                }
                is EngineEvent.Failed ->
                    _state.value = UiState.Failed(event.step, event.cause)
            }
        }
    }

    // ── Publish queue plumbing ──────────────────────────────────────────

    private suspend fun enqueueAndDrain(result: CertificationResult, config: RuntimeConfig) {
        val pub = config.resultsPublishing
        if (!pub.enabled || pub.endpoint.isNullOrBlank()) return
        publishQueue.enqueue(result, pub.endpoint)
        drainQueue(config)
    }

    private suspend fun drainQueue(config: RuntimeConfig) {
        val pub = config.resultsPublishing
        if (!pub.enabled || pub.endpoint.isNullOrBlank()) return
        try {
            val drained = publishQueue.drain(pub.endpoint)
            if (drained > 0) Log.i(TAG, "drained $drained pending result(s)")
        } catch (t: Throwable) {
            Log.w(TAG, "drain failed: ${t::class.simpleName}: ${t.message}")
        }
    }

    fun reset() {
        runJob?.cancel()
        runJob = null
        _state.value = UiState.Idle
    }
}
