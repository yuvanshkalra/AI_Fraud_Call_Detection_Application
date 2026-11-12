package com.fraudcalldetector.feature.callmonitor.telephony

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import timber.log.Timber
import java.util.concurrent.Executor

/**
 * Unified telephony listener that bridges:
 *   • API 31+  → [TelephonyCallback]   (non-deprecated)
 *   • API 26–30 → [PhoneStateListener] (deprecated but required)
 *
 * Delegates all state changes to [PhoneStateDelegate] so call-state
 * business logic lives in one place regardless of API level.
 */
internal class TelephonyStateListener(
    private val context : Context,
    private val delegate: PhoneStateDelegate
) {
    private val telephonyManager: TelephonyManager by lazy {
        context.getSystemService(TelephonyManager::class.java)
    }

    // API 31+ callback (kept so we can unregister it)
    private var modernCallback: TelephonyCallback? = null

    // Legacy listener (API 26–30)
    @Suppress("DEPRECATION")
    private var legacyListener: PhoneStateListener? = null

    // ─────────────────────────────────────────────────────────────────────────
    // Register
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Register the appropriate listener for the current API level.
     * Returns false if READ_PHONE_STATE permission is missing.
     */
    fun register(): Boolean {
        if (!hasPhoneStatePermission()) {
            Timber.w("READ_PHONE_STATE not granted — cannot register telephony listener")
            return false
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            registerModern()
        } else {
            registerLegacy()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Unregister
    // ─────────────────────────────────────────────────────────────────────────

    fun unregister() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            modernCallback?.let {
                telephonyManager.unregisterTelephonyCallback(it)
                Timber.d("TelephonyCallback unregistered")
            }
            modernCallback = null
        } else {
            @Suppress("DEPRECATION")
            legacyListener?.let {
                telephonyManager.listen(it, PhoneStateListener.LISTEN_NONE)
                Timber.d("PhoneStateListener unregistered")
            }
            legacyListener = null
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Modern path (API 31+)
    // ─────────────────────────────────────────────────────────────────────────

    @RequiresApi(Build.VERSION_CODES.S)
    private fun registerModern(): Boolean {
        val executor: Executor = context.mainExecutor
        val callback = ModernTelephonyCallback(delegate).also { modernCallback = it }
        telephonyManager.registerTelephonyCallback(executor, callback)
        Timber.d("TelephonyCallback registered (API 31+)")
        return true
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Legacy path (API 26–30)
    // ─────────────────────────────────────────────────────────────────────────

    @Suppress("DEPRECATION")
    private fun registerLegacy(): Boolean {
        val listener = LegacyPhoneStateListener(delegate).also { legacyListener = it }
        telephonyManager.listen(
            listener,
            PhoneStateListener.LISTEN_CALL_STATE
        )
        Timber.d("PhoneStateListener registered (legacy API)")
        return true
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun hasPhoneStatePermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED
}

// ─────────────────────────────────────────────────────────────────────────────
// Delegate interface — single callback surface for both API paths
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Receives normalised phone-state events from either API path.
 * Implemented by [CallMonitoringManager].
 */
internal interface PhoneStateDelegate {
    fun onCallStateChanged(state: Int, number: String)
}

// ─────────────────────────────────────────────────────────────────────────────
// Modern callback implementation (API 31+)
// ─────────────────────────────────────────────────────────────────────────────

@RequiresApi(Build.VERSION_CODES.S)
private class ModernTelephonyCallback(
    private val delegate: PhoneStateDelegate
) : TelephonyCallback(), TelephonyCallback.CallStateListener {

    override fun onCallStateChanged(state: Int) {
        // API 31+ does not provide the number in the callback for privacy reasons.
        // The number is obtained from CallLog after call ends, or from outgoing call intent.
        Timber.d("TelephonyCallback.onCallStateChanged state=$state")
        delegate.onCallStateChanged(state, "")
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Legacy listener implementation (API 26–30)
// ─────────────────────────────────────────────────────────────────────────────

@Suppress("DEPRECATION")
private class LegacyPhoneStateListener(
    private val delegate: PhoneStateDelegate
) : PhoneStateListener() {

    override fun onCallStateChanged(state: Int, phoneNumber: String?) {
        Timber.d("PhoneStateListener.onCallStateChanged state=$state number=$phoneNumber")
        delegate.onCallStateChanged(state, phoneNumber ?: "")
    }
}
