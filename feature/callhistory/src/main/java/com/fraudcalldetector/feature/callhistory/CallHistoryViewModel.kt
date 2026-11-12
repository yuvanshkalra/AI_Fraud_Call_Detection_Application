package com.fraudcalldetector.feature.callhistory

import androidx.lifecycle.viewModelScope
import com.fraudcalldetector.core.base.BaseViewModel
import com.fraudcalldetector.core.base.UiEvent
import com.fraudcalldetector.core.base.UiState
import com.fraudcalldetector.domain.model.CallEvent
import com.fraudcalldetector.domain.model.RiskLevel
import com.fraudcalldetector.domain.usecase.DeleteCallUseCase
import com.fraudcalldetector.domain.usecase.GetCallHistoryUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class CallHistoryViewModel @Inject constructor(
    private val getCallHistory: GetCallHistoryUseCase,
    private val deleteCall: DeleteCallUseCase
) : BaseViewModel<CallHistoryState, CallHistoryEvent>() {

    override fun initialState() = CallHistoryState()

    init {
        loadHistory()
    }

    private fun loadHistory() {
        updateState { copy(isLoading = true) }
        getCallHistory()
            .onEach { calls ->
                updateState {
                    copy(
                        isLoading = false,
                        calls = applyFilter(calls, activeFilter),
                        allCalls = calls
                    )
                }
            }
            .catch { e ->
                Timber.e(e, "Failed to load call history")
                updateState { copy(isLoading = false, errorMessage = e.message) }
            }
            .launchIn(viewModelScope)
    }

    override fun onEvent(event: CallHistoryEvent) {
        when (event) {
            is CallHistoryEvent.DeleteCall -> viewModelScope.launch {
                deleteCall(event.callId)
            }
            is CallHistoryEvent.FilterChanged -> {
                updateState {
                    copy(
                        activeFilter = event.filter,
                        calls = applyFilter(allCalls, event.filter)
                    )
                }
            }
            is CallHistoryEvent.SearchQueryChanged -> {
                updateState {
                    copy(
                        searchQuery = event.query,
                        calls = applySearch(allCalls, event.query, activeFilter)
                    )
                }
            }
            CallHistoryEvent.ClearError -> updateState { copy(errorMessage = null) }
        }
    }

    private fun applyFilter(calls: List<CallEvent>, filter: HistoryFilter): List<CallEvent> =
        when (filter) {
            HistoryFilter.ALL -> calls
            HistoryFilter.FRAUD_ONLY -> calls.filter {
                it.fraudAnalysis?.isFraud == true
            }
            HistoryFilter.SAFE_ONLY -> calls.filter {
                it.fraudAnalysis?.isFraud == false
            }
            HistoryFilter.HIGH_RISK -> calls.filter {
                it.fraudAnalysis?.riskLevel in listOf(RiskLevel.HIGH, RiskLevel.CRITICAL)
            }
        }

    private fun applySearch(
        calls: List<CallEvent>,
        query: String,
        filter: HistoryFilter
    ): List<CallEvent> {
        val filtered = applyFilter(calls, filter)
        if (query.isBlank()) return filtered
        return filtered.filter { call ->
            call.phoneNumber.contains(query, ignoreCase = true) ||
                call.callerName?.contains(query, ignoreCase = true) == true
        }
    }
}

data class CallHistoryState(
    val isLoading: Boolean = false,
    val calls: List<CallEvent> = emptyList(),
    val allCalls: List<CallEvent> = emptyList(),
    val activeFilter: HistoryFilter = HistoryFilter.ALL,
    val searchQuery: String = "",
    val errorMessage: String? = null
) : UiState

sealed class CallHistoryEvent : UiEvent {
    data class DeleteCall(val callId: String) : CallHistoryEvent()
    data class FilterChanged(val filter: HistoryFilter) : CallHistoryEvent()
    data class SearchQueryChanged(val query: String) : CallHistoryEvent()
    object ClearError : CallHistoryEvent()
}

enum class HistoryFilter(val label: String) {
    ALL("All"),
    FRAUD_ONLY("Fraud"),
    SAFE_ONLY("Safe"),
    HIGH_RISK("High Risk")
}
