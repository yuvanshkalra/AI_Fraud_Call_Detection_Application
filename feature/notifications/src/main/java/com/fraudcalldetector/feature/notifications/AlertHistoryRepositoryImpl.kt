package com.fraudcalldetector.feature.notifications

import android.content.Context
import android.content.SharedPreferences
import com.fraudcalldetector.domain.model.AlertRecord
import com.fraudcalldetector.domain.model.AlertStatus
import com.fraudcalldetector.domain.model.FraudAlertConfig
import com.fraudcalldetector.domain.model.RiskLevel
import com.fraudcalldetector.domain.repository.AlertHistoryRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory [AlertHistoryRepository] backed by [SharedPreferences] for
 * [FraudAlertConfig] persistence.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * Storage model
 * ─────────────────────────────────────────────────────────────────────────────
 *  • Alert records: held in a [MutableList] in memory.  The list is ordered
 *    newest-first and capped at [MAX_HISTORY_SIZE] entries to avoid unbounded
 *    growth across long app sessions.
 *  • Alert config: two values written to SharedPreferences:
 *      – KEY_THRESHOLD     : Float  (0.0–1.0)
 *      – KEY_MIN_RISK      : String (RiskLevel.name)
 *      – KEY_FINAL_PASS    : Boolean
 *
 * A full Room migration is intentionally deferred — alerts are ephemeral UX
 * signals and their loss across app installs is acceptable.  If persistence
 * across installs is needed later, swap this class for a Room-backed one
 * without changing any call sites.
 */
@Singleton
class AlertHistoryRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : AlertHistoryRepository {

    // ─────────────────────────────────────────────────────────────────────────
    // In-memory alert store
    // ─────────────────────────────────────────────────────────────────────────

    private val records = mutableListOf<AlertRecord>()

    private val _alertsFlow = MutableSharedFlow<List<AlertRecord>>(
        replay              = 1,
        extraBufferCapacity = 8
    )
    override val alerts: Flow<List<AlertRecord>> = _alertsFlow.asSharedFlow()

    private val _unreadCount = MutableStateFlow(0)
    override val unreadCount: StateFlow<Int> = _unreadCount.asStateFlow()

    // ─────────────────────────────────────────────────────────────────────────
    // Alert config (SharedPreferences-backed)
    // ─────────────────────────────────────────────────────────────────────────

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private val _configFlow = MutableStateFlow(loadConfig())
    override val alertConfig: Flow<FraudAlertConfig> = _configFlow.asStateFlow()

    // ─────────────────────────────────────────────────────────────────────────
    // AlertHistoryRepository — records
    // ─────────────────────────────────────────────────────────────────────────

    override suspend fun addAlert(alert: AlertRecord) {
        // Remove any previous record for the same call (idempotent)
        records.removeAll { it.callId == alert.callId }
        // Prepend newest first
        records.add(0, alert)
        // Trim to max size
        if (records.size > MAX_HISTORY_SIZE) {
            records.subList(MAX_HISTORY_SIZE, records.size).clear()
        }
        emitSnapshot()
        Timber.d("AlertHistoryRepository: added alert callId=${alert.callId} level=${alert.riskLevel}")
    }

    override suspend fun updateStatus(callId: String, status: AlertStatus) {
        val idx = records.indexOfFirst { it.callId == callId }
        if (idx < 0) return
        records[idx] = records[idx].copy(status = status)
        emitSnapshot()
        Timber.d("AlertHistoryRepository: updated status callId=$callId → $status")
    }

    override suspend fun removeAlert(callId: String) {
        val removed = records.removeAll { it.callId == callId }
        if (removed) {
            emitSnapshot()
            Timber.d("AlertHistoryRepository: removed alert callId=$callId")
        }
    }

    override suspend fun clearAll() {
        records.clear()
        emitSnapshot()
        Timber.d("AlertHistoryRepository: cleared all alerts")
    }

    override suspend fun getAlert(callId: String): AlertRecord? =
        records.firstOrNull { it.callId == callId }

    // ─────────────────────────────────────────────────────────────────────────
    // AlertHistoryRepository — config
    // ─────────────────────────────────────────────────────────────────────────

    override suspend fun saveAlertConfig(config: FraudAlertConfig) {
        prefs.edit()
            .putFloat(KEY_THRESHOLD,   config.alertThreshold)
            .putString(KEY_MIN_RISK,   config.minRiskLevelForAlert.name)
            .putBoolean(KEY_FINAL_PASS, config.alertOnFinalPassOnly)
            .apply()
        _configFlow.value = config
        Timber.d("AlertHistoryRepository: saved config threshold=${config.alertThreshold} minLevel=${config.minRiskLevelForAlert}")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun emitSnapshot() {
        val snapshot = records.toList()
        _alertsFlow.tryEmit(snapshot)
        _unreadCount.value = snapshot.count { it.status == AlertStatus.ACTIVE }
    }

    private fun loadConfig(): FraudAlertConfig {
        val threshold  = prefs.getFloat(KEY_THRESHOLD, FraudAlertConfig.DEFAULT_THRESHOLD)
        val minRisk    = runCatching {
            RiskLevel.valueOf(prefs.getString(KEY_MIN_RISK, RiskLevel.MEDIUM.name) ?: RiskLevel.MEDIUM.name)
        }.getOrDefault(RiskLevel.MEDIUM)
        val finalOnly  = prefs.getBoolean(KEY_FINAL_PASS, false)
        return FraudAlertConfig(
            alertThreshold       = threshold,
            minRiskLevelForAlert = minRisk,
            alertOnFinalPassOnly = finalOnly
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Constants
    // ─────────────────────────────────────────────────────────────────────────

    companion object {
        private const val PREFS_NAME       = "fraud_alert_prefs"
        private const val KEY_THRESHOLD    = "alert_threshold"
        private const val KEY_MIN_RISK     = "min_risk_level"
        private const val KEY_FINAL_PASS   = "alert_final_pass_only"
        private const val MAX_HISTORY_SIZE = 100
    }
}
