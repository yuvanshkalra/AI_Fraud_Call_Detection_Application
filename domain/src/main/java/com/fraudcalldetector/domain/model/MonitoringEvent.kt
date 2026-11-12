package com.fraudcalldetector.domain.model

/**
 * Represents a raw phone-state transition emitted from the telephony layer.
 *
 * This is the in-process event bus payload consumed by [CallMonitoringManager].
 * It is intentionally decoupled from [CallEvent] (the persisted domain entity)
 * so audio capture and fraud detection modules can subscribe independently.
 */
sealed class MonitoringEvent {

    /**
     * An inbound call is ringing. No answer yet.
     * @param callId     Stable ID minted at first ring; constant for the lifetime of this call.
     * @param number     Caller phone number (may be empty on some devices/carriers).
     * @param ringTime   Epoch ms when RINGING was first observed.
     */
    data class Ringing(
        val callId  : String,
        val number  : String,
        val ringTime: Long = System.currentTimeMillis()
    ) : MonitoringEvent()

    /**
     * A call became active (answered, or an outgoing call was connected).
     * @param callId      Matches the [Ringing.callId] for inbound; new UUID for outbound.
     * @param number      Dialled / answered number.
     * @param direction   INBOUND or OUTBOUND.
     * @param connectTime Epoch ms when the call was answered.
     */
    data class Active(
        val callId     : String,
        val number     : String,
        val direction  : CallDirection,
        val connectTime: Long = System.currentTimeMillis()
    ) : MonitoringEvent()

    /**
     * The call has ended (hung up by either party).
     * @param callId      Matches the ID used in [Ringing] / [Active].
     * @param number      Phone number involved.
     * @param endTime     Epoch ms when the call ended.
     * @param durationMs  How long the call was active (0 if it was never answered).
     */
    data class Ended(
        val callId    : String,
        val number    : String,
        val endTime   : Long = System.currentTimeMillis(),
        val durationMs: Long = 0L
    ) : MonitoringEvent()

    /**
     * An inbound call was never answered — RINGING → IDLE transition without OFFHOOK.
     * @param callId  Matches [Ringing.callId].
     * @param number  Missed caller.
     */
    data class Missed(
        val callId: String,
        val number: String
    ) : MonitoringEvent()

    /**
     * The telephony listener was (re-)registered successfully.
     */
    object MonitoringStarted : MonitoringEvent()

    /**
     * The telephony listener was unregistered (service stopped or permission revoked).
     */
    object MonitoringStopped : MonitoringEvent()

    /**
     * A required permission is missing; monitoring cannot start.
     * @param permission  The Manifest permission string that is absent.
     */
    data class PermissionDenied(val permission: String) : MonitoringEvent()
}

/**
 * Overall monitoring status, suitable for UI observation.
 */
enum class MonitoringStatus {
    IDLE,          // service not running
    STARTING,      // service created, listener not yet registered
    ACTIVE,        // listener registered, watching for calls
    IN_CALL,       // a call is currently active
    STOPPING,      // service being shut down
    PERMISSION_DENIED // required permission missing
}
