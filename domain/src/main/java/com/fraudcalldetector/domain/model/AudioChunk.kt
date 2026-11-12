package com.fraudcalldetector.domain.model

/**
 * A discrete segment of captured audio from a single call.
 *
 * Emitted by [AudioCaptureManager] on the [chunks] Flow.
 * Consumed by the STT module which reads the file at [filePath] and
 * by the fraud-detection module if it does raw-audio inference.
 *
 * Each chunk is self-contained — the STT module can process chunks
 * in parallel or as they arrive without waiting for the call to end.
 */
data class AudioChunk(
    /** Matches the [CallEvent.id] this chunk belongs to. */
    val callId       : String,

    /** Zero-based index within the call (0 = first chunk). */
    val chunkIndex   : Int,

    /** Absolute path to the chunk file on device storage. */
    val filePath     : String,

    /** Epoch ms when this chunk's recording started. */
    val startTimeMs  : Long,

    /** Epoch ms when this chunk's recording stopped. */
    val endTimeMs    : Long,

    /** Measured duration (endTimeMs - startTimeMs), in ms. */
    val durationMs   : Long = endTimeMs - startTimeMs,

    /** File size in bytes (-1 if file does not exist yet). */
    val sizeBytes    : Long = -1L,

    /** Whether this is the final chunk of the call. */
    val isFinal      : Boolean = false,

    /** Config used to produce this chunk. */
    val config       : AudioCaptureConfig = AudioCaptureConfig.DEFAULT
)

// ─────────────────────────────────────────────────────────────────────────────
// Capture session (aggregated view of a whole call's recording)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Represents the full recording session for one call.
 * Built incrementally as chunks arrive; finalised when the call ends.
 */
data class CaptureSession(
    val callId       : String,
    val config       : AudioCaptureConfig,
    val sessionStart : Long                = System.currentTimeMillis(),
    val sessionEnd   : Long?               = null,
    val chunks       : List<AudioChunk>    = emptyList(),
    val isComplete   : Boolean             = false
) {
    /** Total recorded duration across all chunks, in ms. */
    val totalDurationMs: Long get() = chunks.sumOf { it.durationMs }

    /** Absolute paths of all chunk files, in order. */
    val allFilePaths: List<String> get() = chunks.map { it.filePath }
}
