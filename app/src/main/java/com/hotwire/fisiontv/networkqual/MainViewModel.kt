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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

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

    /**
     * The "Run cert" button's gating state, derived from the
     * combination of installed versionCode + latest fetched manifest +
     * any in-flight install. Drives both the button label flip
     * (Run cert ⇄ Update & run) and an optional banner above the button.
     */
    sealed interface UpdateGate {
        /** No manifest yet, or installed already at latest. Run cert button works normally. */
        data object Clear : UpdateGate
        /** Installed ≥ minRequired but < latest. Cert can still run; banner offers update. */
        data class Optional(val manifest: AppVersionManifest) : UpdateGate
        /** Installed < minRequired. Cert is BLOCKED until update succeeds. */
        data class RequiredBeforeCert(val manifest: AppVersionManifest) : UpdateGate
        /** Streaming the APK to disk. */
        data class Downloading(val manifest: AppVersionManifest, val fraction: Float) : UpdateGate
        /** APK on disk, install session committed; waiting on the OS / user. */
        data class Installing(val manifest: AppVersionManifest) : UpdateGate
        /** Most recent install attempt failed. UI offers Retry. */
        data class Failed(val manifest: AppVersionManifest, val reason: String) : UpdateGate
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

    /**
     * Local view of in-flight download progress. The container drives
     * install-side status via [AppContainer.installStatus]; this flow
     * only holds the bytes-streamed phase.
     */
    private val _downloadProgress = MutableStateFlow<Float?>(null)

    val updateGate: StateFlow<UpdateGate> = combine(
        container.manifest,
        _downloadProgress,
        container.installStatus
    ) { manifest, downloadFrac, installStatus ->
        deriveGate(manifest, downloadFrac, installStatus)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, UpdateGate.Clear)

    private var runJob: Job? = null
    private var updateJob: Job? = null

    init {
        // Drain anything left over from a prior session — STB killed
        // before the publish completed, network was down, etc.
        viewModelScope.launch { drainQueue(configProvider.current()) }

        // Resume cert after an update install: when the new process
        // boots and the resume flag is set, auto-start. The flag is
        // cleared regardless of outcome so we never loop.
        if (app.consumeResumeCertAfterUpdateFlag()) {
            Log.i(TAG, "resuming cert after successful update install")
            startCertification()
        }
    }

    private fun deriveGate(
        manifest: AppVersionManifest?,
        downloadFrac: Float?,
        installStatus: InstallStatus
    ): UpdateGate {
        if (manifest == null) return UpdateGate.Clear

        // Install-side states take precedence over the version comparison.
        when (installStatus) {
            is InstallStatus.AwaitingUserConfirmation -> return UpdateGate.Installing(manifest)
            is InstallStatus.Success -> return UpdateGate.Clear
            is InstallStatus.Failed -> return UpdateGate.Failed(manifest, installStatus.reason)
            is InstallStatus.Idle -> { /* fall through to version comparison */ }
        }
        if (downloadFrac != null) {
            return UpdateGate.Downloading(manifest, downloadFrac)
        }

        val installed = container.installedVersionCode
        return when {
            installed >= manifest.latestVersionCode -> UpdateGate.Clear
            installed < manifest.minRequiredVersionCode -> UpdateGate.RequiredBeforeCert(manifest)
            else -> UpdateGate.Optional(manifest)
        }
    }

    fun startCertification() {
        if (runJob?.isActive == true) return
        // Defensive backstop — the UI shouldn't surface a "Run cert" tap
        // path when an update is required, but if it does, refuse.
        if (updateGate.value is UpdateGate.RequiredBeforeCert) {
            Log.w(TAG, "cert blocked: update required (installed=${container.installedVersionCode})")
            return
        }
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

    /**
     * Drives the download → verify → install pipeline for the current
     * manifest. Persists the resume-cert flag before committing the
     * install so the next process boot auto-starts the cert.
     *
     * Called by the UI when the tech taps "Update & run" (required) or
     * "Update" (optional banner). Re-entrant calls are a no-op.
     */
    fun startUpdate() {
        if (updateJob?.isActive == true) return
        val manifest = container.manifest.value ?: run {
            Log.w(TAG, "startUpdate called with no manifest")
            return
        }
        updateJob = viewModelScope.launch {
            _downloadProgress.value = 0f
            var apkFile: File? = null
            container.updateDownloader.download(manifest)
                .onEach { progress ->
                    when (progress) {
                        is AppUpdateDownloader.Progress.Downloading ->
                            _downloadProgress.value = progress.fraction
                        is AppUpdateDownloader.Progress.Done -> apkFile = progress.apkFile
                    }
                }
                .catch { t ->
                    Log.e(TAG, "download failed: ${t.message}", t)
                    container.publishInstallStatus(InstallStatus.Failed("download: ${t.message}"))
                    _downloadProgress.value = null
                }
                .collect { /* drained for side effects above */ }

            val file = apkFile
            _downloadProgress.value = null
            if (file == null) return@launch  // catch handled the failure

            when (val verify = container.updateInstaller.verify(file, manifest)) {
                AppUpdateInstaller.VerifyOutcome.Ok -> { /* proceed */ }
                is AppUpdateInstaller.VerifyOutcome.Reject -> {
                    Log.e(TAG, "verify rejected: ${verify.reason}")
                    container.publishInstallStatus(InstallStatus.Failed("verify: ${verify.reason}"))
                    file.delete()
                    return@launch
                }
            }

            // Persist resume intent BEFORE commit — once the OS replaces
            // the app, the new process is the only place this matters.
            app.markResumeCertAfterUpdate(manifest.latestVersionCode)
            container.publishInstallStatus(InstallStatus.AwaitingUserConfirmation)

            when (val begin = container.updateInstaller.beginInstall(file, manifest)) {
                AppUpdateInstaller.BeginOutcome.Pending -> {
                    // Receiver will publish Success / Failed when the OS reports it.
                }
                is AppUpdateInstaller.BeginOutcome.Refused -> {
                    Log.e(TAG, "install refused: ${begin.reason}")
                    container.publishInstallStatus(InstallStatus.Failed("refused: ${begin.reason}"))
                    app.clearResumeCertAfterUpdate()
                }
                is AppUpdateInstaller.BeginOutcome.Failed -> {
                    Log.e(TAG, "install failed: ${begin.cause}")
                    container.publishInstallStatus(InstallStatus.Failed("session: ${begin.cause}"))
                    app.clearResumeCertAfterUpdate()
                }
            }
        }
    }

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

    /** Called from the UI's failure banner to dismiss a failed install attempt. */
    fun clearUpdateFailure() {
        container.publishInstallStatus(InstallStatus.Idle)
    }
}
