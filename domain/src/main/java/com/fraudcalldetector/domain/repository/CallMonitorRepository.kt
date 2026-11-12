package com.fraudcalldetector.domain.repository

import com.fraudcalldetector.domain.model.MonitoringEvent
import com.fraudcalldetector.domain.model.MonitoringStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Contract for the call monitoring subsystem.
 *
 * Implemented in :feature:callmonitor. Other modules (audio recorder, fraud detection,
 * UI) program against this interface — they never touch telephony APIs directly.
 *
 * ┌─────────────────────────────────────────────────────────────────────────┐
 * │  Phone → TelephonyCallback/Listener → CallMonitoringManager            │
 * │         → emits MonitoringEvent on [events] Flow                        │
 * │         → persists CallEvent via CallRepository                         │
 * │         → updates [status] StateFlow                                    │
 * └─────────────────────────────────────────────────────────────────────────┘
 */
interface CallMonitorRepository {

    /** Hot SharedFlow of raw telephony events. Replay = 1 so late subscribers see the last event. */
    val events: Flow<MonitoringEvent>

    /** Current monitoring lifecycle status. */
    val status: StateFlow<MonitoringStatus>

    /**
     * Start the telephony listener. No-op if already active.
     * Emits [MonitoringEvent.MonitoringStarted] on success,
     * [MonitoringEvent.PermissionDenied] if READ_PHONE_STATE is absent.
     */
    fun startMonitoring()

    /**
     * Stop the telephony listener and release all resources.
     * Emits [MonitoringEvent.MonitoringStopped].
     */
    fun stopMonitoring()

    /** True when a call is currently in progress (RINGING or ACTIVE). */
    val isCallInProgress: Boolean

    /** Phone number of the currently tracked call, or null. */
    val currentCallNumber: String?

    /** Stable call-id of the currently tracked call, or null. */
    val currentCallId: String?
}
