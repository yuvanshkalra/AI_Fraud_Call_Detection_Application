package com.fraudcalldetector.ui.fraudwarning

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fraudcalldetector.domain.model.AlertStatus
import com.fraudcalldetector.domain.model.CallEvent
import com.fraudcalldetector.domain.repository.AlertHistoryRepository
import com.fraudcalldetector.domain.repository.CallRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for [FraudWarningScreen].
 *
 * Loads the [CallEvent] by its [callId] (from [SavedStateHandle]) so the screen
 * can be reached from both in-app navigation (selectedCall) and notification
 * deep-links where no prior in-app selection exists.
 *
 * Also marks the corresponding [AlertHistoryRepository] record as [AlertStatus.VIEWED]
 * when the user opens the screen — this clears the unread badge.
 */
@HiltViewModel
class FraudWarningViewModel @Inject constructor(
    private val callRepository        : CallRepository,
    private val alertHistoryRepository: AlertHistoryRepository,
    savedStateHandle                  : SavedStateHandle
) : ViewModel() {

    private val callId: String = savedStateHandle.get<String>("callId") ?: ""

    private val _uiState = MutableStateFlow<FraudWarningUiState>(FraudWarningUiState.Loading)
    val uiState: StateFlow<FraudWarningUiState> = _uiState.asStateFlow()

    init {
        loadCall()
    }

    private fun loadCall() {
        viewModelScope.launch {
            if (callId.isBlank()) {
                _uiState.value = FraudWarningUiState.Error("No call ID provided")
                return@launch
            }
            val call = callRepository.getCallById(callId)
            _uiState.value = if (call != null) {
                // Mark the alert as VIEWED so the unread badge clears
                runCatching {
                    alertHistoryRepository.updateStatus(callId, AlertStatus.VIEWED)
                }
                FraudWarningUiState.Success(call)
            } else {
                FraudWarningUiState.Error("Call not found")
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// UI state
// ─────────────────────────────────────────────────────────────────────────────

sealed class FraudWarningUiState {
    object Loading : FraudWarningUiState()
    data class Success(val call: CallEvent) : FraudWarningUiState()
    data class Error(val message: String) : FraudWarningUiState()
}
