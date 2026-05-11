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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

private const val TAG = "MainViewModel"

class MainViewModel(application: Application) : AndroidViewModel(application) {

    sealed interface UiState {
        data object Idle : UiState
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

    private var runJob: Job? = null
    private var autoUpdateJob: Job? = null

    init {
        // Drain anything left over from a prior session — STB killed
        // before the publish completed, network was down, etc.
        viewModelScope.launch { drainQueue(configProvider.current()) }

        // Self-update orchestration. The manifest fetch fires in
        // AppContainer.init in parallel with cert-config; when a newer
        // version lands here we attempt the download → verify → install
        // pipeline transparently. The cert is never blocked by this:
        // - install is deferred until any running cert completes,
        // - failures log but don't surface UI,
        // - retries are bounded; after they're exhausted, we proceed
        //   on the installed version and let the cert run.
        viewModelScope.launch {
            container.manifest
                .filterNotNull()
                .distinctUntilChanged()
                .collectLatest { manifest -> maybeAutoUpdate(manifest) }
        }
    }

    fun startCertification() {
        if (runJob?.isActive == true) return
        // Pre-cert version check. Triggers a manifest refresh so that a
        // newer-version-published-while-the-app-was-idle is picked up
        // before this run. The refresh is async and rate-limited inside
        // AppContainer; the cert kicks off immediately, the auto-update
        // pipeline (if a newer manifest arrives) starts in parallel and
        // defers its install commit until this runJob.join()'s.
        container.refreshManifest()
        // Re-read config per run so a refresh that arrived between runs
        // (once the cert-config API is wired) takes effect on the next
        // click of "Run again" without an app restart.
        val config = configProvider.current()
        val engine = CertificationEngine(getApplication(), config, container.ooklaRuntime)
        runJob = viewModelScope.launch {
            _state.value = UiState.Running(TestStep.DNS, 0f, 0f)
            engine.run().collect { event ->
                when (event) {
                    is EngineEvent.StepProgress ->
                        _state.value = UiState.Running(event.step, event.stepFrac, event.overallFrac)
                    is EngineEvent.Complete -> {
                        _state.value = UiState.Done(event.result)
                        withContext(Dispatchers.IO) {
                            historyDao.insert(event.result.toEntity())
                        }
                        enqueueAndDrain(event.result, config)
                    }
                    is EngineEvent.Failed ->
                        _state.value = UiState.Failed(event.step, event.cause)
                }
            }
        }
    }

    // ── Auto-update pipeline ─────────────────────────────────────────────

    /**
     * Outcomes the per-attempt pipeline can return.
     * `Permanent` means retrying is pointless (deterministic check failed —
     * SHA mismatch, signing cert mismatch, downgrade refused). `Transient`
     * means a retry might succeed (network blip, install session quirk).
     */
    private sealed interface UpdateAttempt {
        data object Success : UpdateAttempt
        data object Permanent : UpdateAttempt
        data class Transient(val cause: String) : UpdateAttempt
    }

    private suspend fun maybeAutoUpdate(manifest: AppVersionManifest) {
        if (manifest.latestVersionCode <= container.installedVersionCode) {
            // Already current. Note that an older manifest (server rolled
            // a version back) is also a no-op — never auto-downgrade.
            Log.i(TAG, "auto-update: installed (${container.installedVersionCode}) >= latest (${manifest.latestVersionCode}); no-op")
            return
        }
        if (autoUpdateJob?.isActive == true) return
        autoUpdateJob = viewModelScope.launch { runAutoUpdate(manifest) }
    }

    private suspend fun runAutoUpdate(manifest: AppVersionManifest) {
        // Backoffs are intentionally generous — a flaky network shouldn't
        // burn cycles, and the install kill-window for the OS process
        // replacement is non-trivial. We retry only on transient
        // failures; integrity failures are permanent and return immediately.
        val backoffsMs = listOf(5_000L, 30_000L, 120_000L)
        repeat(backoffsMs.size + 1) { attempt ->
            Log.i(TAG, "auto-update v${manifest.latestVersionName}: attempt ${attempt + 1}/${backoffsMs.size + 1}")
            when (val r = attemptUpdate(manifest)) {
                UpdateAttempt.Success -> {
                    Log.i(TAG, "auto-update committed; OS will replace the process")
                    return
                }
                UpdateAttempt.Permanent -> {
                    Log.w(TAG, "auto-update gave up — permanent failure (won't retry)")
                    return
                }
                is UpdateAttempt.Transient -> {
                    val isLast = attempt >= backoffsMs.size
                    if (isLast) {
                        Log.w(TAG, "auto-update exhausted retries (${r.cause}); cert will run on installed version ${container.installedVersionCode}")
                        return
                    }
                    val backoff = backoffsMs[attempt]
                    Log.w(TAG, "auto-update transient (${r.cause}); next attempt in ${backoff}ms")
                    delay(backoff)
                }
            }
        }
    }

    private suspend fun attemptUpdate(manifest: AppVersionManifest): UpdateAttempt {
        // 1. Stream the APK to cache, hashing as we go. The downloader
        //    deletes the partial file on any failure.
        var apkFile: File? = null
        try {
            container.updateDownloader.download(manifest).collect { p ->
                if (p is AppUpdateDownloader.Progress.Done) apkFile = p.apkFile
            }
        } catch (e: AppUpdateDownloader.IntegrityException) {
            return UpdateAttempt.Permanent.also {
                Log.w(TAG, "auto-update download integrity: ${e.message}")
            }
        } catch (e: IOException) {
            return UpdateAttempt.Transient("download IO: ${e.message ?: e::class.simpleName}")
        } catch (e: Throwable) {
            return UpdateAttempt.Transient("download: ${e::class.simpleName}: ${e.message}")
        }
        val file = apkFile ?: return UpdateAttempt.Transient("download completed without Done frame")

        // 2. Verify the downloaded APK against the manifest + pinned signing
        //    cert. Verification is deterministic — failure is permanent
        //    because retrying with the same bytes will fail the same way.
        when (val v = container.updateInstaller.verify(file, manifest)) {
            AppUpdateInstaller.VerifyOutcome.Ok -> { /* proceed */ }
            is AppUpdateInstaller.VerifyOutcome.Reject -> {
                Log.w(TAG, "auto-update verify rejected: ${v.reason}")
                file.delete()
                return UpdateAttempt.Permanent
            }
        }

        // 3. Wait for any in-flight cert to complete before committing the
        //    install — the install replaces the app process and would
        //    interrupt the cert mid-run. After this join() returns, the
        //    cert is done (or there was no cert running) and the install
        //    can safely commit. This is the "cert always wins" invariant.
        runJob?.let {
            Log.i(TAG, "auto-update: waiting for in-flight cert to complete before install")
            it.join()
        }

        // 4. Commit the install session. On a sideloaded build the OS
        //    immediately fires PENDING_USER_ACTION to UpdateInstallReceiver,
        //    which launches the system "Install update?" dialog. On a
        //    platform-signed (system-app) build the install runs silently
        //    and the OS replaces the app process directly.
        return when (val b = container.updateInstaller.beginInstall(file, manifest)) {
            AppUpdateInstaller.BeginOutcome.Pending -> UpdateAttempt.Success
            is AppUpdateInstaller.BeginOutcome.Refused -> {
                Log.w(TAG, "auto-update install refused: ${b.reason}")
                UpdateAttempt.Permanent
            }
            is AppUpdateInstaller.BeginOutcome.Failed -> {
                UpdateAttempt.Transient("install session: ${b.cause}")
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
