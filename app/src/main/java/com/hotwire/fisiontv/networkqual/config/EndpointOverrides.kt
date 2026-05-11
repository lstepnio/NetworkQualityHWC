package com.hotwire.fisiontv.networkqual.config

import android.content.Context
import android.provider.Settings
import android.util.Log

/**
 * Runtime URL overrides for the device-facing backend endpoints.
 *
 * The release build hard-codes the production hostname
 * (`certifier-api.gethotwired.com`) into BuildConfig so retail STBs always
 * point at prod. In the lab we need release-signed builds (for signing-cert
 * continuity across self-updates) to talk to a local dev backend on the LAN.
 * Side-loading a debug APK would work but breaks the signing-cert chain.
 *
 * Solution: at app launch, each endpoint URL is resolved via
 * [Settings.Global]. If the setting is non-empty, it wins; otherwise the
 * BuildConfig default is used. This lets a dev STB redirect to
 * `http://192.168.10.233:18080/...` without modifying the APK.
 *
 * Set an override (requires adb shell access — no app-level secure-settings
 * permission needed because `adb shell` is the system user):
 *
 *   adb shell settings put global fisiontv_app_update_url_override \
 *       http://192.168.10.233:18080/v1/app/version
 *   adb shell settings put global fisiontv_cert_config_url_override \
 *       http://192.168.10.233:18080/v1/cert-config
 *
 * Clear an override:
 *
 *   adb shell settings delete global fisiontv_app_update_url_override
 *
 * Take effect: app restart. Reading is cheap (one IPC per launch) and
 * cached for the process lifetime via the [AppContainer] singleton.
 *
 * On a retail STB no overrides are set, so the BuildConfig prod URLs are
 * used unconditionally and the override mechanism is invisible.
 */
object EndpointOverrides {

    /** Setting key for `/v1/app/version` URL override. */
    const val KEY_APP_UPDATE_URL = "fisiontv_app_update_url_override"

    /** Setting key for `/v1/cert-config` URL override. */
    const val KEY_CERT_CONFIG_URL = "fisiontv_cert_config_url_override"

    /**
     * Returns the override at [settingKey] if non-empty, else [default].
     * Never throws; on any failure (uncommon — Global is unrestricted-read)
     * the default is returned.
     */
    fun resolve(context: Context, settingKey: String, default: String): String {
        return try {
            val override = Settings.Global.getString(context.contentResolver, settingKey)
            if (override.isNullOrBlank()) {
                default
            } else {
                Log.i(TAG, "override active: $settingKey=$override (BuildConfig default was $default)")
                override
            }
        } catch (t: Throwable) {
            Log.w(TAG, "failed to read $settingKey: ${t.message}")
            default
        }
    }

    private const val TAG = "EndpointOverrides"
}
