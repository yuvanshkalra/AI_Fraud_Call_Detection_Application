package com.fraudcalldetector.domain.usecase

import com.fraudcalldetector.domain.model.MonitoringEvent
import com.fraudcalldetector.domain.model.MonitoringStatus
import com.fraudcalldetector.domain.repository.CallMonitorRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * Start call monitoring (idempotent — safe to call multiple times).
 * Emits [MonitoringEvent.PermissionDenied] if READ_PHONE_STATE is absent.
 */
class StartMonitoringUseCase @Inject constructor(
    private val repository: CallMonitorRepository
) {
    operator fun invoke() = repository.startMonitoring()
}

/**
 * Stop call monitoring and release telephony resources.
 */
class StopMonitoringUseCase @Inject constructor(
    private val repository: CallMonitorRepository
) {
    operator fun invoke() = repository.stopMonitoring()
}

/**
 * Observe the real-time monitoring status for UI display.
 */
class ObserveMonitoringStatusUseCase @Inject constructor(
    private val repository: CallMonitorRepository
) {
    operator fun invoke(): StateFlow<MonitoringStatus> = repository.status
}

/**
 * Observe raw [MonitoringEvent] emissions.
 * Used by audio recorder, fraud detection, and the active call screen.
 */
class ObserveMonitoringEventsUseCase @Inject constructor(
    private val repository: CallMonitorRepository
) {
    operator fun invoke(): Flow<MonitoringEvent> = repository.events
}
