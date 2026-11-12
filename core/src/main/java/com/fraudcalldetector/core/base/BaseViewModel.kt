package com.fraudcalldetector.core.base

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Base ViewModel that standardizes UiState + UiEvent handling across all feature modules.
 */
abstract class BaseViewModel<S : UiState, E : UiEvent> : ViewModel() {

    private val _uiState = MutableStateFlow(initialState())
    val uiState: StateFlow<S> = _uiState.asStateFlow()

    protected abstract fun initialState(): S

    protected fun updateState(reducer: S.() -> S) {
        _uiState.value = _uiState.value.reducer()
    }

    abstract fun onEvent(event: E)
}

interface UiState
interface UiEvent
