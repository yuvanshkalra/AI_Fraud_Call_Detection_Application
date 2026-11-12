package com.fraudcalldetector.ui.alerts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fraudcalldetector.domain.model.AlertRecord
import com.fraudcalldetector.domain.model.AlertStatus
import com.fraudcalldetector.domain.model.FraudAlertConfig
import com.fraudcalldetector.domain.model.RiskLevel
import com.fraudcalldetector.domain.repository.AlertHistoryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AlertHistoryViewModel @Inject constructor(
    private val alertHistoryRepository: AlertHistoryRepository
) : ViewModel() {

    // ── Alert list ─────────────────────────────────────────────────────────────

    val alerts: StateFlow<List<AlertRecord>> = alertHistoryRepository.alerts
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val unreadCount: StateFlow<Int> = alertHistoryRepository.unreadCount

    // ── Alert config ───────────────────────────────────────────────────────────

    val alertConfig: StateFlow<FraudAlertConfig> = alertHistoryRepository.alertConfig
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FraudAlertConfig.DEFAULT)

    // ── Actions ────────────────────────────────────────────────────────────────

    fun markViewed(callId: String) {
        viewModelScope.launch {
            alertHistoryRepository.updateStatus(callId, AlertStatus.VIEWED)
        }
    }

    fun dismissAlert(callId: String) {
        viewModelScope.launch {
            alertHistoryRepository.updateStatus(callId, AlertStatus.DISMISSED)
        }
    }

    fun removeAlert(callId: String) {
        viewModelScope.launch {
            alertHistoryRepository.removeAlert(callId)
        }
    }

    fun clearAll() {
        viewModelScope.launch {
            alertHistoryRepository.clearAll()
        }
    }

    fun saveAlertConfig(config: FraudAlertConfig) {
        viewModelScope.launch {
            alertHistoryRepository.saveAlertConfig(config)
        }
    }
}
