package com.hotwire.fisiontv.networkqual.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.hotwire.fisiontv.networkqual.MainViewModel
import com.hotwire.fisiontv.networkqual.R

/**
 * Single-purpose screen shown while the update is downloading,
 * waiting for install confirmation, or after a failed install.
 *
 * Renders the same Fision logo header as [StartScreen] so the screen
 * feels like a phase of the same flow, not a different app.
 */
@Composable
fun UpdatingScreen(
    gate: MainViewModel.UpdateGate,
    onRetry: () -> Unit,
    onDismiss: () -> Unit
) {
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
        Spacer(Modifier.height(40.dp))

        when (gate) {
            is MainViewModel.UpdateGate.Downloading -> DownloadingBody(gate)
            is MainViewModel.UpdateGate.Installing -> InstallingBody(gate)
            is MainViewModel.UpdateGate.Failed -> FailedBody(gate, onRetry, onDismiss)
            else -> { /* AppRoot routes only the three states above here */ }
        }
    }
}

@Composable
private fun DownloadingBody(gate: MainViewModel.UpdateGate.Downloading) {
    Text(
        text = "Downloading v${gate.manifest.latestVersionName}",
        style = MaterialTheme.typography.headlineSmall,
        color = MaterialTheme.colorScheme.onBackground
    )
    Spacer(Modifier.height(8.dp))
    Text(
        text = "${(gate.fraction * 100).toInt()}% — ${humanBytes(gate.manifest.apkSizeBytes)}",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(24.dp))
    LinearProgressIndicator(
        progress = { gate.fraction.coerceIn(0f, 1f) },
        modifier = Modifier.fillMaxWidth(0.6f).height(8.dp),
        color = MaterialTheme.colorScheme.primary
    )
}

@Composable
private fun InstallingBody(gate: MainViewModel.UpdateGate.Installing) {
    Text(
        text = "Installing v${gate.manifest.latestVersionName}",
        style = MaterialTheme.typography.headlineSmall,
        color = MaterialTheme.colorScheme.onBackground
    )
    Spacer(Modifier.height(12.dp))
    Text(
        text = "If the system asks, tap Install. The app will restart and run the certification automatically.",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(24.dp))
    LinearProgressIndicator(
        modifier = Modifier.fillMaxWidth(0.6f).height(8.dp),
        color = MaterialTheme.colorScheme.primary
    )
}

@Composable
private fun FailedBody(
    gate: MainViewModel.UpdateGate.Failed,
    onRetry: () -> Unit,
    onDismiss: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Text(
        text = "Update failed",
        style = MaterialTheme.typography.headlineSmall,
        color = MaterialTheme.colorScheme.error
    )
    Spacer(Modifier.height(8.dp))
    Text(
        text = gate.reason,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(32.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Button(
            onClick = onRetry,
            modifier = Modifier
                .focusRequester(focusRequester)
                .height(56.dp)
                .widthIn(min = 180.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
        ) {
            Text("Retry update", style = MaterialTheme.typography.titleMedium)
        }
        OutlinedButton(
            onClick = onDismiss,
            modifier = Modifier.height(56.dp).widthIn(min = 180.dp)
        ) {
            Text("Dismiss")
        }
    }
}

private fun humanBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
    else -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
}
