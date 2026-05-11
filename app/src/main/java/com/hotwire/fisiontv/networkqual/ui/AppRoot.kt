package com.hotwire.fisiontv.networkqual.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hotwire.fisiontv.networkqual.MainViewModel

@Composable
fun AppRoot(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Box(modifier = Modifier.fillMaxSize()) {
        when (val s = state) {
            MainViewModel.UiState.Idle -> StartScreen(
                onStart = { viewModel.startCertification() }
            )
            is MainViewModel.UiState.Preparing -> PreparingScreen(
                title = s.title,
                subtitle = s.subtitle,
                fraction = s.frac
            )
            is MainViewModel.UiState.Running -> RunningScreen(
                currentStep = s.currentStep,
                stepFrac = s.stepFrac,
                overallFrac = s.overallFrac
            )
            is MainViewModel.UiState.Done -> ResultsScreen(
                result = s.result,
                onRunAgain = { viewModel.reset() }
            )
            is MainViewModel.UiState.Failed -> FailedScreen(
                step = s.step,
                message = s.message,
                onRetry = { viewModel.reset() }
            )
        }

        // Always-visible build label in the bottom-right corner.
        // After the auto-update pipeline lands a new version and the OS
        // replaces the app process, the tech sees the version flip here —
        // the only on-screen signal that the update happened. Also useful
        // for support ("what version are you on?").
        Text(
            text = "v${viewModel.installedVersionName}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 24.dp, bottom = 16.dp)
        )
    }
}
