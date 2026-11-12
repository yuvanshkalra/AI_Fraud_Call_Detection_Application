package com.fraudcalldetector.domain.repository

import com.fraudcalldetector.domain.model.AlertRecord
import com.fraudcalldetector.domain.model.AlertStatus
import com.fraudcalldetector.domain.model.FraudAlertConfig
import com.fraudcalldetector.domain.model.RiskLevel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Contract for persisting and observing [AlertRecord]s — the in-app history
 * of every fraud alert that has been shown.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * Responsibilities
 * ─────────────────────────────────────────────────────────────────────────────
 *  • Add a new record when [FraudNotificationManager] fires an alert.
 *  • Update the [AlertStatus] when the user dismisses or views an alert.
 *  • Expose a live list of all records, newest first.
 *  • Provide the current [FraudAlertConfig] (threshold + min risk level) and
 *    allow the user to update it from Settings.
 *
 * Implemented by [AlertHistoryRepositoryImpl] in :feature:notifications.
 */
interface AlertHistoryRepository {

    // ── Alert records ─────────────────────────────────────────────────────────

    /**
     * Hot flow of all alert records, newest first.
     * Emits on every change (add, status update, clear).
     */
    val alerts: Flow<List<AlertRecord>>

    /**
     * Count of unread (ACTIVE) alerts — drives the badge on the history icon.
     */
    val unreadCount: StateFlow<Int>

    /**
     * Record [alert] in history (called when the notification is posted).
     */
    suspend fun addAlert(alert: AlertRecord)

    /**
     * Update the status of the alert for [callId].
     */
    suspend fun updateStatus(callId: String, status: AlertStatus)

    /**
     * Remove the alert for [callId].
     */
    suspend fun removeAlert(callId: String)

    /**
     * Clear the entire alert history.
     */
    suspend fun clearAll()

    /**
     * Return the most recent [AlertRecord] for [callId], or null.
     */
    suspend fun getAlert(callId: String): AlertRecord?

    // ── Config (threshold) ────────────────────────────────────────────────────

    /**
     * The current fraud-alert configuration (threshold score + min risk level).
     * Backed by DataStore; persists across app restarts.
     * Emits immediately with the current value; then on every update.
     */
    val alertConfig: Flow<FraudAlertConfig>

    /**
     * Persist a new [FraudAlertConfig].
     * Immediately reflected in [alertConfig] emissions.
     */
    suspend fun saveAlertConfig(config: FraudAlertConfig)
}
