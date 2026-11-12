package com.fraudcalldetector.domain.repository

import com.fraudcalldetector.domain.model.AudioCaptureConfig
import com.fraudcalldetector.domain.model.AudioChunk
import com.fraudcalldetector.domain.model.CaptureSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * High-level contract for the audio capture subsystem.
 *
 * Implemented by [AudioCaptureManager] in :feature:audiorecorder.
 * All other modules (STT, fraud detection, UI) depend only on this interface.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * Data flow
 * ─────────────────────────────────────────────────────────────────────────────
 *
 *   [MonitoringEvent.Active]
 *         │
 *         ▼
 *   AudioCaptureManager.startCapture(callId, config)
 *         │
 *         ├── records segment (chunkDurationMs)
 *         │
 *         ├── emits AudioChunk on [chunks] Flow  ──► STT module
 *         │                                      ──► FraudDetection module (optional)
 *         │
 *         └── rotates to next segment until [stopCapture] is called
 *
 *   [MonitoringEvent.Ended]
 *         │
 *         ▼
 *   AudioCaptureManager.stopCapture(callId)
 *         │
 *         └── emits final chunk (isFinal = true) ──► STT / FraudDetection
 */
interface AudioCaptureRepository {

    /** Emits every completed [AudioChunk] as it becomes available. Replay = 0. */
    val chunks: Flow<AudioChunk>

    /** Current recording lifecycle state. */
    val captureState: StateFlow<CaptureState>

    /**
     * Start capturing audio for [callId] using [config].
     *
     * If chunking is enabled ([AudioCaptureConfig.chunkDurationMs] != null),
     * a new segment file is started automatically every [chunkDurationMs] ms
     * and an [AudioChunk] is emitted for each completed segment.
     *
     * If [callId] is already being recorded this call is a no-op.
     *
     * @param callId  Stable call identifier from [MonitoringEvent.Active.callId].
     * @param config  Recording parameters; defaults to [AudioCaptureConfig.DEFAULT].
     */
    suspend fun startCapture(callId: String, config: AudioCaptureConfig = AudioCaptureConfig.DEFAULT)

    /**
     * Stop capturing audio for [callId].
     *
     * The in-progress segment is stopped, saved, and emitted as the final
     * [AudioChunk] (with [AudioChunk.isFinal] = true) before the session closes.
     *
     * @return The completed [CaptureSession] summarising all chunks, or null
     *         if [callId] was not being recorded.
     */
    suspend fun stopCapture(callId: String): CaptureSession?

    /**
     * Abort recording without saving any data.
     * All chunk files written so far are deleted.
     */
    suspend fun cancelCapture(callId: String)

    /**
     * Returns the active [CaptureSession] for [callId], or null.
     */
    fun getActiveSession(callId: String): CaptureSession?

    /**
     * True when any call is currently being recorded.
     */
    val isRecording: Boolean
}

/**
 * Observable state of the audio capture system.
 */
sealed class CaptureState {
    /** No session is active. */
    object Idle : CaptureState()

    /**
     * A session is being initialised (source allocated, first segment starting).
     * @param callId The call being set up.
     */
    data class Starting(val callId: String) : CaptureState()

    /**
     * A segment is actively recording.
     * @param callId     Current call.
     * @param chunkIndex Which chunk is being written.
     * @param segmentStartMs Epoch ms when the current segment started.
     */
    data class Recording(
        val callId        : String,
        val chunkIndex    : Int,
        val segmentStartMs: Long
    ) : CaptureState()

    /**
     * The most recent chunk finished rotating; a new segment is starting.
     * UI can show "buffering" during this brief transition.
     */
    data class Rotating(val callId: String, val completedIndex: Int) : CaptureState()

    /**
     * The session stopped cleanly.
     * @param session  Full summary of the completed session.
     */
    data class Stopped(val session: CaptureSession) : CaptureState()

    /**
     * A non-fatal or fatal error occurred.
     * @param callId   Call that was affected (null if startup failed before a call was known).
     * @param message  Human-readable description.
     * @param fatal    If true the session has been aborted; if false recording may continue.
     */
    data class Error(
        val callId : String?,
        val message: String,
        val fatal  : Boolean = false
    ) : CaptureState()
}
