package com.fraudcalldetector.domain.stt

import kotlinx.coroutines.flow.Flow

/**
 * Low-level STT engine abstraction.
 *
 * Each implementation knows how to convert a single audio file (or stream)
 * into transcription results. It has no knowledge of call sessions, chunk
 * rotation, persistence, or the UI — those concerns live in [SttManager].
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * Implementations
 * ─────────────────────────────────────────────────────────────────────────────
 *  • [MockSttEngine]   — deterministic fake for unit tests / CI / offline dev
 *  • [RemoteSttEngine] — HTTP multipart upload to teammate's REST endpoint
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * Replacing the AI model
 * ─────────────────────────────────────────────────────────────────────────────
 * Swap the binding in [SttModule] without changing any call-site:
 *   @Binds abstract fun bindEngine(impl: YourCustomEngine): SttEngine
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * Streaming support
 * ─────────────────────────────────────────────────────────────────────────────
 * [transcribeStreaming] emits [SttStreamEvent] incrementally so the UI can
 * show partial words as they arrive. Engines that do not support true streaming
 * may emit a single [SttStreamEvent.Partial] followed by [SttStreamEvent.Final].
 */
interface SttEngine {

    /**
     * True when this engine supports incremental word emission during transcription.
     * [MockSttEngine] sets this to true; [RemoteSttEngine] sets it based on whether
     * the endpoint returns a streaming chunked response.
     */
    val supportsStreaming: Boolean

    /**
     * Transcribe a single audio file synchronously.
     *
     * Suspends until the entire file is processed.
     * Use [transcribeStreaming] if you want incremental updates.
     *
     * @param audioFilePath Absolute path to the audio file on device.
     * @param config        Transcription parameters (language, hints, etc.).
     * @return              Completed [SttEngineResult] with all segments and timestamps.
     * @throws [SttEngineException] on hard failure (network, auth, codec).
     */
    @Throws(SttEngineException::class)
    suspend fun transcribe(
        audioFilePath: String,
        config       : SttEngineConfig = SttEngineConfig.DEFAULT
    ): SttEngineResult

    /**
     * Transcribe a single audio file and stream incremental results.
     *
     * Each emission is one [SttStreamEvent]; the last emission is always
     * [SttStreamEvent.Final] (or [SttStreamEvent.Error] on failure).
     *
     * Engines that don't support true streaming emit:
     *   1. Nothing until finished
     *   2. [SttStreamEvent.Final] in one shot
     *
     * @param audioFilePath Absolute path to the audio file on device.
     * @param config        Transcription parameters.
     * @return              Cold [Flow] of [SttStreamEvent]s. Collection starts the job.
     */
    fun transcribeStreaming(
        audioFilePath: String,
        config       : SttEngineConfig = SttEngineConfig.DEFAULT
    ): Flow<SttStreamEvent>
}

// ─────────────────────────────────────────────────────────────────────────────
// Configuration
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Immutable configuration for a single STT transcription request.
 *
 * Passed to [SttEngine.transcribe] / [SttEngine.transcribeStreaming].
 */
data class SttEngineConfig(
    /** BCP-47 language code, e.g. "en-US", "es-ES". */
    val languageCode      : String        = "en-US",

    /** Optional list of phrases to bias recognition toward (name hints, product names). */
    val speechContextHints: List<String>  = emptyList(),

    /** Whether to emit incremental/partial results (only used if [SttEngine.supportsStreaming]). */
    val enableStreaming    : Boolean       = true,

    /** Whether to request per-word timestamps in the result. */
    val enableWordTimestamps: Boolean     = true,

    /** Whether to automatically add punctuation. */
    val enablePunctuation  : Boolean      = true,

    /** Whether to try diarising speakers (caller vs user). */
    val enableDiarisation  : Boolean      = true,

    /**
     * Maximum number of alternative transcriptions to return (1 = best only).
     * Set > 1 to feed multiple hypotheses to a fraud model.
     */
    val maxAlternatives    : Int          = 1,

    /**
     * MIME type of the audio file sent to the engine.
     * For mock: ignored. For remote: set as Content-Type of the multipart part.
     */
    val audioMimeType      : String       = "audio/mp4"
) {
    companion object {
        /** Default config — good for speech-to-text pipelines. */
        val DEFAULT = SttEngineConfig()

        /** Quick config for unit tests (no diarisation overhead). */
        val TEST = SttEngineConfig(
            enableDiarisation    = false,
            enableWordTimestamps = true,
            enableStreaming      = true
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Result types
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Complete transcription result for one audio file.
 *
 * Returned by [SttEngine.transcribe]. Also produced as the final payload of
 * [SttStreamEvent.Final].
 */
data class SttEngineResult(
    /** Verbatim text assembled from all segments. */
    val fullText       : String,

    /** Time-stamped, speaker-labelled segments. */
    val segments       : List<SttSegment>,

    /** Per-word timestamps (populated when [SttEngineConfig.enableWordTimestamps] = true). */
    val words          : List<WordTimestamp>,

    /** Detected language (BCP-47 code). */
    val detectedLanguage: String = "en-US",

    /** Total duration of the audio analysed, in milliseconds. */
    val audioDurationMs : Long = 0L,

    /**
     * Engine-assigned overall confidence for the full transcript (0.0–1.0).
     * Word-level confidences are on [WordTimestamp.confidence].
     */
    val overallConfidence: Float = 0f,

    /** Which chunk index this result corresponds to (set by [SttManager]). */
    val chunkIndex     : Int  = 0,

    /** Whether this is the final result for the call (last chunk). */
    val isFinal        : Boolean = false
) {
    companion object {
        /** Empty/failed result. */
        fun empty(isFinal: Boolean = false) = SttEngineResult(
            fullText    = "",
            segments    = emptyList(),
            words       = emptyList(),
            isFinal     = isFinal
        )
    }
}

/**
 * A speaker-labelled time slice of transcribed text.
 */
data class SttSegment(
    /** Unique ID for this segment (engine-assigned or generated). */
    val id           : String,
    /** Transcribed text for this segment. */
    val text         : String,
    /** Who is speaking. */
    val speaker      : SttSpeaker,
    /** Start offset from the beginning of the audio chunk, in ms. */
    val startOffsetMs: Long,
    /** End offset from the beginning of the audio chunk, in ms. */
    val endOffsetMs  : Long,
    /** Confidence score for this segment (0.0–1.0). */
    val confidence   : Float = 0f
)

/**
 * Per-word timestamp entry.
 *
 * Useful for detecting urgency cues or highlighting suspicious words
 * in the fraud analysis model.
 */
data class WordTimestamp(
    /** The word exactly as transcribed. */
    val word         : String,
    /** Offset from start of the audio chunk, in ms. */
    val startOffsetMs: Long,
    /** Offset from start of the audio chunk, in ms. */
    val endOffsetMs  : Long,
    /** Per-word confidence (0.0–1.0). */
    val confidence   : Float = 0f,
    /** Speaker label for this word (inherited from the enclosing segment). */
    val speaker      : SttSpeaker = SttSpeaker.UNKNOWN
)

/**
 * Speaker identity as reported by the diarisation model.
 */
enum class SttSpeaker {
    /** The remote party (the caller the user is talking to). */
    CALLER,
    /** The device user. */
    USER,
    /** Diarisation was not performed or speaker could not be determined. */
    UNKNOWN
}

// ─────────────────────────────────────────────────────────────────────────────
// Streaming events
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Events emitted by [SttEngine.transcribeStreaming].
 *
 * Consumers observe this flow in real-time to update the active-call UI.
 */
sealed class SttStreamEvent {

    /**
     * Incremental/partial transcription — text may change as the engine
     * hears more audio. Multiple [Partial] events may be emitted.
     *
     * @param text        Current best-guess transcription so far.
     * @param confidence  Provisional confidence (may be low for partials).
     * @param wordsStable Words that will not change in future emissions (engine hint).
     */
    data class Partial(
        val text         : String,
        val confidence   : Float              = 0f,
        val wordsStable  : List<WordTimestamp> = emptyList()
    ) : SttStreamEvent()

    /**
     * The engine emitted a finalized segment (speaker-labelled sentence).
     * Stable — will not be revised by future events.
     *
     * @param segment The finalised [SttSegment].
     * @param words   Per-word timestamps for this segment.
     */
    data class SegmentFinalised(
        val segment: SttSegment,
        val words  : List<WordTimestamp> = emptyList()
    ) : SttStreamEvent()

    /**
     * The full audio file has been processed; this is the complete result.
     * Always the last event in the flow (before the flow completes).
     *
     * @param result Complete [SttEngineResult].
     */
    data class Final(val result: SttEngineResult) : SttStreamEvent()

    /**
     * A non-recoverable error terminated the transcription.
     *
     * @param exception The wrapped [SttEngineException].
     */
    data class Error(val exception: SttEngineException) : SttStreamEvent()
}

// ─────────────────────────────────────────────────────────────────────────────
// Exception
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Thrown by [SttEngine] when a transcription request fails.
 */
class SttEngineException(
    message      : String,
    cause        : Throwable? = null,
    /** HTTP status code if the failure was a network/API error. */
    val httpCode : Int?       = null,
    /** Whether retrying the request might succeed. */
    val retryable: Boolean    = false
) : Exception(message, cause)
