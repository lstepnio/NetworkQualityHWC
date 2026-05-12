package com.hotwire.fisiontv.networkqual.config

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Single-process accessor for the runtime config.
 *
 * Today seeds with [RuntimeConfigDefaults.bundled]. Remote-fetch contract:
 *
 *   - [current] returns the cached config synchronously.
 *   - [flow] emits the cached config whenever it changes. UI components
 *     observe this to react when the remote config lands (e.g. the
 *     configVersion footer next to the app version).
 *   - A refresh routine in [com.hotwire.fisiontv.networkqual.AppContainer]
 *     fetches `GET /v1/cert-config`, validates it via [RuntimeConfig]'s
 *     `init` checks, and atomically swaps the cache only if the parsed
 *     config's `schemaVersion <=` what the app understands.
 *   - Any exception during fetch or parse is logged and the previous
 *     cached copy stays in effect.
 */
class RuntimeConfigProvider {

    private val _flow = MutableStateFlow(RuntimeConfigDefaults.bundled)
    val flow: StateFlow<RuntimeConfig> = _flow.asStateFlow()

    fun current(): RuntimeConfig = _flow.value

    /**
     * Apply a freshly-fetched remote config. Validates by virtue of
     * [RuntimeConfig]'s `init` checks; if those throw or the schema is too
     * new, the previous cached config is preserved.
     */
    fun apply(candidate: RuntimeConfig): Boolean {
        return try {
            if (candidate.schemaVersion > RuntimeConfigDefaults.bundled.schemaVersion) {
                Log.w(TAG, "rejecting remote config: schemaVersion ${candidate.schemaVersion} > supported ${RuntimeConfigDefaults.bundled.schemaVersion}")
                false
            } else {
                _flow.value = candidate
                Log.i(TAG, "applied remote config ${candidate.configVersion} (schema=${candidate.schemaVersion})")
                true
            }
        } catch (t: Throwable) {
            Log.e(TAG, "rejected remote config: ${t.message}")
            false
        }
    }

    companion object {
        private const val TAG = "RuntimeConfig"
    }
}
