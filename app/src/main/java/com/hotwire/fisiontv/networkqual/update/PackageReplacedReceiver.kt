package com.hotwire.fisiontv.networkqual.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.hotwire.fisiontv.networkqual.MainActivity

/**
 * Re-launches [MainActivity] after the OS finishes replacing our APK
 * (typically post-self-update). Without this, an install-commit kills
 * the old process and the device sits idle until the tech manually
 * relaunches from the launcher — defeating the "tap once, cert
 * auto-resumes on the new version" promise.
 *
 * **Caveat on sideloaded debug builds.** Android 10+ enforces
 * Background Activity Launch restrictions on receivers, and contrary
 * to common belief, `ACTION_MY_PACKAGE_REPLACED` is NOT on the
 * exemption list. On a plain sideloaded build the OS aborts our
 * `startActivity` call with `Abort background activity starts from
 * <uid>` in logcat, and the tech still has to relaunch manually.
 * This was confirmed in the lab against TiVo's Android TV build:
 *
 *   I/PkgReplacedRecv: package replaced — re-launching MainActivity
 *   W/ActivityTaskManager: Background activity start [callingUidProcState: RECEIVER ...]
 *   E/ActivityTaskManager: Abort background activity starts from <uid>
 *
 * The exemption that makes this code work is granted to apps holding
 * `INSTALL_PACKAGES` — a signature-protected permission only awarded
 * to apps signed with the platform key (i.e. shipped as a system app
 * baked into firmware). That's the OEM-cooperation pivot already
 * planned for production, and the auto-relaunch lights up for free
 * the day it lands. The other workaround paths
 * (user-toggled SYSTEM_ALERT_WINDOW, or a Notification with
 * fullScreenIntent) were considered and rejected as worse trade-offs
 * than "tech taps the app icon once after a sideloaded dev update".
 *
 * Manifest registration is mandatory — the app isn't running when
 * the broadcast fires, so a runtime-registered receiver wouldn't
 * see it.
 */
class PackageReplacedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        Log.i(TAG, "package replaced — re-launching MainActivity")
        val launch = Intent(context, MainActivity::class.java).apply {
            // FLAG_ACTIVITY_NEW_TASK is required when starting an Activity
            // from a non-Activity context. CLEAR_TOP guards against the
            // unlikely case where some launcher already started us and
            // we'd otherwise stack a second MainActivity on top.
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        try {
            context.startActivity(launch)
        } catch (t: Throwable) {
            Log.e(TAG, "couldn't launch MainActivity post-replace: ${t::class.simpleName}: ${t.message}")
        }
    }

    companion object {
        private const val TAG = "PkgReplacedRecv"
    }
}
