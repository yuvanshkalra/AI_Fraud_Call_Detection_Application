package com.fraudcalldetector.domain.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * A single fraud alert record stored in the alert history.
 *
 * Created whenever [FraudNotificationManager.showFraudAlert] fires a notification.
 * Persisted by [AlertHistoryRepository] so the user can review past alerts even
 * after dismissing the notification.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * Lifecycle
 * ─────────────────────────────────────────────────────────────────────────────
 *  ACTIVE  → the notification is still posted in the system tray
 *  DISMISSED → the user tapped "Dismiss" or the alert was programmatically cancelled
 *  VIEWED  → the user tapped "View Details" and opened the in-app screen
 */
@Parcelize
data class AlertRecord(
    /** Stable identifier, same as the [CallEvent.id] it belongs to. */
    val callId       : String,

    /** When the alert was first fired (epoch ms). */
    val firedAtMs    : Long   = System.currentTimeMillis(),

    /** Caller's phone number. */
    val phoneNumber  : String,

    /** Caller's display name, if available from the device contacts. */
    val callerName   : String? = null,

    /** The fraud analysis that triggered this alert. */
    val analysis     : FraudAnalysis,

    /** The primary reason shown to the user (first flag description or model reason). */
    val primaryReason: String,

    /** Current lifecycle state of the alert. */
    val status       : AlertStatus = AlertStatus.ACTIVE
) : Parcelable {
    val confidence: Float    get() = analysis.confidence
    val riskLevel : RiskLevel get() = analysis.riskLevel
    val isFraud   : Boolean  get() = analysis.isFraud
}

/** Lifecycle state of an [AlertRecord]. */
enum class AlertStatus {
    /** Notification is still in the shade; user has not interacted. */
    ACTIVE,
    /** User tapped "Dismiss" or the alert was cancelled programmatically. */
    DISMISSED,
    /** User tapped "View Details" and opened the in-app fraud warning screen. */
    VIEWED
}
