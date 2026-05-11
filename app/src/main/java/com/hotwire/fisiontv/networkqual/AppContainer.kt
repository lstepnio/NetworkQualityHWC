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
    val silentInstallSupported: Boolean = BuildConfig.SILENT_INSTALL_SUPPORTED

    private val refreshScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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

        // Parallel app-update manifest fetch. Same pattern as cert-config:
        // non-blocking, silent-on-failure. If the manifest endpoint is
        // unreachable the cert button behaves as before — the installed
        // version is the baseline. Once the manifest lands, MainViewModel
        // computes the update gate and the "Run cert" / "Update & run"
        // label flip is driven from that.
        refreshScope.launch {
            when (val outcome = updateClient.fetch()) {
                is AppUpdateFetchOutcome.Updated -> _manifest.value = outcome.manifest
                is AppUpdateFetchOutcome.NotModified ->
                    Log.i(TAG, "app-update 304: keeping cached manifest")
                is AppUpdateFetchOutcome.Error ->
                    Log.w(TAG, "app-update fetch failed: ${outcome.cause}")
            }
        }
    }

    companion object {
        private const val TAG = "AppContainer"
    }
}
