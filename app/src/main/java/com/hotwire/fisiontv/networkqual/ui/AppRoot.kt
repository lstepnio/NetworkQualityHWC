package com.hotwire.fisiontv.networkqual.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hotwire.fisiontv.networkqual.MainViewModel

@Composable
fun AppRoot(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    when (val s = state) {
        MainViewModel.UiState.Idle -> StartScreen(
            onStart = { viewModel.startCertification() }
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
}
