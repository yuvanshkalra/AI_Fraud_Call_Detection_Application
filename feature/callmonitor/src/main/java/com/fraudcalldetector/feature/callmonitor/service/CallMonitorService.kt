package com.fraudcalldetector.feature.callmonitor.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.fraudcalldetector.domain.model.MonitoringEvent
import com.fraudcalldetector.domain.model.MonitoringStatus
import com.fraudcalldetector.feature.callmonitor.CallMonitoringManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * Foreground service that owns the lifecycle of [CallMonitoringManager].
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * Architecture notes
 * ─────────────────────────────────────────────────────────────────────────────
 *  • The service is a thin shell: it starts/stops [CallMonitoringManager] and
 *    keeps a foreground notification current.
 *  • Business logic (state machine, persistence, Flow) lives entirely in
 *    [CallMonitoringManager] and is testable without a running Service.
 *  • Bound clients receive [LocalBinder] and can access the manager directly
 *    to observe [events] and [status] Flows — no broadcast needed.
 *  • The service uses START_STICKY so the OS restarts it after process death.
 *
 * Start / Stop
 * ─────────────────────────────────────────────────────────────────────────────
 *  Start : context.startForegroundService(CallMonitorService.startIntent(context))
 *  Stop  : context.startService(CallMonitorService.stopIntent(context))
 *       or: context.stopService(Intent(context, CallMonitorService::class.java))
 */
@AndroidEntryPoint
class CallMonitorService : Service() {

    @Inject lateinit var monitoringManager: CallMonitoringManager

    private val serviceScope  = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var eventJob      : Job? = null
    private val localBinder   = LocalBinder()

    // ─────────────────────────────────────────────────────────────────────────
    // Binder for bound clients
    // ─────────────────────────────────────────────────────────────────────────

    /** Allows bound components to reach [CallMonitoringManager] directly. */
    inner class LocalBinder : Binder() {
        val manager: CallMonitoringManager get() = monitoringManager
    }

    override fun onBind(intent: Intent?): IBinder = localBinder

    // ─────────────────────────────────────────────────────────────────────────
    // Service lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification(NOTIFICATION_TEXT_IDLE))
        Timber.d("CallMonitorService: onCreate")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startMonitoring()
            ACTION_STOP  -> stopSelf()  // triggers onDestroy → stopMonitoring()
            else         -> startMonitoring() // default: start if not already active
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopMonitoring()
        serviceScope.cancel()
        Timber.d("CallMonitorService: onDestroy")
        super.onDestroy()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Monitoring control
    // ─────────────────────────────────────────────────────────────────────────

    private fun startMonitoring() {
        monitoringManager.startMonitoring()
        observeEvents()
    }

    private fun stopMonitoring() {
        eventJob?.cancel()
        monitoringManager.stopMonitoring()
    }

    /**
     * Subscribe to events and keep the foreground notification in sync.
     * Only the notification text changes here — no logic lives in the service.
     */
    private fun observeEvents() {
        eventJob?.cancel()
        eventJob = serviceScope.launch {
            monitoringManager.events.collect { event ->
                handleEventForNotification(event)
            }
        }
    }

    private fun handleEventForNotification(event: MonitoringEvent) {
        val text = when (event) {
            is MonitoringEvent.Ringing  ->
                "Incoming call from ${event.number.ifEmpty { "unknown" }} — analysing…"
            is MonitoringEvent.Active   ->
                "${if (event.direction.name == "OUTBOUND") "Outgoing" else "Active"} call: ${event.number.ifEmpty { "unknown" }}"
            is MonitoringEvent.Ended,
            is MonitoringEvent.Missed   -> NOTIFICATION_TEXT_IDLE
            MonitoringEvent.MonitoringStopped -> {
                stopSelf()
                return
            }
            else -> return
        }
        updateNotification(text)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Notification helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Call Monitor",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "FraudCall Detector is monitoring calls"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("FraudCall Detector")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Companion
    // ─────────────────────────────────────────────────────────────────────────

    companion object {
        private const val NOTIFICATION_ID        = 1001
        private const val CHANNEL_ID             = "call_monitor_channel"
        private const val NOTIFICATION_TEXT_IDLE = "Monitoring calls for fraud…"

        const val ACTION_START = "com.fraudcalldetector.callmonitor.START"
        const val ACTION_STOP  = "com.fraudcalldetector.callmonitor.STOP"

        /** Intent to start (or resume) monitoring. */
        fun startIntent(context: Context) =
            Intent(context, CallMonitorService::class.java).setAction(ACTION_START)

        /** Intent to gracefully stop monitoring. */
        fun stopIntent(context: Context) =
            Intent(context, CallMonitorService::class.java).setAction(ACTION_STOP)
    }
}
