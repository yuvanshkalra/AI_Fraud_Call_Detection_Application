package com.fraudcalldetector.ui.monitoring

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fraudcalldetector.domain.model.MonitoringEvent
import com.fraudcalldetector.domain.model.MonitoringStatus
import com.fraudcalldetector.domain.usecase.ObserveMonitoringEventsUseCase
import com.fraudcalldetector.domain.usecase.ObserveMonitoringStatusUseCase
import com.fraudcalldetector.domain.usecase.StartMonitoringUseCase
import com.fraudcalldetector.domain.usecase.StopMonitoringUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import javax.inject.Inject

/**
 * Application-scoped ViewModel that manages monitoring lifecycle and
 * exposes observable state to any composable in the nav graph.
 *
 * Lifecycle notes
 * ────────────────
 * • Created by Hilt when the NavGraph host Activity starts.
 * • Survives configuration changes — monitoring status is never lost on rotation.
 * • Calls [startMonitoring] in [init] — the use-case is idempotent, so
 *   re-creating the ViewModel does not double-register the listener.
 */
@HiltViewModel
class MonitoringViewModel @Inject constructor(
    private val startMonitoring         : StartMonitoringUseCase,
    private val stopMonitoring          : StopMonitoringUseCase,
    observeStatus                       : ObserveMonitoringStatusUseCase,
    observeEvents                       : ObserveMonitoringEventsUseCase
) : ViewModel() {

    /** Current monitoring lifecycle status for UI display. */
    val status: StateFlow<MonitoringStatus> = observeStatus()

    /**
     * Derived UI state — simplified flag for "is there an active/ringing call right now?".
     */
    val isCallActive: StateFlow<Boolean> = status
        .map { it == MonitoringStatus.IN_CALL }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /**
     * The callId of the currently active call, or null when no call is in progress.
     * Used by [HomeScreen] to navigate to [ActiveCallScreen] with the correct callId.
     */
    val activeCallId: StateFlow<String?> = observeEvents()
        .map { event ->
            when (event) {
                is MonitoringEvent.Active -> event.callId
                is MonitoringEvent.Ended,
                is MonitoringEvent.Missed -> null
                else -> null
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Last monitoring event — useful for one-shot navigation reactions
     * (e.g. navigating to FraudWarning when HIGH+ analysis completes).
     */
    val lastEvent: StateFlow<MonitoringEvent?> = observeEvents()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Events that should trigger one-time side-effects in the UI
     * (snackbars, navigation). The flow is not replayed, so observers
     * only see events they were alive to receive.
     */
    val sideEffectEvents: Flow<MonitoringEvent> = observeEvents()
        .filter { it is MonitoringEvent.PermissionDenied
               || it is MonitoringEvent.Ringing
               || it is MonitoringEvent.Ended
               || it is MonitoringEvent.Missed }

    init {
        startMonitoring()
    }

    fun onStartMonitoring() = startMonitoring()
    fun onStopMonitoring()  = stopMonitoring()

    override fun onCleared() {
        // Do NOT stop monitoring here — the manager is Singleton-scoped and
        // lives beyond any single ViewModel. Only the service controls its lifecycle.
        super.onCleared()
    }
}
