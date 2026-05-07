package com.hotwire.fisiontv.networkqual.config

import android.util.Log

/**
 * Single-process accessor for the runtime config.
 *
 * Today returns [RuntimeConfigDefaults.bundled]. The contract for the future
 * remote-fetch path:
 *
 *   - On every [current] call the cached config is returned synchronously.
 *   - A separate refresh routine (TBD; placement here keeps the seam clean)
 *     fetches `GET /v1/cert-config`, validates it (constructing the
 *     [RuntimeConfig] runs all `require(...)` checks), and atomically swaps
 *     [cached] only if the parsed config's `schemaVersion <=` what the app
 *     understands. Higher schemaVersion → ignore and stay on bundled.
 *   - Any exception during fetch or parse is logged and the previous cached
 *     copy stays in effect.
 */
class RuntimeConfigProvider {

    @Volatile
    private var cached: RuntimeConfig = RuntimeConfigDefaults.bundled

    fun current(): RuntimeConfig = cached

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
                cached = candidate
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
