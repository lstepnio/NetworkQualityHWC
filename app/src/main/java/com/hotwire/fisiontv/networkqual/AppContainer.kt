package com.hotwire.fisiontv.networkqual

import android.content.Context
import android.util.Log
import com.hotwire.fisiontv.networkqual.config.RuntimeConfigProvider
import com.hotwire.fisiontv.networkqual.data.AppDatabase
import com.hotwire.fisiontv.networkqual.diagnostics.DeviceIdentityCollector
import com.hotwire.fisiontv.networkqual.publish.CertConfigClient
import com.hotwire.fisiontv.networkqual.publish.FetchOutcome
import com.hotwire.fisiontv.networkqual.publish.NoAuthProvider
import com.hotwire.fisiontv.networkqual.publish.OkHttpCertConfigClient
import com.hotwire.fisiontv.networkqual.publish.PublishQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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

    private val certConfigClient: CertConfigClient = OkHttpCertConfigClient(
        endpoint = BuildConfig.CERT_CONFIG_URL,
        authProvider = NoAuthProvider,
        deviceId = DeviceIdentityCollector.deviceId(applicationContext),
        appVersion = BuildConfig.VERSION_NAME
    )

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
    }

    companion object {
        private const val TAG = "AppContainer"
    }
}
