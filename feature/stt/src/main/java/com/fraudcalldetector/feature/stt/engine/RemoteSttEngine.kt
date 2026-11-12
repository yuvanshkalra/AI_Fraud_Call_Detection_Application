package com.fraudcalldetector.feature.stt.engine

import com.fraudcalldetector.core.network.ApiResult
import com.fraudcalldetector.core.network.safeApiCall
import com.fraudcalldetector.domain.stt.*
import com.fraudcalldetector.feature.stt.api.SttApiService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Production [SttEngine] backed by the teammate-provided REST endpoint.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * Integration contract for teammates
 * ─────────────────────────────────────────────────────────────────────────────
 * 1. Set the base URL in [SttModule]:
 *      private const val STT_BASE_URL = "https://your-endpoint.com/"
 *
 * 2. The engine POSTs to POST /v1/transcribe (see [SttApiService]).
 *    JSON response shape:
 *    {
 *      "call_id":          "string",
 *      "full_text":        "string",
 *      "language":         "en-US",
 *      "duration_seconds": 12.4,
 *      "segments": [
 *        {
 *          "id":         "string",
 *          "text":       "string",
 *          "speaker":    "caller" | "user" | "unknown",
 *          "start_ms":   1200,
 *          "end_ms":     3400,
 *          "confidence": 0.93
 *        }
 *      ]
 *    }
 *
 * 3. [supportsStreaming] is false — the current endpoint is request/response.
 *    For true streaming upgrade the API to Server-Sent Events and override
 *    [transcribeStreaming] to consume the SSE stream.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * Error handling
 * ─────────────────────────────────────────────────────────────────────────────
 * Network / HTTP errors are wrapped in [SttEngineException] with [retryable]
 * set to true for 5xx errors and false for 4xx.
 */
@Singleton
class RemoteSttEngine @Inject constructor(
    private val apiService: SttApiService
) : SttEngine {

    // The current REST endpoint is request/response; mark streaming as unsupported
    // so SttManager knows to emit a single Final event after the call returns.
    override val supportsStreaming: Boolean = false

    // ─────────────────────────────────────────────────────────────────────────
    // SttEngine — synchronous
    // ─────────────────────────────────────────────────────────────────────────

    override suspend fun transcribe(
        audioFilePath: String,
        config       : SttEngineConfig
    ): SttEngineResult {
        Timber.d("RemoteSttEngine.transcribe → $audioFilePath")

        val file         = File(audioFilePath)
        if (!file.exists()) {
            throw SttEngineException("Audio file not found: $audioFilePath", retryable = false)
        }

        val requestFile  = file.asRequestBody(config.audioMimeType.toMediaTypeOrNull())
        val audioPart    = MultipartBody.Part.createFormData("audio", file.name, requestFile)
        val languagePart = config.languageCode.toRequestBody("text/plain".toMediaTypeOrNull())
        // callId is not known at the engine level; SttManager injects it via filename convention
        val callIdPart   = file.nameWithoutExtension.toRequestBody("text/plain".toMediaTypeOrNull())

        return when (val result = safeApiCall {
            apiService.transcribeAudio(audioPart, languagePart, callIdPart)
        }) {
            is ApiResult.Success -> {
                val dto = result.data
                Timber.d("RemoteSttEngine: success, ${dto.segments.size} segments")
                dto.toEngineResult()
            }
            is ApiResult.Error -> {
                val retryable = (result.code ?: 0) >= 500
                val msg = "STT API error ${result.code}: ${result.message}"
                Timber.e(msg)
                throw SttEngineException(msg, httpCode = result.code, retryable = retryable)
            }
            is ApiResult.Exception -> {
                Timber.e(result.throwable, "STT API exception")
                throw SttEngineException(
                    "STT network error: ${result.throwable.message}",
                    cause    = result.throwable,
                    retryable = true
                )
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SttEngine — streaming (non-streaming fallback)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * The remote endpoint does not support true streaming. This implementation
     * calls [transcribe] and emits the result as a single [SttStreamEvent.Final].
     *
     * To upgrade to real streaming:
     *  1. Change the API to Server-Sent Events or WebSocket.
     *  2. Replace the body of this function with an SSE/WS consumer that
     *     emits [SttStreamEvent.Partial] as chunks arrive.
     *  3. Set [supportsStreaming] = true.
     */
    override fun transcribeStreaming(
        audioFilePath: String,
        config       : SttEngineConfig
    ): Flow<SttStreamEvent> = flow {
        try {
            val result = transcribe(audioFilePath, config)
            // Emit one SegmentFinalised per segment before the Final event
            result.segments.forEach { segment ->
                emit(SttStreamEvent.SegmentFinalised(segment = segment, words = emptyList()))
            }
            emit(SttStreamEvent.Final(result))
        } catch (e: SttEngineException) {
            emit(SttStreamEvent.Error(e))
        } catch (e: Exception) {
            emit(SttStreamEvent.Error(
                SttEngineException("RemoteSttEngine streaming error: ${e.message}", e)
            ))
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// DTO → domain mapper
// ─────────────────────────────────────────────────────────────────────────────

private fun com.fraudcalldetector.feature.stt.api.SttResponse.toEngineResult(): SttEngineResult {
    val segments = this.segments.map { seg ->
        SttSegment(
            id            = seg.id,
            text          = seg.text,
            speaker       = when (seg.speaker.lowercase()) {
                "caller" -> SttSpeaker.CALLER
                "user"   -> SttSpeaker.USER
                else     -> SttSpeaker.UNKNOWN
            },
            startOffsetMs = seg.startMs,
            endOffsetMs   = seg.endMs,
            confidence    = seg.confidence
        )
    }

    // Build word-level timestamps from segment data
    // (The current API does not return per-word timestamps; each segment is
    //  treated as one "word" entry. When the API is upgraded, map seg.words here.)
    val words = segments.map { seg ->
        WordTimestamp(
            word          = seg.text.take(40),   // truncate for display
            startOffsetMs = seg.startOffsetMs,
            endOffsetMs   = seg.endOffsetMs,
            confidence    = seg.confidence,
            speaker       = seg.speaker
        )
    }

    val durationMs = (durationSeconds * 1000).toLong()
    val avgConfidence = if (segments.isEmpty()) 0f
                        else segments.map { it.confidence }.average().toFloat()

    return SttEngineResult(
        fullText          = fullText,
        segments          = segments,
        words             = words,
        detectedLanguage  = language,
        audioDurationMs   = durationMs,
        overallConfidence = avgConfidence
    )
}
