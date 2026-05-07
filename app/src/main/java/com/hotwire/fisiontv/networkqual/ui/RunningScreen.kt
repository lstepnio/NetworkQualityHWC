package com.hotwire.fisiontv.networkqual.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hotwire.fisiontv.networkqual.cert.TestStep

@Composable
fun RunningScreen(
    currentStep: TestStep,
    stepFrac: Float,
    overallFrac: Float
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 80.dp, vertical = 56.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text("Certifying network…", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "This takes about a minute. Please don't navigate away.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(40.dp))

        TestStep.values().forEach { step ->
            val state = when {
                step.ordinal < currentStep.ordinal -> StepState.Done
                step == currentStep -> StepState.Active(stepFrac)
                else -> StepState.Pending
            }
            StepRow(step = step, state = state)
            Spacer(Modifier.height(16.dp))
        }

        Spacer(Modifier.height(24.dp))
        Text(
            "Overall progress: ${(overallFrac * 100).toInt()}%",
            style = MaterialTheme.typography.bodyLarge
        )
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { overallFrac },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )
    }
}

private sealed interface StepState {
    data object Pending : StepState
    data class Active(val frac: Float) : StepState
    data object Done : StepState
}

@Composable
private fun StepRow(step: TestStep, state: StepState) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier.size(28.dp),
            contentAlignment = Alignment.Center
        ) {
            when (state) {
                StepState.Pending -> Icon(
                    imageVector = Icons.Outlined.RadioButtonUnchecked,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(24.dp)
                )
                is StepState.Active -> CircularProgressIndicator(
                    progress = { state.frac },
                    strokeWidth = 3.dp,
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.size(28.dp)
                )
                StepState.Done -> Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp)
                )
            }
        }
        Spacer(Modifier.width(20.dp))
        Text(
            text = step.label,
            style = MaterialTheme.typography.titleMedium,
            color = if (state is StepState.Pending) MaterialTheme.colorScheme.outline
                else MaterialTheme.colorScheme.onBackground
        )
    }
}
