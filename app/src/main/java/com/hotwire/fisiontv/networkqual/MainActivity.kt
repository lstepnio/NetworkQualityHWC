package com.hotwire.fisiontv.networkqual

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hotwire.fisiontv.networkqual.ui.AppRoot
import com.hotwire.fisiontv.networkqual.ui.theme.FisionTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        // Killswitch hook: re-fetch the active cert-config at every
        // activity launch (the init-time fetch in AppContainer only
        // fires once per process; for a process that survives a
        // killswitched-then-recovered cycle we need a fresh ask).
        // After the fetch resolves, if `killswitch.enabled` is true,
        // exit cleanly via finishAffinity(). Recovery is automatic —
        // next launch fetches again.
        val container = (application as FisionApp).container
        lifecycleScope.launch {
            container.refreshCertConfig()
            val cfg = container.configProvider.current()
            if (cfg.killswitch.enabled) {
                Log.w(
                    TAG,
                    "killswitch engaged (configVersion=${cfg.configVersion}, " +
                        "reason=${cfg.killswitch.reason ?: "—"}); exiting"
                )
                finishAffinity()
            }
        }

        setContent {
            FisionTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppRoot(viewModel = viewModel())
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-check for a newer app version whenever the tech returns to
        // the foreground. The launch-time fetch in AppContainer.init
        // only fires once per process; without this hook, an app that's
        // been sitting on the start screen for an hour while a new
        // version is published would never notice. Rate-limited in
        // AppContainer.refreshManifest so a screen-off / screen-on flurry
        // doesn't burn the API.
        (application as FisionApp).container.refreshManifest()
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
