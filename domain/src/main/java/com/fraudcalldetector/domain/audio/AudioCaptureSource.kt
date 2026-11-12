package com.fraudcalldetector.domain.audio

import com.fraudcalldetector.domain.model.AudioCaptureConfig
import com.fraudcalldetector.domain.model.AudioChunk
import kotlinx.coroutines.flow.Flow

/**
 * Low-level audio source abstraction.
 *
 * An [AudioCaptureSource] knows how to capture one file segment and hand it back
 * as an [AudioChunk].  It has no knowledge of call-IDs, chunk indices, persistence,
 * or the chunk-rotation timer — those concerns live in [AudioCaptureManager].
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * Implementations
 * ─────────────────────────────────────────────────────────────────────────────
 *  • [MediaRecorderAudioSource] — real device microphone via MediaRecorder
 *  • [MockAudioSource]          — deterministic fake for unit tests / CI
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * Threading contract
 * ─────────────────────────────────────────────────────────────────────────────
 *  • [startSegment] / [stopSegment] MUST be called from the same thread / coroutine.
 *  • [isRecording] MAY be polled from any thread.
 */
interface AudioCaptureSource {

    /**
     * True while a segment is actively being recorded.
     * Thread-safe; backed by an AtomicBoolean in every implementation.
     */
    val isRecording: Boolean

    /**
     * Start capturing audio to [outputPath].
     *
     * @param outputPath Absolute path of the file to write.  The caller is
     *                   responsible for creating parent directories.
     * @param config     Recording parameters (codec, bitrate, sample rate, etc.).
     * @throws [AudioCaptureException] if the source fails to start
     *         (e.g. permission denied, hardware unavailable).
     */
    @Throws(AudioCaptureException::class)
    fun startSegment(outputPath: String, config: AudioCaptureConfig)

    /**
     * Stop recording the current segment and flush the file.
     *
     * @return The [AudioChunk] describing the completed segment.
     * @throws [AudioCaptureException] if stop fails (e.g. file system error).
     */
    @Throws(AudioCaptureException::class)
    fun stopSegment(): SegmentResult

    /**
     * Abort the current segment without completing the file.
     * The partial file is deleted.  No-op if not currently recording.
     */
    fun cancelSegment()

    /**
     * Release all native/hardware resources held by this source.
     * Must be called when the source is no longer needed.
     * After this call the source must not be reused.
     */
    fun release()
}

/**
 * Result returned by [AudioCaptureSource.stopSegment].
 */
data class SegmentResult(
    /** Absolute path to the completed file. */
    val filePath   : String,
    /** Epoch ms at the start of this segment. */
    val startTimeMs: Long,
    /** Epoch ms at the end of this segment. */
    val endTimeMs  : Long,
    /** File size in bytes. */
    val sizeBytes  : Long
)

/**
 * Thrown by [AudioCaptureSource] when a hardware/OS error occurs.
 */
class AudioCaptureException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)
