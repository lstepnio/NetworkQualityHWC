package com.hotwire.fisiontv.networkqual.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import com.hotwire.fisiontv.networkqual.FisionApp

/**
 * Receives the two-phase install result from [PackageInstaller].
 *
 * Phase 1 — `STATUS_PENDING_USER_ACTION`:
 *   The OS wants the tech to tap "Install" on the system confirm
 *   dialog. The broadcast carries an `EXTRA_INTENT` we launch from
 *   this receiver. (Once we ship as a system app with
 *   INSTALL_PACKAGES signature permission, this phase is skipped.)
 *
 * Phase 2 — `STATUS_SUCCESS` / `STATUS_FAILURE` (and friends):
 *   The install completed (or didn't). Relay the outcome to the
 *   [AppContainer.updateInstallStatus] flow so the UI can react.
 *
 * On `STATUS_SUCCESS` the OS kills our process and re-launches us
 * automatically (because we just replaced ourselves). The
 * resume-after-update flag set by `MainViewModel.startUpdate()` is
 * what tells the fresh process to auto-start the cert.
 */
class UpdateInstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_INSTALL_RESULT) return

        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""
        val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        val app = context.applicationContext as? FisionApp
        val expected = app?.container?.updateInstaller?.expectedSessionId
        Log.i(TAG, "install broadcast: status=$status session=$sessionId expected=$expected message=$message")

        // Ignore broadcasts from earlier sessions in the same install
        // flow. PackageInstaller emits one terminal status per session;
        // if an earlier session failed (e.g. signing-cert mismatch) and
        // we retried with a different APK, the OS still delivers the old
        // session's failure broadcast. Without this filter, that stale
        // failure overwrites the in-flight session's outcome and
        // MainViewModel logs "install failed" for an install that
        // actually succeeded. See #44.
        //
        // STATUS_PENDING_USER_ACTION is allowed through regardless —
        // launching the system confirm dialog is idempotent and a stray
        // pending broadcast from a long-dead session is exceedingly rare
        // in practice.
        if (status != PackageInstaller.STATUS_PENDING_USER_ACTION &&
            expected != null && sessionId != -1 && sessionId != expected
        ) {
            Log.i(TAG, "ignoring stale broadcast: session=$sessionId expected=$expected")
            return
        }

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // The OS has prepared the "Install this update?" dialog.
                // Launch it as a new task — we're in a receiver and have
                // no Activity context to attach to.
                @Suppress("DEPRECATION")
                val confirmIntent: Intent? = intent.getParcelableExtra(Intent.EXTRA_INTENT)
                if (confirmIntent != null) {
                    confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try {
                        context.startActivity(confirmIntent)
                    } catch (t: Throwable) {
                        Log.e(TAG, "failed to launch confirm intent: ${t.message}")
                        publishStatus(context, InstallStatus.Failed(
                            "could not launch confirm dialog: ${t.message}"
                        ))
                    }
                } else {
                    Log.e(TAG, "PENDING_USER_ACTION without EXTRA_INTENT")
                    publishStatus(context, InstallStatus.Failed("missing confirm intent"))
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                publishStatus(context, InstallStatus.Success)
                app?.container?.updateInstaller?.clearExpectedSessionId()
                // On success the OS replaces the app process; the resume
                // flag set before commit() drives auto-start of the cert
                // when the new process boots.
            }
            else -> {
                // Everything else is a terminal failure (cancel, blocked,
                // conflict, storage, abort, incompatible). Surface the
                // OS-provided message so the tech sees why.
                val reason = mapFailureStatus(status) + if (message.isNotBlank()) " — $message" else ""
                publishStatus(context, InstallStatus.Failed(reason))
                app?.container?.updateInstaller?.clearExpectedSessionId()
            }
        }
    }

    private fun publishStatus(context: Context, status: InstallStatus) {
        val app = context.applicationContext as? FisionApp
        if (app == null) {
            Log.w(TAG, "application context is not FisionApp; cannot publish status")
            return
        }
        app.container.publishInstallStatus(status)
    }

    private fun mapFailureStatus(status: Int): String = when (status) {
        PackageInstaller.STATUS_FAILURE -> "install failed"
        PackageInstaller.STATUS_FAILURE_ABORTED -> "install cancelled"
        PackageInstaller.STATUS_FAILURE_BLOCKED -> "install blocked by the system"
        PackageInstaller.STATUS_FAILURE_CONFLICT -> "install conflicts with another package"
        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "APK incompatible with this device"
        PackageInstaller.STATUS_FAILURE_INVALID -> "APK invalid"
        PackageInstaller.STATUS_FAILURE_STORAGE -> "not enough storage"
        else -> "install failed (status=$status)"
    }

    companion object {
        private const val TAG = "UpdateInstallRecv"
        const val ACTION_INSTALL_RESULT =
            "com.hotwire.fisiontv.networkqual.update.INSTALL_RESULT"
    }
}

/**
 * Terminal install outcomes the receiver publishes to the rest of the app.
 * `Pending` lives in [AppUpdateInstaller.BeginOutcome] instead — once we're
 * past pre-install verification we never go back to "pending" from the
 * receiver's perspective.
 */
sealed interface InstallStatus {
    data object Idle : InstallStatus
    data object AwaitingUserConfirmation : InstallStatus
    data object Success : InstallStatus
    data class Failed(val reason: String) : InstallStatus
}
