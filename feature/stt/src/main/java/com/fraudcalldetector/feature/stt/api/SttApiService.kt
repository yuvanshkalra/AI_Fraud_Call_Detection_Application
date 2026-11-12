package com.fraudcalldetector.feature.stt.api

import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.*

/**
 * STT API contract — swap in Google Speech, Whisper, or teammate's endpoint.
 * Base URL is injected via SttModule.
 */
interface SttApiService {

    /**
     * Transcribe an audio file.
     * Multipart upload: field "audio" = audio bytes, "language" = BCP-47 code.
     */
    @Multipart
    @POST("v1/transcribe")
    suspend fun transcribeAudio(
        @Part audio: MultipartBody.Part,
        @Part("language") language: RequestBody,
        @Part("call_id") callId: RequestBody
    ): Response<SttResponse>
}

data class SttResponse(
    val callId: String,
    val fullText: String,
    val segments: List<SttSegmentDto>,
    val language: String,
    val durationSeconds: Double
)

data class SttSegmentDto(
    val id: String,
    val text: String,
    val speaker: String,       // "caller" | "user" | "unknown"
    val startMs: Long,
    val endMs: Long,
    val confidence: Float
)
