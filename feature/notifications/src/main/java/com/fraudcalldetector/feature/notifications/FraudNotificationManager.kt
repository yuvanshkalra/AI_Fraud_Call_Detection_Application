package com.fraudcalldetector.feature.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.fraudcalldetector.domain.model.FraudAnalysis
import com.fraudcalldetector.domain.model.RiskLevel
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Central notification dispatcher for fraud alerts and general call status.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * Notification anatomy
 * ─────────────────────────────────────────────────────────────────────────────
 *  • Title: risk-level headline with emoji ("⚠️ FRAUD CALL DETECTED")
 *  • Collapsed body: confidence % + phone number
 *  • Expanded BigText: reason lines, detected signal list
 *  • Caller name: shown when available
 *  • Actions:
 *      – "View Details" → deep-link to FraudWarningScreen for this call
 *      – "Dismiss"      → cancels the notification
 *  • Vibration pattern: intensity scales with risk level
 *  • Channel importance: CRITICAL/HIGH → IMPORTANCE_HIGH; others lower
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * Deep-link protocol
 * ─────────────────────────────────────────────────────────────────────────────
 * The "View Details" PendingIntent fires an explicit Intent to the host
 * Activity (class name passed as [activityClassName]).  The Intent carries:
 *   – extra EXTRA_CALL_ID   : String  = callId
 *   – extra EXTRA_NAVIGATE  : String  = "fraud_warning"
 * MainActivity reads these in onNewIntent and navigates accordingly.
 */
@Singleton
class FraudNotificationManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val notificationManager = NotificationManagerCompat.from(context)

    init {
        createChannels()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Post a fraud alert notification for [callId].
     *
     * @param callId      Stable call identifier (used as notification tag).
     * @param phoneNumber Caller's number, shown in body text.
     * @param analysis    Result of fraud analysis — drives priority, text, vibration.
     * @param callerName  Optional display name for the caller.
     * @param fraudReason Primary reason text from the AI model (first flag description
     *                    if empty).
     * @param tapIntent   Custom [PendingIntent] for the notification tap target.
     *                    If null a default deep-link to [MainActivity] is built.
     */
    fun showFraudAlert(
        callId      : String,
        phoneNumber : String,
        analysis    : FraudAnalysis,
        callerName  : String?         = null,
        fraudReason : String?         = null,
        tapIntent   : PendingIntent?  = null
    ) {
        if (!notificationManager.areNotificationsEnabled()) {
            Timber.w("FraudNotificationManager: notifications disabled by user")
            return
        }

        val channel   = channelForLevel(analysis.riskLevel)
        val priority  = priorityForLevel(analysis.riskLevel)
        val headline  = headlineForLevel(analysis.riskLevel)
        val callerDisplay = callerName?.takeIf { it.isNotBlank() } ?: phoneNumber
        val reason    = fraudReason?.takeIf { it.isNotBlank() }
            ?: analysis.flags.firstOrNull()?.description
            ?: "Suspicious activity detected"
        val confidence = (analysis.confidence * 100).toInt()

        val bodyText = buildString {
            append("$callerDisplay · $confidence% confidence")
            if (callerName != null && callerName.isNotBlank()) {
                append(" · $phoneNumber")
            }
        }

        val bigText = buildExpandedText(analysis, reason, callerDisplay, phoneNumber, callerName)

        val contentIntent = tapIntent ?: buildDeepLinkIntent(callId)
        val dismissIntent = buildDismissIntent(callId)

        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(headline)
            .setContentText(bodyText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            .setPriority(priority)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setVibrate(vibrationForLevel(analysis.riskLevel))
            .addAction(
                android.R.drawable.ic_menu_info_details,
                "View Details",
                contentIntent
            )
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Dismiss",
                dismissIntent
            )
            .build()

        notificationManager.notify(notificationId(callId), notification)
        Timber.d("FraudNotificationManager: alert posted callId=$callId level=${analysis.riskLevel} confidence=$confidence%")
    }

    /** Cancel the fraud alert notification for [callId]. */
    fun dismissFraudAlert(callId: String) {
        notificationManager.cancel(notificationId(callId))
        Timber.d("FraudNotificationManager: alert dismissed callId=$callId")
    }

    /** Cancel all posted fraud alert notifications. */
    fun dismissAllAlerts() {
        notificationManager.cancelAll()
        Timber.d("FraudNotificationManager: all alerts dismissed")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Intent builders
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Builds a [PendingIntent] that launches the host Activity and carries
     * extras so [MainActivity] can navigate to [FraudWarningScreen].
     */
    fun buildDeepLinkIntent(callId: String): PendingIntent {
        val intent = context.packageManager
            .getLaunchIntentForPackage(context.packageName)
            ?.apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(EXTRA_CALL_ID,  callId)
                putExtra(EXTRA_NAVIGATE, NAV_FRAUD_WARNING)
            }
            ?: Intent().apply {
                `package` = context.packageName
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                putExtra(EXTRA_CALL_ID,  callId)
                putExtra(EXTRA_NAVIGATE, NAV_FRAUD_WARNING)
            }

        return PendingIntent.getActivity(
            context,
            callId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                    PendingIntent.FLAG_IMMUTABLE else 0
        )
    }

    private fun buildDismissIntent(callId: String): PendingIntent {
        val intent = Intent(context, NotificationDismissReceiver::class.java).apply {
            action = NotificationDismissReceiver.ACTION_DISMISS
            putExtra(EXTRA_CALL_ID, callId)
            putExtra(NotificationDismissReceiver.EXTRA_NOTIFICATION_ID, notificationId(callId))
        }
        return PendingIntent.getBroadcast(
            context,
            notificationId(callId) + DISMISS_REQUEST_CODE_OFFSET,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                    PendingIntent.FLAG_IMMUTABLE else 0
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Text builders
    // ─────────────────────────────────────────────────────────────────────────

    private fun buildExpandedText(
        analysis      : FraudAnalysis,
        primaryReason : String,
        callerDisplay : String,
        phoneNumber   : String,
        callerName    : String?
    ): String = buildString {
        appendLine("Caller: $callerDisplay")
        if (callerName != null && callerName.isNotBlank()) {
            appendLine("Number: $phoneNumber")
        }
        appendLine("Risk Level: ${analysis.riskLevel.name}")
        appendLine("Confidence: ${(analysis.confidence * 100).toInt()}%")
        appendLine()
        appendLine("Reason: $primaryReason")
        if (analysis.flags.size > 1) {
            appendLine()
            appendLine("Detected signals:")
            analysis.flags.forEach { flag ->
                appendLine("  • ${flag.description} (${(flag.severity * 100).toInt()}%)")
            }
        }
    }.trimEnd()

    // ─────────────────────────────────────────────────────────────────────────
    // Risk-level helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun headlineForLevel(level: RiskLevel): String = when (level) {
        RiskLevel.CRITICAL -> "⚠️ FRAUD CALL DETECTED"
        RiskLevel.HIGH     -> "🚨 Suspicious Call — High Risk"
        RiskLevel.MEDIUM   -> "⚠️ Potentially Suspicious Call"
        RiskLevel.LOW      -> "ℹ️ Low-Risk Call Flagged"
        RiskLevel.SAFE     -> "✅ Call Cleared"
    }

    private fun channelForLevel(level: RiskLevel): String = when (level) {
        RiskLevel.CRITICAL, RiskLevel.HIGH -> CHANNEL_FRAUD_ALERTS_CRITICAL
        RiskLevel.MEDIUM                   -> CHANNEL_FRAUD_ALERTS_MEDIUM
        else                               -> CHANNEL_FRAUD_ALERTS_LOW
    }

    private fun priorityForLevel(level: RiskLevel): Int = when (level) {
        RiskLevel.CRITICAL -> NotificationCompat.PRIORITY_MAX
        RiskLevel.HIGH     -> NotificationCompat.PRIORITY_HIGH
        RiskLevel.MEDIUM   -> NotificationCompat.PRIORITY_DEFAULT
        RiskLevel.LOW      -> NotificationCompat.PRIORITY_LOW
        RiskLevel.SAFE     -> NotificationCompat.PRIORITY_MIN
    }

    /**
     * Vibration patterns scaled to urgency.
     * Format: [delay, vibrate, pause, vibrate, …] in ms.
     */
    private fun vibrationForLevel(level: RiskLevel): LongArray = when (level) {
        RiskLevel.CRITICAL -> longArrayOf(0, 300, 100, 300, 100, 600)   // triple strong
        RiskLevel.HIGH     -> longArrayOf(0, 200, 100, 400)             // double
        RiskLevel.MEDIUM   -> longArrayOf(0, 300)                       // single medium
        RiskLevel.LOW      -> longArrayOf(0, 100)                       // brief
        RiskLevel.SAFE     -> longArrayOf(0)                            // none
    }

    private fun notificationId(callId: String): Int = callId.hashCode()

    // ─────────────────────────────────────────────────────────────────────────
    // Channel creation
    // ─────────────────────────────────────────────────────────────────────────

    private fun createChannels() {
        val nm = context.getSystemService(NotificationManager::class.java)

        val criticalChannel = NotificationChannel(
            CHANNEL_FRAUD_ALERTS_CRITICAL,
            "Fraud Alerts — Critical",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description    = "Immediate alerts for CRITICAL and HIGH risk fraud calls"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 300, 100, 300, 100, 600)
        }

        val mediumChannel = NotificationChannel(
            CHANNEL_FRAUD_ALERTS_MEDIUM,
            "Fraud Alerts — Medium",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description    = "Alerts for MEDIUM risk calls with suspicious patterns"
            enableVibration(true)
        }

        val lowChannel = NotificationChannel(
            CHANNEL_FRAUD_ALERTS_LOW,
            "Fraud Alerts — Low",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description    = "Informational alerts for low-risk flags"
            enableVibration(false)
        }

        val statusChannel = NotificationChannel(
            CHANNEL_CALL_STATUS,
            "Call Monitor Status",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description    = "Ongoing call monitoring foreground notification"
            setShowBadge(false)
        }

        nm.createNotificationChannels(listOf(criticalChannel, mediumChannel, lowChannel, statusChannel))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Constants
    // ─────────────────────────────────────────────────────────────────────────

    companion object {
        // Channels
        const val CHANNEL_FRAUD_ALERTS_CRITICAL = "fraud_alerts_critical"
        const val CHANNEL_FRAUD_ALERTS_MEDIUM   = "fraud_alerts_medium"
        const val CHANNEL_FRAUD_ALERTS_LOW      = "fraud_alerts_low"
        /** @deprecated Use the split channels above; kept for legacy compatibility. */
        const val CHANNEL_FRAUD_ALERTS          = CHANNEL_FRAUD_ALERTS_CRITICAL
        const val CHANNEL_CALL_STATUS           = "call_status"

        // Deep-link extras
        const val EXTRA_CALL_ID   = "extra_call_id"
        const val EXTRA_NAVIGATE  = "extra_navigate"
        const val NAV_FRAUD_WARNING = "fraud_warning"

        private const val DISMISS_REQUEST_CODE_OFFSET = 10_000
    }
}
