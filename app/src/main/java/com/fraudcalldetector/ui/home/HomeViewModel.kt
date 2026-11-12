package com.fraudcalldetector.ui.home

import com.fraudcalldetector.domain.model.CallEvent
import com.fraudcalldetector.domain.model.RiskLevel
import com.fraudcalldetector.domain.usecase.GetCallHistoryUseCase
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    getCallHistory: GetCallHistoryUseCase
) : ViewModel() {

    val uiState: StateFlow<HomeState> = getCallHistory()
        .map { calls ->
            val analysed = calls.filter { it.fraudAnalysis != null }
            val totalSafe = analysed.count { it.fraudAnalysis?.riskLevel == RiskLevel.SAFE }
            val safeRate  = if (analysed.isNotEmpty()) (totalSafe * 100f / analysed.size).toInt() else 100

            HomeState(
                isMonitoring  = true,
                recentCalls   = calls.take(8),
                totalCalls    = calls.size,
                fraudCalls    = calls.count { it.fraudAnalysis?.isFraud == true },
                safeCalls     = totalSafe,
                blockedThisWeek = calls.count {
                    it.fraudAnalysis?.isFraud == true &&
                    it.startTime > System.currentTimeMillis() - 7 * 24 * 3600_000L
                },
                safeRatePercent = safeRate,
                criticalAlerts  = calls.count { it.fraudAnalysis?.riskLevel == RiskLevel.CRITICAL }
            )
        }
        .catch { emit(HomeState()) }
        .stateIn(
            scope   = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = HomeState()
        )
}

data class HomeState(
    val isMonitoring    : Boolean         = false,
    val recentCalls     : List<CallEvent> = emptyList(),
    val totalCalls      : Int             = 0,
    val fraudCalls      : Int             = 0,
    val safeCalls       : Int             = 0,
    val blockedThisWeek : Int             = 0,
    val safeRatePercent : Int             = 100,
    val criticalAlerts  : Int             = 0
)
