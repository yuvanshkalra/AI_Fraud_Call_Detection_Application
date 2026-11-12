package com.fraudcalldetector.feature.stt

import com.fraudcalldetector.domain.model.AudioChunk
import com.fraudcalldetector.domain.model.TranscriptResult
import com.fraudcalldetector.domain.repository.AudioCaptureRepository
import com.fraudcalldetector.domain.repository.SttRepository
import com.fraudcalldetector.domain.stt.*
import com.fraudcalldetector.feature.stt.di.SttManagerScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * SttManagerImpl
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * Central STT orchestrator. Consumes [AudioChunk]s from [AudioCaptureRepository]
 * and drives [SttEngine] for each chunk. Aggregates results and persists them
 * via [SttRepository].
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * Threading
 * ─────────────────────────────────────────────────────────────────────────────
 *  • Chunk subscription runs on [Dispatchers.Default] (the manager scope).
 *  • Each chunk transcription is launched as a child coroutine — they run
 *    sequentially per call-session to preserve transcript order.
 *  • [SttEngine] methods are called from within those coroutines; engines
 *    that do network I/O switch dispatchers internally.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * Session lifecycle
 * ─────────────────────────────────────────────────────────────────────────────
 *  • A "session" is keyed by callId.
 *  • The session accumulates text chunks until an [AudioChunk.isFinal] = true
 *    chunk is processed, then writes the aggregated [TranscriptResult] to the DB.
 *  • [resetSession] clears in-memory state (called after the call ends).
 */
@Singleton
class SttManagerImpl @Inject constructor(
    private val engine              : SttEngine,
    private val sttRepository       : SttRepository,
    private val audioCaptureRepository: AudioCaptureRepository,
    @SttManagerScope private val scope: CoroutineScope
) : SttManager {

    // ─────────────────────────────────────────────────────────────────────────
    // Public state
    // ─────────────────────────────────────────────────────────────────────────

    private val _state = MutableStateFlow<SttManagerState>(SttManagerState.Idle)
    override val state: StateFlow<SttManagerState> = _state.asStateFlow()

    private val _streamEvents = MutableSharedFlow<SttStreamEvent>(
        replay = 0,
        extraBufferCapacity = 32
    )
    override val streamEvents: Flow<SttStreamEvent> = _streamEvents.asSharedFlow()

    private val _liveTranscript = MutableStateFlow("")
    override val liveTranscript: StateFlow<String> = _liveTranscript.asStateFlow()

    // ─────────────────────────────────────────────────────────────────────────
    // In-memory session accumulator
    // ─────────────────────────────────────────────────────────────────────────

    /** Mutable session data — thread-confined to the manager scope. */
    private data class TranscriptSession(
        val callId: String,
        val chunks: MutableList<SttEngineResult> = mutableListOf()
    ) {
        val aggregatedText: String
            get() = chunks.joinToString(" ") { it.fullText.trim() }.trim()
    }

    private val sessions = mutableMapOf<String, TranscriptSession>()

    /** Per-call sequential transcription queue. */
    private val sessionJobs = mutableMapOf<String, Job>()

    // ─────────────────────────────────────────────────────────────────────────
    // Auto-subscription to audio chunks
    // ─────────────────────────────────────────────────────────────────────────

    init {
        scope.launch {
            audioCaptureRepository.chunks.collect { chunk ->
                Timber.d("SttManager: received chunk callId=${chunk.callId} index=${chunk.chunkIndex} isFinal=${chunk.isFinal}")
                enqueueChunk(chunk)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SttManager interface
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Enqueue [chunk] for sequential transcription within its call session.
     * Uses a [Job] chain so chunks are processed in order even if they arrive quickly.
     */
    override suspend fun transcribeChunk(chunk: AudioChunk, config: SttEngineConfig) {
        enqueueChunk(chunk, config)
    }

    override fun resetSession(callId: String) {
        sessions.remove(callId)
        sessionJobs.remove(callId)?.cancel()
        Timber.d("SttManager: session reset for callId=$callId")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Internal orchestration
    // ─────────────────────────────────────────────────────────────────────────

    private fun enqueueChunk(
        chunk : AudioChunk,
        config: SttEngineConfig = SttEngineConfig.DEFAULT
    ) {
        val callId = chunk.callId

        // Ensure a session exists for this call
        val session = sessions.getOrPut(callId) { TranscriptSession(callId) }

        // Chain this chunk's job after the previous one so order is preserved
        val previous = sessionJobs[callId]
        val newJob = scope.launch {
            previous?.join()   // wait for previous chunk to finish
            processChunk(chunk, session, config)
        }
        sessionJobs[callId] = newJob
    }

    private suspend fun processChunk(
        chunk  : AudioChunk,
        session: TranscriptSession,
        config : SttEngineConfig
    ) {
        val callId = chunk.callId

        _state.value = SttManagerState.Transcribing(callId, chunk.chunkIndex)

        try {
            // ── Stream events from the engine ─────────────────────────────
            engine.transcribeStreaming(chunk.filePath, config)
                .catch { e ->
                    val ex = e as? SttEngineException
                        ?: SttEngineException("Streaming error: ${e.message}", e)
                    _streamEvents.tryEmit(SttStreamEvent.Error(ex))
                    _state.value = SttManagerState.ChunkError(callId, chunk.chunkIndex, ex.message ?: "")
                }
                .collect { event ->
                    // Relay every event to the live UI stream
                    _streamEvents.tryEmit(event)

                    when (event) {
                        is SttStreamEvent.Partial -> {
                            _state.value = SttManagerState.StreamingPartial(callId, event.text)
                            // Append partial to live transcript (overwrite current chunk's contribution)
                            updateLiveTranscript(session, partialOverride = event.text)
                        }

                        is SttStreamEvent.SegmentFinalised -> {
                            // Update live transcript with the stable segment text
                            updateLiveTranscript(session, partialOverride = null)
                        }

                        is SttStreamEvent.Final -> {
                            val result = event.result.copy(chunkIndex = chunk.chunkIndex, isFinal = chunk.isFinal)
                            session.chunks += result
                            _state.value = SttManagerState.ChunkComplete(callId, chunk.chunkIndex, result)

                            // Update live transcript with the final text for this chunk
                            _liveTranscript.value = session.aggregatedText

                            Timber.d("SttManager: chunk ${chunk.chunkIndex} done, " +
                                     "${result.words.size} words, session text length=${session.aggregatedText.length}")
                        }

                        is SttStreamEvent.Error -> {
                            _state.value = SttManagerState.ChunkError(
                                callId     = callId,
                                chunkIndex = chunk.chunkIndex,
                                message    = event.exception.message ?: "Unknown error"
                            )
                        }
                    }
                }

        } catch (e: Exception) {
            Timber.e(e, "SttManager: unexpected error for chunk ${chunk.chunkIndex}")
            _state.value = SttManagerState.ChunkError(callId, chunk.chunkIndex, e.message ?: "")
        }

        // ── Persist and finalise after the last chunk ─────────────────────
        if (chunk.isFinal) {
            persistSession(session)
        }
    }

    private fun updateLiveTranscript(session: TranscriptSession, partialOverride: String?) {
        val base = session.aggregatedText
        _liveTranscript.value = if (partialOverride != null) {
            if (base.isBlank()) partialOverride else "$base $partialOverride"
        } else {
            base
        }
    }

    private suspend fun persistSession(session: TranscriptSession) {
        val callId    = session.callId
        val fullText  = session.aggregatedText

        val result = TranscriptResult(
            callEventId = callId,
            fullText    = fullText,
            segments    = emptyList()   // word-level stored in-memory via SttEngineResult
        )

        runCatching {
            sttRepository.saveTranscript(result)
            Timber.d("SttManager: persisted transcript for callId=$callId, ${fullText.length} chars")
        }.onFailure {
            Timber.e(it, "SttManager: failed to persist transcript for callId=$callId")
        }

        _state.value = SttManagerState.CallComplete(callId, fullText)
        Timber.i("SttManager: call $callId complete, total text length=${fullText.length}")
    }
}
