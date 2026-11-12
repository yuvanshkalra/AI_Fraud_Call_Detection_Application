package com.fraudcalldetector.domain.stt

import com.fraudcalldetector.domain.model.AudioChunk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * High-level STT orchestrator contract.
 *
 * [SttManager] sits above [SttEngine] and handles:
 *  • Auto-subscription to [AudioCaptureRepository.chunks]
 *  • Per-chunk transcription (sequential or parallel)
 *  • Live streaming event re-emission for the active-call UI
 *  • Aggregation and persistence of results via [SttRepository]
 *
 * Other modules (fraud detection, UI) depend only on this interface.
 * Implemented by [com.fraudcalldetector.feature.stt.SttManagerImpl].
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * Data flow
 * ─────────────────────────────────────────────────────────────────────────────
 *
 *   AudioChunk (from AudioCaptureRepository.chunks)
 *       │
 *       ▼
 *   SttManager receives chunk, submits to SttEngine
 *       │
 *       ├── emits SttStreamEvent on [streamEvents]   ──► ActiveCallViewModel
 *       │
 *       └── on Final: aggregates + persists via SttRepository
 *                      └── emits SttManagerState update on [state]
 */
interface SttManager {

    /** Observable state of the STT subsystem. */
    val state: StateFlow<SttManagerState>

    /**
     * Live stream of [SttStreamEvent]s for the call currently being transcribed.
     *
     * Replay = 0; late subscribers only see future events.
     * Consumers should collect this in the ActiveCallViewModel.
     */
    val streamEvents: Flow<SttStreamEvent>

    /**
     * Aggregated transcript text for the current call, updated incrementally
     * as each chunk is processed. Starts empty; resets when a new call starts.
     *
     * Backed by a [StateFlow] so the UI can `collectAsState()` directly.
     */
    val liveTranscript: StateFlow<String>

    /**
     * Manually request transcription of [chunk] with [config].
     *
     * Normally auto-triggered by the chunk flow subscription;
     * exposed here for manual one-shot calls (e.g., retrying a failed chunk).
     */
    suspend fun transcribeChunk(
        chunk : AudioChunk,
        config: SttEngineConfig = SttEngineConfig.DEFAULT
    )

    /**
     * Clear accumulated state for [callId].
     * Called by the monitoring layer when a call ends and the result is persisted.
     */
    fun resetSession(callId: String)
}

// ─────────────────────────────────────────────────────────────────────────────
// State
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Observable state of the STT subsystem.
 */
sealed class SttManagerState {

    /** No transcription is in progress. */
    object Idle : SttManagerState()

    /**
     * A chunk is currently being transcribed.
     *
     * @param callId     Call being transcribed.
     * @param chunkIndex Which chunk is being processed.
     */
    data class Transcribing(
        val callId    : String,
        val chunkIndex: Int
    ) : SttManagerState()

    /**
     * A streaming partial result arrived — shown in the live transcript UI.
     *
     * @param callId      The call.
     * @param partialText Current best-guess text for the chunk being transcribed.
     */
    data class StreamingPartial(
        val callId     : String,
        val partialText: String
    ) : SttManagerState()

    /**
     * A chunk was fully transcribed and the result persisted.
     *
     * @param callId      The call.
     * @param chunkIndex  Which chunk was completed.
     * @param result      The [SttEngineResult] for that chunk.
     */
    data class ChunkComplete(
        val callId    : String,
        val chunkIndex: Int,
        val result    : SttEngineResult
    ) : SttManagerState()

    /**
     * All chunks for [callId] have been transcribed (isFinal chunk processed).
     *
     * @param callId       The completed call.
     * @param fullTranscript Concatenated text of all chunks.
     */
    data class CallComplete(
        val callId        : String,
        val fullTranscript: String
    ) : SttManagerState()

    /**
     * A non-fatal error on one chunk — processing continues for future chunks.
     *
     * @param callId     Affected call.
     * @param chunkIndex Chunk that failed.
     * @param message    Human-readable reason.
     */
    data class ChunkError(
        val callId    : String,
        val chunkIndex: Int,
        val message   : String
    ) : SttManagerState()
}
