package com.fraudcalldetector.feature.audiorecorder

import android.content.Context
import com.fraudcalldetector.domain.audio.AudioCaptureException
import com.fraudcalldetector.domain.audio.AudioCaptureSource
import com.fraudcalldetector.domain.model.*
import com.fraudcalldetector.domain.repository.AudioCaptureRepository
import com.fraudcalldetector.domain.repository.CallMonitorRepository
import com.fraudcalldetector.domain.repository.CaptureState
import com.fraudcalldetector.feature.audiorecorder.di.AudioRecorderScope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Central orchestrator for call audio capture.
 *
 * Responsibilities:
 *  1. Subscribe to [CallMonitorRepository.events] to start/stop recording
 *     automatically when a call goes active or ends.
 *  2. Own the chunk-rotation timer: every [AudioCaptureConfig.chunkDurationMs]
 *     ms it stops the current segment, emits an [AudioChunk], and starts the
 *     next segment.
 *  3. Expose [chunks] and [captureState] for downstream modules (STT, UI).
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * Threading
 * ─────────────────────────────────────────────────────────────────────────────
 * [AudioCaptureSource] is not thread-safe; ALL source interactions are
 * confined to [recorderDispatcher] — a single-threaded IO dispatcher.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * Replacing the AI model
 * ─────────────────────────────────────────────────────────────────────────────
 * Collect [chunks] in :feature:stt or :feature:frauddetection.
 * No changes needed here.
 */
@Singleton
class AudioCaptureManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val source              : AudioCaptureSource,
    private val callMonitorRepository: CallMonitorRepository,
    @AudioRecorderScope private val scope: CoroutineScope
) : AudioCaptureRepository {

    // ─────────────────────────────────────────────────────────────────────────
    // Public state
    // ─────────────────────────────────────────────────────────────────────────

    private val _chunks = MutableSharedFlow<AudioChunk>(replay = 0, extraBufferCapacity = 16)
    override val chunks: Flow<AudioChunk> = _chunks.asSharedFlow()

    private val _captureState = MutableStateFlow<CaptureState>(CaptureState.Idle)
    override val captureState: StateFlow<CaptureState> = _captureState.asStateFlow()

    override val isRecording: Boolean get() = source.isRecording

    // ─────────────────────────────────────────────────────────────────────────
    // Internal state
    // ─────────────────────────────────────────────────────────────────────────

    /** Single-threaded dispatcher so MediaRecorder is never accessed concurrently. */
    private val recorderDispatcher = Dispatchers.IO.limitedParallelism(1)

    /** Active sessions, keyed by callId. */
    private val sessions = mutableMapOf<String, MutableCaptureSession>()

    /** The chunk-rotation job for the currently recording call (only one call at a time). */
    private var rotationJob: Job? = null

    // ─────────────────────────────────────────────────────────────────────────
    // Monitoring event subscription (auto start/stop)
    // ─────────────────────────────────────────────────────────────────────────

    init {
        scope.launch {
            callMonitorRepository.events.collect { event ->
                when (event) {
                    is MonitoringEvent.Active  -> startCapture(event.callId)
                    is MonitoringEvent.Ended   -> stopCapture(event.callId)
                    is MonitoringEvent.Missed  -> cancelCapture(event.callId)
                    else                       -> Unit
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // AudioCaptureRepository
    // ─────────────────────────────────────────────────────────────────────────

    override suspend fun startCapture(callId: String, config: AudioCaptureConfig) {
        if (sessions.containsKey(callId)) {
            Timber.w("AudioCaptureManager: startCapture called for already-tracked callId=$callId — ignored")
            return
        }

        Timber.d("AudioCaptureManager: starting capture for callId=$callId")
        _captureState.value = CaptureState.Starting(callId)

        val session = MutableCaptureSession(callId = callId, config = config)
        sessions[callId] = session

        withContext(recorderDispatcher) {
            try {
                val path = segmentPath(config, callId, chunkIndex = 0)
                source.startSegment(path, config)
                session.currentSegmentPath = path
                session.chunkIndex = 0
            } catch (e: AudioCaptureException) {
                sessions.remove(callId)
                val msg = "Failed to start recording: ${e.message}"
                Timber.e(e, "AudioCaptureManager: $msg")
                _captureState.value = CaptureState.Error(callId = callId, message = msg, fatal = true)
                return@withContext
            }
        }

        if (!sessions.containsKey(callId)) return // startup failed

        _captureState.value = CaptureState.Recording(
            callId         = callId,
            chunkIndex     = 0,
            segmentStartMs = System.currentTimeMillis()
        )

        // Start chunk-rotation timer if chunking is enabled
        val chunkMs = config.chunkDurationMs
        if (chunkMs != null && chunkMs > 0) {
            rotationJob = scope.launch {
                rotateChunks(callId, config, chunkMs)
            }
        }
    }

    override suspend fun stopCapture(callId: String): CaptureSession? {
        rotationJob?.cancel()
        rotationJob = null

        val session = sessions[callId] ?: run {
            Timber.w("AudioCaptureManager: stopCapture called for unknown callId=$callId")
            return null
        }

        Timber.d("AudioCaptureManager: stopping capture for callId=$callId")

        // Stop and emit the final chunk
        withContext(recorderDispatcher) {
            try {
                if (source.isRecording) {
                    val result = source.stopSegment()
                    val chunk = AudioChunk(
                        callId     = callId,
                        chunkIndex = session.chunkIndex,
                        filePath   = result.filePath,
                        startTimeMs= result.startTimeMs,
                        endTimeMs  = result.endTimeMs,
                        sizeBytes  = result.sizeBytes,
                        isFinal    = true,
                        config     = session.config
                    )
                    session.addChunk(chunk)
                    _chunks.tryEmit(chunk)
                }
            } catch (e: AudioCaptureException) {
                Timber.e(e, "AudioCaptureManager: error stopping final segment for callId=$callId")
            }
        }

        val completed = session.toImmutable(isComplete = true)
        sessions.remove(callId)
        _captureState.value = CaptureState.Stopped(completed)
        Timber.d("AudioCaptureManager: session complete, ${completed.chunks.size} chunks, callId=$callId")
        return completed
    }

    override suspend fun cancelCapture(callId: String) {
        rotationJob?.cancel()
        rotationJob = null

        val session = sessions.remove(callId) ?: return
        Timber.d("AudioCaptureManager: cancelling capture for callId=$callId")

        withContext(recorderDispatcher) {
            source.cancelSegment()
        }

        // Delete all chunk files written so far
        session.chunks.forEach { File(it.filePath).delete() }
        session.currentSegmentPath?.let { File(it).delete() }

        _captureState.value = CaptureState.Idle
    }

    override fun getActiveSession(callId: String): CaptureSession? =
        sessions[callId]?.toImmutable()

    // ─────────────────────────────────────────────────────────────────────────
    // Chunk rotation
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Loops indefinitely, stopping the current segment after [chunkMs] ms,
     * emitting the resulting [AudioChunk], then immediately starting the next.
     * Cancelled when [stopCapture] or [cancelCapture] is called.
     */
    private suspend fun rotateChunks(callId: String, config: AudioCaptureConfig, chunkMs: Long) {
        while (currentCoroutineContext().isActive) {
            delay(chunkMs)

            val session = sessions[callId] ?: break

            _captureState.value = CaptureState.Rotating(callId, session.chunkIndex)

            val nextIndex = session.chunkIndex + 1

            withContext(recorderDispatcher) {
                try {
                    // Stop current segment
                    val result = source.stopSegment()
                    val chunk = AudioChunk(
                        callId     = callId,
                        chunkIndex = session.chunkIndex,
                        filePath   = result.filePath,
                        startTimeMs= result.startTimeMs,
                        endTimeMs  = result.endTimeMs,
                        sizeBytes  = result.sizeBytes,
                        isFinal    = false,
                        config     = config
                    )
                    session.addChunk(chunk)
                    _chunks.tryEmit(chunk)

                    // Start next segment
                    val nextPath = segmentPath(config, callId, chunkIndex = nextIndex)
                    source.startSegment(nextPath, config)
                    session.currentSegmentPath = nextPath
                    session.chunkIndex = nextIndex
                } catch (e: AudioCaptureException) {
                    val msg = "Chunk rotation failed at index ${session.chunkIndex}: ${e.message}"
                    Timber.e(e, "AudioCaptureManager: $msg")
                    _captureState.value = CaptureState.Error(callId = callId, message = msg, fatal = false)
                    // Non-fatal: attempt to keep going next iteration
                }
            }

            if (sessions.containsKey(callId)) {
                _captureState.value = CaptureState.Recording(
                    callId         = callId,
                    chunkIndex     = nextIndex,
                    segmentStartMs = System.currentTimeMillis()
                )
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun segmentPath(config: AudioCaptureConfig, callId: String, chunkIndex: Int): String {
        val ext = when (config.outputFormat) {
            AudioOutputFormat.MPEG_4    -> "mp4"
            AudioOutputFormat.THREE_GPP -> "3gp"
            AudioOutputFormat.AAC_ADTS  -> "aac"
            AudioOutputFormat.RAW_AMR   -> "amr"
        }
        // Resolve against app-internal filesDir so the path is always absolute
        // and the MediaRecorder can write to it without WRITE_EXTERNAL_STORAGE.
        val dir = File(context.filesDir, config.storageSubDir).also { it.mkdirs() }
        return File(dir, "call_${callId}_chunk_$chunkIndex.$ext").absolutePath
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Internal mutable session
    // ─────────────────────────────────────────────────────────────────────────

    private class MutableCaptureSession(
        val callId          : String,
        val config          : AudioCaptureConfig,
        val sessionStart    : Long             = System.currentTimeMillis(),
        var chunkIndex      : Int              = 0,
        var currentSegmentPath: String?        = null,
        private val _chunks : MutableList<AudioChunk> = mutableListOf()
    ) {
        val chunks: List<AudioChunk> get() = _chunks.toList()

        fun addChunk(chunk: AudioChunk) { _chunks += chunk }

        fun toImmutable(isComplete: Boolean = false): CaptureSession = CaptureSession(
            callId       = callId,
            config       = config,
            sessionStart = sessionStart,
            sessionEnd   = if (isComplete) System.currentTimeMillis() else null,
            chunks       = _chunks.toList(),
            isComplete   = isComplete
        )
    }
}
