package com.fraudcalldetector.feature.callmonitor

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.fraudcalldetector.domain.model.*
import com.fraudcalldetector.domain.repository.CallMonitorRepository
import com.fraudcalldetector.domain.repository.CallRepository
import com.fraudcalldetector.feature.callmonitor.di.ApplicationScope
import com.fraudcalldetector.feature.callmonitor.telephony.CallLogResolver
import com.fraudcalldetector.feature.callmonitor.telephony.PhoneStateDelegate
import com.fraudcalldetector.feature.callmonitor.telephony.TelephonyStateListener
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * CallMonitoringManager
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * Single source of truth for call-monitoring lifecycle within the process.
 *
 * Responsibilities
 * ────────────────
 *  1. Register / unregister the telephony listener (API-level aware).
 *  2. Run the call-state machine:  IDLE → RINGING → ACTIVE → ENDED / MISSED
 *  3. Emit [MonitoringEvent] on [events] – hot SharedFlow, replay = 1.
 *  4. Update [status] StateFlow for UI observation.
 *  5. Persist [CallEvent] domain entities via [CallRepository].
 *  6. Check required permissions before starting.
 *
 * Audio capture contract
 * ──────────────────────
 * This class is intentionally unaware of audio.  It emits [MonitoringEvent.Active]
 * (call started) and [MonitoringEvent.Ended] / [MonitoringEvent.Missed] (call over)
 * so AudioRecorderManager can subscribe and start/stop independently.
 *
 * Threading
 * ─────────
 *  • Public API is main-thread safe.
 *  • Telephony callbacks arrive on the main thread (as required by the system).
 *  • Persistence dispatches to [Dispatchers.IO] via the manager's own scope.
 */
@Singleton
class CallMonitoringManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val callRepository             : CallRepository,
    @ApplicationScope private val scope    : CoroutineScope
) : CallMonitorRepository, PhoneStateDelegate {

    // ─────────────────────────────────────────────────────────────────────────
    // Public Flow surface
    // ─────────────────────────────────────────────────────────────────────────

    private val _events = MutableSharedFlow<MonitoringEvent>(
        replay        = 1,
        extraBufferCapacity = 16
    )
    override val events: Flow<MonitoringEvent> = _events.asSharedFlow()

    private val _status = MutableStateFlow(MonitoringStatus.IDLE)
    override val status: StateFlow<MonitoringStatus> = _status.asStateFlow()

    // ─────────────────────────────────────────────────────────────────────────
    // Call state machine
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Internal state machine node.
     * Thread-confined to the main thread (telephony callbacks always arrive there).
     */
    private sealed class CallTrack {
        /** No call in progress. */
        object Idle : CallTrack()

        /** Inbound RINGING, not yet answered. */
        data class Ringing(
            val callId   : String,
            val number   : String,
            val ringStart: Long
        ) : CallTrack()

        /** Call is live (OFFHOOK). */
        data class Live(
            val callId     : String,
            val number     : String,
            val direction  : CallDirection,
            val connectTime: Long
        ) : CallTrack()
    }

    @Volatile private var track: CallTrack = CallTrack.Idle

    // ─────────────────────────────────────────────────────────────────────────
    // Telephony listener
    // ─────────────────────────────────────────────────────────────────────────

    private val telephonyListener = TelephonyStateListener(context, this)
    private val isListenerRegistered = AtomicBoolean(false)

    // ─────────────────────────────────────────────────────────────────────────
    // CallMonitorRepository — public API
    // ─────────────────────────────────────────────────────────────────────────

    override fun startMonitoring() {
        if (isListenerRegistered.get()) {
            Timber.d("CallMonitoringManager: already active, ignoring startMonitoring()")
            return
        }

        val missingPermission = checkRequiredPermissions()
        if (missingPermission != null) {
            Timber.w("Missing permission: $missingPermission")
            _status.value = MonitoringStatus.PERMISSION_DENIED
            emit(MonitoringEvent.PermissionDenied(missingPermission))
            return
        }

        _status.value = MonitoringStatus.STARTING
        val registered = telephonyListener.register()
        if (registered) {
            isListenerRegistered.set(true)
            _status.value = MonitoringStatus.ACTIVE
            emit(MonitoringEvent.MonitoringStarted)
            Timber.i("CallMonitoringManager: monitoring started")
        } else {
            _status.value = MonitoringStatus.PERMISSION_DENIED
            emit(MonitoringEvent.PermissionDenied(Manifest.permission.READ_PHONE_STATE))
        }
    }

    override fun stopMonitoring() {
        if (!isListenerRegistered.getAndSet(false)) return
        _status.value = MonitoringStatus.STOPPING
        telephonyListener.unregister()
        track = CallTrack.Idle
        _status.value = MonitoringStatus.IDLE
        emit(MonitoringEvent.MonitoringStopped)
        Timber.i("CallMonitoringManager: monitoring stopped")
    }

    override val isCallInProgress: Boolean
        get() = track !is CallTrack.Idle

    override val currentCallNumber: String?
        get() = when (val t = track) {
            is CallTrack.Ringing -> t.number
            is CallTrack.Live    -> t.number
            else                 -> null
        }

    override val currentCallId: String?
        get() = when (val t = track) {
            is CallTrack.Ringing -> t.callId
            is CallTrack.Live    -> t.callId
            else                 -> null
        }

    // ─────────────────────────────────────────────────────────────────────────
    // PhoneStateDelegate — telephony callbacks
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Called on the main thread by [TelephonyStateListener] for both API paths.
     *
     * @param state  [TelephonyManager.CALL_STATE_RINGING] / _OFFHOOK / _IDLE
     * @param number Phone number string (may be empty on API 31+ inbound,
     *               filled for outgoing via [CallLogResolver]).
     */
    override fun onCallStateChanged(state: Int, number: String) {
        Timber.d("onCallStateChanged(state=$state, number='$number') track=$track")
        when (state) {
            TelephonyManager.CALL_STATE_RINGING  -> handleRinging(number)
            TelephonyManager.CALL_STATE_OFFHOOK  -> handleOffhook(number)
            TelephonyManager.CALL_STATE_IDLE     -> handleIdle()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // State machine transitions
    // ─────────────────────────────────────────────────────────────────────────

    private fun handleRinging(rawNumber: String) {
        // Only start a new ringing track when currently idle.
        // Re-entrant RINGING (unlikely but defensive) is ignored.
        if (track !is CallTrack.Idle) {
            Timber.d("Ignoring RINGING: already tracking $track")
            return
        }

        val callId  = UUID.randomUUID().toString()
        val now     = System.currentTimeMillis()
        val number  = rawNumber.ifEmpty {
            // API 31+: number is withheld from callback; attempt call-log lookup.
            CallLogResolver.getMostRecentNumber(context) ?: ""
        }

        track = CallTrack.Ringing(callId, number, now)
        _status.value = MonitoringStatus.IN_CALL

        val event = MonitoringEvent.Ringing(callId, number, now)
        emit(event)

        // Persist placeholder CallEvent for immediate UI update
        persist {
            callRepository.insertCall(
                CallEvent(
                    id        = callId,
                    phoneNumber = number,
                    callerName = null,
                    startTime = now,
                    endTime   = null,
                    direction = CallDirection.INBOUND,
                    state     = CallState.RINGING
                )
            )
        }
    }

    private fun handleOffhook(rawNumber: String) {
        val now = System.currentTimeMillis()

        when (val currentTrack = track) {
            is CallTrack.Ringing -> {
                // Inbound call answered
                val liveTrack = CallTrack.Live(
                    callId      = currentTrack.callId,
                    number      = currentTrack.number.ifEmpty { rawNumber },
                    direction   = CallDirection.INBOUND,
                    connectTime = now
                )
                track = liveTrack

                emit(MonitoringEvent.Active(liveTrack.callId, liveTrack.number, CallDirection.INBOUND, now))

                persist {
                    callRepository.updateCallState(liveTrack.callId, CallState.ACTIVE)
                }
            }

            is CallTrack.Idle -> {
                // Outgoing call: no RINGING precedes OFFHOOK
                val callId = UUID.randomUUID().toString()
                val number = rawNumber.ifEmpty {
                    CallLogResolver.getMostRecentNumber(context) ?: ""
                }
                val liveTrack = CallTrack.Live(callId, number, CallDirection.OUTBOUND, now)
                track = liveTrack
                _status.value = MonitoringStatus.IN_CALL

                emit(MonitoringEvent.Active(callId, number, CallDirection.OUTBOUND, now))

                persist {
                    callRepository.insertCall(
                        CallEvent(
                            id          = callId,
                            phoneNumber = number,
                            callerName  = null,
                            startTime   = now,
                            endTime     = null,
                            direction   = CallDirection.OUTBOUND,
                            state       = CallState.ACTIVE
                        )
                    )
                }
            }

            is CallTrack.Live -> {
                // Already live — can happen with call-waiting; log and ignore.
                Timber.d("OFFHOOK received while already Live — call-waiting scenario?")
            }
        }
    }

    private fun handleIdle() {
        val now = System.currentTimeMillis()

        when (val currentTrack = track) {
            is CallTrack.Ringing -> {
                // Phone went IDLE without OFFHOOK → missed call
                Timber.d("Missed call: ${currentTrack.number}")
                track = CallTrack.Idle
                _status.value = MonitoringStatus.ACTIVE

                emit(MonitoringEvent.Missed(currentTrack.callId, currentTrack.number))

                persist {
                    callRepository.updateCallState(currentTrack.callId, CallState.MISSED)
                }
            }

            is CallTrack.Live -> {
                val durationMs = now - currentTrack.connectTime
                Timber.d("Call ended: ${currentTrack.number} duration=${durationMs}ms")
                track = CallTrack.Idle
                _status.value = MonitoringStatus.ACTIVE

                emit(MonitoringEvent.Ended(currentTrack.callId, currentTrack.number, now, durationMs))

                persist {
                    val existing = callRepository.getCallById(currentTrack.callId)
                    if (existing != null) {
                        callRepository.updateCall(
                            existing.copy(state = CallState.ENDED, endTime = now)
                        )
                    } else {
                        callRepository.updateCallState(currentTrack.callId, CallState.ENDED)
                    }
                }
            }

            is CallTrack.Idle -> {
                // Spurious IDLE event — ignore
                Timber.d("IDLE received while already Idle — ignoring")
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /** Emit on the manager's coroutine scope without blocking the telephony callback. */
    private fun emit(event: MonitoringEvent) {
        scope.launch { _events.emit(event) }
    }

    /** Dispatch a suspend persistence block on IO without blocking. */
    private fun persist(block: suspend () -> Unit) {
        scope.launch(Dispatchers.IO) {
            runCatching { block() }.onFailure { e ->
                Timber.e(e, "CallMonitoringManager: persistence error")
            }
        }
    }

    /**
     * Returns the first missing required permission, or null if all are granted.
     */
    private fun checkRequiredPermissions(): String? {
        val required = listOf(Manifest.permission.READ_PHONE_STATE)
        return required.firstOrNull { permission ->
            ContextCompat.checkSelfPermission(context, permission) !=
                PackageManager.PERMISSION_GRANTED
        }
    }
}
