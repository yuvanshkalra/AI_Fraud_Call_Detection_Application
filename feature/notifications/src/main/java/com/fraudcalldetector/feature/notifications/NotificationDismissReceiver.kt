package com.fraudcalldetector.feature.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import com.fraudcalldetector.domain.model.AlertStatus
import com.fraudcalldetector.domain.repository.AlertHistoryRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * BroadcastReceiver that handles the "Dismiss" action on fraud-alert notifications.
 *
 * When the user taps "Dismiss" in the notification shade, Android fires this
 * receiver with [ACTION_DISMISS].  The receiver:
 *  1. Cancels the system notification.
 *  2. Updates the [AlertHistoryRepository] record to [AlertStatus.DISMISSED] so
 *     the in-app history screen reflects the change.
 *
 * Registration: declared in the `:feature:notifications` AndroidManifest.xml.
 * The component is NOT exported — only the app's own PendingIntent can trigger it.
 */
@AndroidEntryPoint
class NotificationDismissReceiver : BroadcastReceiver() {

    @Inject
    lateinit var alertHistoryRepository: AlertHistoryRepository

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_DISMISS) return

        val callId  = intent.getStringExtra(FraudNotificationManager.EXTRA_CALL_ID)
        val notifId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)

        if (notifId != -1) {
            NotificationManagerCompat.from(context).cancel(notifId)
        }

        if (!callId.isNullOrBlank()) {
            // Use goAsync so the coroutine has time to complete before the receiver is recycled.
            val pendingResult = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    alertHistoryRepository.updateStatus(callId, AlertStatus.DISMISSED)
                    Timber.d("NotificationDismissReceiver: status → DISMISSED callId=$callId")
                } catch (e: Exception) {
                    Timber.e(e, "NotificationDismissReceiver: failed to update status callId=$callId")
                } finally {
                    pendingResult.finish()
                }
            }
        }

        Timber.d("NotificationDismissReceiver: dismissed callId=$callId notifId=$notifId")
    }

    companion object {
        const val ACTION_DISMISS        = "com.fraudcalldetector.notifications.DISMISS_ALERT"
        const val EXTRA_NOTIFICATION_ID = "extra_notification_id"
    }
}
