package com.fraudcalldetector.feature.callmonitor.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import com.fraudcalldetector.feature.callmonitor.service.CallMonitorService
import timber.log.Timber

/**
 * Boot-completion and telephony trigger receiver.
 *
 * Responsibilities
 * ────────────────
 *  1. Start [CallMonitorService] when the device boots (BOOT_COMPLETED).
 *  2. Wake [CallMonitorService] on PHONE_STATE changes so it is alive
 *     before TelephonyCallback fires (covers edge-cases on some OEMs).
 *
 * What this receiver does NOT do
 * ────────────────────────────────
 *  • It does NOT process phone-number extras or call direction.
 *    All state-machine logic is in [CallMonitoringManager].
 *  • It does NOT use ACTION_NEW_OUTGOING_CALL — that broadcast was removed
 *    in Android 10. Outgoing calls are detected via CALL_STATE_OFFHOOK in
 *    TelephonyStateListener + CallLogResolver.
 *
 * Note: @AndroidEntryPoint is intentionally NOT used here.
 * BroadcastReceivers that use @AndroidEntryPoint must never do async work,
 * and this receiver only starts a service — no DI injection is needed.
 */
class CallStateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED -> {
                Timber.d("CallStateReceiver: device boot — starting CallMonitorService")
                context.startForegroundService(CallMonitorService.startIntent(context))
            }

            TelephonyManager.ACTION_PHONE_STATE_CHANGED -> {
                // Ensure the service is running before the TelephonyCallback fires.
                // On most devices the service is already alive (START_STICKY), but
                // this handles cold-start and OEM process kill scenarios.
                val phoneState = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
                Timber.d("CallStateReceiver: PHONE_STATE=$phoneState — ensuring service is alive")
                context.startForegroundService(CallMonitorService.startIntent(context))
            }
        }
    }
}
