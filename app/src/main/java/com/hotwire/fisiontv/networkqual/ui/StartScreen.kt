package com.hotwire.fisiontv.networkqual.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.hotwire.fisiontv.networkqual.MainViewModel
import com.hotwire.fisiontv.networkqual.R

@Composable
fun StartScreen(
    updateGate: MainViewModel.UpdateGate,
    onStart: () -> Unit,
    onUpdate: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(updateGate.javaClass) { focusRequester.requestFocus() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 80.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Image(
            painter = painterResource(id = R.drawable.logo_fision_on_dark),
            contentDescription = "FisionTV+",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxWidth(0.55f)
                .height(140.dp)
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = "Network Certification",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "by Hotwire Communications",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(32.dp))
        Text(
            text = "Verify this connection can support FisionTV+ streaming.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground
        )

        when (updateGate) {
            is MainViewModel.UpdateGate.Optional -> {
                Spacer(Modifier.height(24.dp))
                UpdateBanner(
                    title = "Update available — v${updateGate.manifest.latestVersionName}",
                    body = updateGate.manifest.releaseNotes
                        ?: "A newer version of the certifier is available.",
                    tone = BannerTone.Info
                )
            }
            is MainViewModel.UpdateGate.RequiredBeforeCert -> {
                Spacer(Modifier.height(24.dp))
                UpdateBanner(
                    title = "Update required — v${updateGate.manifest.latestVersionName}",
                    body = updateGate.manifest.releaseNotes
                        ?: "This release of the certifier is too old to produce a valid result. Update before running.",
                    tone = BannerTone.Warn
                )
            }
            else -> { /* Clear, Downloading, Installing, Failed handled by AppRoot */ }
        }

        Spacer(Modifier.height(40.dp))

        val isRequired = updateGate is MainViewModel.UpdateGate.RequiredBeforeCert
        Button(
            onClick = if (isRequired) onUpdate else onStart,
            modifier = Modifier
                .focusRequester(focusRequester)
                .height(64.dp)
                .widthIn(min = 280.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
        ) {
            Text(
                text = if (isRequired) "Update & run" else "Run certification",
                style = MaterialTheme.typography.titleLarge
            )
        }
    }
}

private enum class BannerTone { Info, Warn }

@Composable
private fun UpdateBanner(title: String, body: String, tone: BannerTone) {
    val bg = when (tone) {
        BannerTone.Info -> Color(0xFF1E3A8A) // blue-900
        BannerTone.Warn -> Color(0xFF7C2D12) // orange-900
    }
    val accent = when (tone) {
        BannerTone.Info -> Color(0xFF93C5FD) // blue-300
        BannerTone.Warn -> Color(0xFFFDBA74) // orange-300
    }
    Box(
        modifier = Modifier
            .fillMaxWidth(0.75f)
            .background(bg, RoundedCornerShape(12.dp))
            .padding(horizontal = 24.dp, vertical = 16.dp)
    ) {
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = accent
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White
            )
        }
    }
}
