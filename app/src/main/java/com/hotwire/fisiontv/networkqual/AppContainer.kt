package com.hotwire.fisiontv.networkqual

import android.content.Context
import android.util.Log
import com.hotwire.fisiontv.networkqual.cert.probes.ookla.OoklaRuntime
import com.hotwire.fisiontv.networkqual.config.RuntimeConfigProvider
import com.hotwire.fisiontv.networkqual.data.AppDatabase
import com.hotwire.fisiontv.networkqual.diagnostics.DeviceIdentityCollector
import com.hotwire.fisiontv.networkqual.publish.CertConfigClient
import com.hotwire.fisiontv.networkqual.publish.FetchOutcome
import com.hotwire.fisiontv.networkqual.publish.NoAuthProvider
import com.hotwire.fisiontv.networkqual.publish.OkHttpCertConfigClient
import com.hotwire.fisiontv.networkqual.publish.PublishQueue
import com.hotwire.fisiontv.networkqual.update.AppUpdateClient
import com.hotwire.fisiontv.networkqual.update.AppUpdateDownloader
import com.hotwire.fisiontv.networkqual.update.AppUpdateFetchOutcome
import com.hotwire.fisiontv.networkqual.update.AppUpdateInstaller
import com.hotwire.fisiontv.networkqual.update.AppVersionManifest
import com.hotwire.fisiontv.networkqual.update.InstallStatus
import com.hotwire.fisiontv.networkqual.update.OkHttpAppUpdateClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Process-wide service container.
 *
 * One instance per Application — created in [FisionApp.onCreate], read
 * by [MainViewModel] (and any future ViewModels). Holds the long-lived
 * singletons in a single, audit-able place so construction order is
 * explicit and tests can substitute alternatives.
 *
 * No DI framework on purpose — the dependency graph is small enough that
 * the readability cost of Hilt/Koin would exceed the benefit. If the
 * graph grows past ~10 services, revisit.
 */
class AppContainer(context: Context) {
    private val applicationContext: Context = context.applicationContext

    val configProvider: RuntimeConfigProvider = RuntimeConfigProvider()

    val database: AppDatabase = AppDatabase.get(applicationContext)

    val publishQueue: PublishQueue = PublishQueue(database.pendingPublishDao())

    /**
     * Single OoklaRuntime instance for the process. Created lazily and
     * extracts the CA bundle off the main thread on first access — so
     * the FisionApp.onCreate path doesn't pay the I/O cost. The runtime
     * itself is cheap to hold past app lifetime; it just resolves paths.
     */
    val ooklaRuntime: OoklaRuntime by lazy { OoklaRuntime(applicationContext) }

    private val certConfigClient: CertConfigClient = OkHttpCertConfigClient(
        endpoint = BuildConfig.CERT_CONFIG_URL,
        authProvider = NoAuthProvider,
        deviceId = DeviceIdentityCollector.deviceId(applicationContext),
        appVersion = BuildConfig.VERSION_NAME
    )

    // ── App self-update plumbing ────────────────────────────────────────
    private val updateClient: AppUpdateClient = OkHttpAppUpdateClient(
        endpoint = BuildConfig.APP_UPDATE_URL,
        authProvider = NoAuthProvider,
        deviceId = DeviceIdentityCollector.deviceId(applicationContext),
        appVersion = BuildConfig.VERSION_NAME,
        appVersionCode = BuildConfig.VERSION_CODE
    )

    val updateDownloader: AppUpdateDownloader = AppUpdateDownloader(applicationContext.cacheDir)
    val updateInstaller: AppUpdateInstaller = AppUpdateInstaller(applicationContext)

    /**
     * Latest fetched manifest. `null` means "we haven't successfully
     * fetched yet" — the UI treats that as "no update info, behave as
     * before". Updated atomically by the refresh coroutine in [init].
     */
    private val _manifest = MutableStateFlow<AppVersionManifest?>(null)
    val manifest: StateFlow<AppVersionManifest?> = _manifest.asStateFlow()

    /**
     * Install lifecycle state. Driven by [com.hotwire.fisiontv.networkqual.update.UpdateInstallReceiver]
     * via [publishInstallStatus]; read by [MainViewModel] to render
     * progress / success / failure UI.
     */
    private val _installStatus = MutableStateFlow<InstallStatus>(InstallStatus.Idle)
    val installStatus: StateFlow<InstallStatus> = _installStatus.asStateFlow()

    fun publishInstallStatus(status: InstallStatus) {
        Log.i(TAG, "install status → $status")
        _installStatus.value = status
    }

    val installedVersionCode: Int = BuildConfig.VERSION_CODE
    val installedVersionName: String = BuildConfig.VERSION_NAME
    /**
     * Whether this running process will install silently. Determined at
     * runtime from the actual INSTALL_PACKAGES grant — true for a
     * platform-signed system-app build, false for a sideloaded build of
     * the same APK. Surfaced here so the UI can shape its "Installing…"
     * copy (the system dialog hint only applies to the non-silent path).
     */
    val silentInstallSupported: Boolean
        get() = updateInstaller.canInstallSilently()

    private val refreshScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var lastManifestFetchAtMs: Long = 0L

    /**
     * Trigger an app-update manifest refresh.
     *
     * Returns the [Job] that completes when the fetch finishes (or `null`
     * when the call was rate-limited and skipped). Callers that need the
     * latest manifest before acting — notably the pre-cert update path —
     * must `join()` the returned Job, otherwise they race against the
     * still-in-flight fetch and read the stale cached value.
     *
     * Rate-limited to one network fetch per [MIN_MANIFEST_FETCH_INTERVAL_MS]
     * — wiring this into multiple lifecycle hooks (Activity.onResume,
     * MainViewModel.startCertification, future places) doesn't hammer the
     * API. Pass [force] = true to bypass the rate-limit — the pre-cert
     * path does this because the cost of a 304 is negligible and the
     * cost of running a cert against a stale "no update available"
     * decision is much higher.
     *
     * Why this exists: the launch-time fetch in [init] only fires once
     * per process. On long-running app sessions (or when an STB sits
     * idle on the start screen while a new version is published), we'd
     * miss the new manifest entirely.
     */
    fun refreshManifest(force: Boolean = false): Job? {
        val now = System.currentTimeMillis()
        val sinceLast = now - lastManifestFetchAtMs
        if (!force && sinceLast < MIN_MANIFEST_FETCH_INTERVAL_MS) {
            Log.i(TAG, "app-update: skip refresh (${sinceLast / 1000}s since last fetch)")
            return null
        }
        lastManifestFetchAtMs = now
        return refreshScope.launch {
            when (val outcome = updateClient.fetch()) {
                is AppUpdateFetchOutcome.Updated -> {
                    Log.i(TAG, "app-update: new manifest (v${outcome.manifest.latestVersionName}, code=${outcome.manifest.latestVersionCode})")
                    _manifest.value = outcome.manifest
                }
                is AppUpdateFetchOutcome.NotModified ->
                    Log.i(TAG, "app-update 304: keeping cached manifest")
                is AppUpdateFetchOutcome.Error ->
                    Log.w(TAG, "app-update fetch failed: ${outcome.cause}")
            }
        }
    }

    init {
        // Kick off a config refresh on launch. The result lands via
        // configProvider.apply() — typically before the user clicks "Run",
        // so the next certification uses the server-side servers + tier
        // thresholds. Any failure is logged and bundled defaults stay.
        refreshScope.launch {
            when (val outcome = certConfigClient.fetch()) {
                is FetchOutcome.Updated -> configProvider.apply(outcome.config)
                is FetchOutcome.NotModified -> Log.i(TAG, "cert-config 304: keeping cached")
                is FetchOutcome.Error -> Log.w(TAG, "cert-config fetch failed, using bundled: ${outcome.cause}")
            }
        }
        // Eagerly extract the Ookla CA bundle off the main thread. By
        // the time the user clicks "Run certification" the bundle is
        // already on disk and the first speedtest doesn't pay the
        // ~10 ms extraction cost.
        refreshScope.launch { ooklaRuntime.binaryPath /* triggers extraction via lazy */ }

        // Initial manifest fetch. Subsequent fetches are triggered by
        // MainActivity.onResume (tech returns to the app) and by
        // MainViewModel.startCertification (right before each run) — both
        // funnel through refreshManifest() which rate-limits to avoid
        // burning the API.
        refreshManifest()
    }

    companion object {
        private const val TAG = "AppContainer"
        /**
         * Minimum gap between manifest fetches. Each fetch is cheap (ETag
         * → 304 in the common case) but we don't need to ask multiple
         * times per minute. 30 s is generous — picks up a freshly-published
         * version on the next tech action, doesn't spam during routine
         * Activity lifecycle churn (screen-off → screen-on burst).
         */
        private const val MIN_MANIFEST_FETCH_INTERVAL_MS = 30_000L
    }
}
