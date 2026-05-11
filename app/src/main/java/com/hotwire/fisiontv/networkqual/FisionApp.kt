package com.hotwire.fisiontv.networkqual

import android.app.Application
import android.content.Context

/**
 * Application class. Hosts the process-wide [AppContainer] so every
 * other component (ViewModels, future workers, etc.) reads dependencies
 * from one canonical place rather than constructing them ad-hoc.
 *
 * Also owns the small "resume cert after update" flag in SharedPreferences.
 * Lives here, not in the ViewModel, because the flag must survive the
 * OS-driven process kill that happens when the APK gets replaced — i.e.
 * a *fresh* process needs to read it during onCreate before any
 * ViewModel exists.
 */
class FisionApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }

    private val prefs by lazy {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Records that the in-flight install is being run as part of a
     * "Update & run cert" flow. After the OS replaces the app and the
     * new process boots, [consumeResumeCertAfterUpdateFlag] sees this
     * and auto-starts the cert.
     *
     * Stores the [targetVersionCode] so the consume-side can sanity-check
     * the new process is actually at-or-above the expected version
     * before resuming. If the install ended up failing somehow and we
     * relaunched at the old version, we don't want to start a cert with
     * an outdated client.
     */
    fun markResumeCertAfterUpdate(targetVersionCode: Int) {
        prefs.edit().putInt(KEY_RESUME_VERSION_CODE, targetVersionCode).apply()
    }

    /** Clears the resume flag without consuming. Used on install failure paths. */
    fun clearResumeCertAfterUpdate() {
        prefs.edit().remove(KEY_RESUME_VERSION_CODE).apply()
    }

    /**
     * Returns true exactly once if the flag is set AND the currently
     * installed versionCode matches or exceeds what was expected. The
     * flag is cleared either way (one-shot semantics — we never auto-start
     * twice from the same flag write).
     */
    fun consumeResumeCertAfterUpdateFlag(): Boolean {
        val target = prefs.getInt(KEY_RESUME_VERSION_CODE, -1)
        if (target < 0) return false
        prefs.edit().remove(KEY_RESUME_VERSION_CODE).apply()
        val installed = BuildConfig.VERSION_CODE
        return installed >= target
    }

    companion object {
        private const val PREFS_NAME = "fision_app_state"
        private const val KEY_RESUME_VERSION_CODE = "resume_cert_after_update_target_version_code"
    }
}
