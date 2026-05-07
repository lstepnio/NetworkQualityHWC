package com.hotwire.fisiontv.networkqual

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hotwire.fisiontv.networkqual.cert.CertificationEngine
import com.hotwire.fisiontv.networkqual.cert.CertificationResult
import com.hotwire.fisiontv.networkqual.cert.EngineEvent
import com.hotwire.fisiontv.networkqual.cert.TestStep
import com.hotwire.fisiontv.networkqual.config.RuntimeConfigProvider
import com.hotwire.fisiontv.networkqual.data.AppDatabase
import com.hotwire.fisiontv.networkqual.data.HistoryEntity
import com.hotwire.fisiontv.networkqual.data.toEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(application: Application) : AndroidViewModel(application) {

    sealed interface UiState {
        data object Idle : UiState
        data class Running(
            val currentStep: TestStep,
            val stepFrac: Float,
            val overallFrac: Float
        ) : UiState
        data class Done(val result: CertificationResult) : UiState
        data class Failed(val step: TestStep, val message: String) : UiState
    }

    private val configProvider = RuntimeConfigProvider()
    private val historyDao = AppDatabase.get(application).historyDao()

    private val _state = MutableStateFlow<UiState>(UiState.Idle)
    val state: StateFlow<UiState> = _state.asStateFlow()

    val recentHistory: StateFlow<List<HistoryEntity>> = historyDao.observeRecent()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private var runJob: Job? = null

    fun startCertification() {
        if (runJob?.isActive == true) return
        // Re-read config per run so a refresh that arrived between runs
        // (once the cert-config API is wired) takes effect on the next click
        // of "Run again" without an app restart.
        val engine = CertificationEngine(getApplication(), configProvider.current())
        runJob = viewModelScope.launch {
            _state.value = UiState.Running(TestStep.DNS, 0f, 0f)
            engine.run().collect { event ->
                when (event) {
                    is EngineEvent.StepProgress ->
                        _state.value = UiState.Running(event.step, event.stepFrac, event.overallFrac)
                    is EngineEvent.Complete -> {
                        _state.value = UiState.Done(event.result)
                        withContext(Dispatchers.IO) {
                            historyDao.insert(event.result.toEntity())
                        }
                    }
                    is EngineEvent.Failed ->
                        _state.value = UiState.Failed(event.step, event.cause)
                }
            }
        }
    }

    fun reset() {
        runJob?.cancel()
        runJob = null
        _state.value = UiState.Idle
    }
}
