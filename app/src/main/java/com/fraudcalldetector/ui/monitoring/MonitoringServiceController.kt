package com.fraudcalldetector.ui.monitoring

import android.content.Context
import androidx.core.content.ContextCompat
import com.fraudcalldetector.feature.callmonitor.service.CallMonitorService

/**
 * Pure-function helpers for starting/stopping [CallMonitorService].
 *
 * Extracted from [MainActivity] so it's easy to call from anywhere that
 * has a [Context], and testable without inflating an Activity.
 */
object MonitoringServiceController {

    /**
     * Start the foreground monitoring service.
     * Safe to call from any thread; startForegroundService is thread-safe.
     */
    fun start(context: Context) {
        ContextCompat.startForegroundService(
            context,
            CallMonitorService.startIntent(context)
        )
    }

    /**
     * Request a graceful shutdown of the monitoring service.
     */
    fun stop(context: Context) {
        context.startService(CallMonitorService.stopIntent(context))
    }
}
