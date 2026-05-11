package com.hotwire.fisiontv.networkqual.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hotwire.fisiontv.networkqual.MainViewModel

@Composable
fun AppRoot(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val updateGate by viewModel.updateGate.collectAsStateWithLifecycle()

    when (val s = state) {
        MainViewModel.UiState.Idle -> {
            // Update-side states paint over the start screen while
            // download/install is in flight. The cert flow itself is
            // never running concurrently — the gate transitions and the
            // UiState transitions are interlocked via MainViewModel.
            when (val g = updateGate) {
                is MainViewModel.UpdateGate.Downloading,
                is MainViewModel.UpdateGate.Installing,
                is MainViewModel.UpdateGate.Failed -> UpdatingScreen(
                    gate = g,
                    onRetry = { viewModel.startUpdate() },
                    onDismiss = { viewModel.clearUpdateFailure() }
                )
                else -> StartScreen(
                    updateGate = g,
                    onStart = { viewModel.startCertification() },
                    onUpdate = { viewModel.startUpdate() }
                )
            }
        }
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
}
