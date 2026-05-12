package com.hotwire.fisiontv.networkqual.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp

/**
 * Renders [com.hotwire.fisiontv.networkqual.MainViewModel.UiState.UpdateRequired].
 *
 * Surfaces the version the STB needs to upgrade to and either an
 * "Update now" affordance (no prior attempt) or "Retry update" (a prior
 * attempt failed and we're showing the OS-provided reason). The "Run
 * certification" path is deliberately absent — we don't let the tech
 * bypass the gate.
 */
@Composable
fun UpdateRequiredScreen(
    targetVersionName: String,
    targetVersionCode: Int,
    reason: String?,
    onRetry: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 80.dp, vertical = 56.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.Start
    ) {
        Icon(
            imageVector = Icons.Filled.SystemUpdate,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(64.dp)
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = "Update required",
            style = MaterialTheme.typography.headlineLarge
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "This STB must upgrade to v$targetVersionName (code $targetVersionCode) before it can run a certification.",
            style = MaterialTheme.typography.bodyLarge
        )
        if (reason != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = reason,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
        }
        Spacer(Modifier.height(48.dp))
        Button(
            onClick = onRetry,
            modifier = Modifier
                .focusRequester(focusRequester)
                .height(56.dp)
                .widthIn(min = 220.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
        ) {
            Text(
                text = if (reason == null) "Update now" else "Retry update",
                style = MaterialTheme.typography.titleLarge
            )
        }
    }
}
