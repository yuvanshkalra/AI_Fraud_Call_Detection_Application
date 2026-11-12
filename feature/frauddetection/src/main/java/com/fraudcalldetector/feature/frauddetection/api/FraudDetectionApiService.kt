package com.fraudcalldetector.feature.frauddetection.api

import com.google.gson.annotations.SerializedName
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.*

// ─────────────────────────────────────────────────────────────────────────────
// JSON-body endpoint  (primary — matches teammates' spec exactly)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Retrofit service for the AI fraud detection backend.
 *
 * ╔══════════════════════════════════════════════════════════════════════════╗
 * ║  TEAMMATE INTEGRATION CONTRACT                                          ║
 * ╠══════════════════════════════════════════════════════════════════════════╣
 * ║  Endpoint:  POST /v1/detect                                             ║
 * ║  Content-Type: application/json                                         ║
 * ║                                                                         ║
 * ║  Request body:                                                          ║
 * ║  {                                                                      ║
 * ║    "call_id":    "string",          // required                         ║
 * ║    "transcript": "string",          // required                         ║
 * ║    "language":   "en-US",           // optional, default "en-US"        ║
 * ║    "metadata": {                    // optional                         ║
 * ║      "caller_number": "string",                                         ║
 * ║      "duration_ms":   12000,                                            ║
 * ║      "chunk_index":   0,                                                ║
 * ║      "is_final":      true                                              ║
 * ║    }                                                                    ║
 * ║  }                                                                      ║
 * ║                                                                         ║
 * ║  Response body (HTTP 200):                                              ║
 * ║  {                                                                      ║
 * ║    "is_fraud":   true,                                                  ║
 * ║    "risk_score": 0.92,              // 0.0–1.0                          ║
 * ║    "risk_level": "HIGH",            // SAFE|LOW|MEDIUM|HIGH|CRITICAL    ║
 * ║    "reason":     "Suspicious OTP request",                              ║
 * ║    "signals":    [                  // optional detailed breakdown       ║
 * ║      {                                                                  ║
 * ║        "type":        "SUSPICIOUS_KEYWORDS",                            ║
 * ║        "description": "OTP keyword with urgency pattern",               ║
 * ║        "severity":    0.88                                              ║
 * ║      }                                                                  ║
 * ║    ],                                                                   ║
 * ║    "model_version": "v2.1.0"        // optional                        ║
 * ║  }                                                                      ║
 * ╚══════════════════════════════════════════════════════════════════════════╝
 */
interface FraudDetectionApiService {

    /**
     * Primary JSON-body endpoint — preferred for all new integrations.
     */
    @POST("v1/detect")
    suspend fun detectFraud(
        @Body request: FraudDetectionRequestDto
    ): Response<FraudDetectionResponseDto>

    /**
     * Legacy multipart endpoint — kept for backwards compatibility.
     * Deprecated: prefer [detectFraud] with a JSON body.
     */
    @Multipart
    @POST("v1/detect")
    suspend fun detectFraudMultipart(
        @Part("call_id")    callId    : RequestBody,
        @Part("transcript") transcript: RequestBody,
        @Part              audio      : MultipartBody.Part?
    ): Response<FraudDetectionResponseDto>
}

// ─────────────────────────────────────────────────────────────────────────────
// Request DTO
// ─────────────────────────────────────────────────────────────────────────────

data class FraudDetectionRequestDto(
    @SerializedName("call_id")    val callId    : String,
    @SerializedName("transcript") val transcript: String,
    @SerializedName("language")   val language  : String                 = "en-US",
    @SerializedName("metadata")   val metadata  : RequestMetadataDto?    = null
)

data class RequestMetadataDto(
    @SerializedName("caller_number") val callerNumber: String? = null,
    @SerializedName("duration_ms")   val durationMs  : Long   = 0L,
    @SerializedName("chunk_index")   val chunkIndex  : Int    = 0,
    @SerializedName("is_final")      val isFinal     : Boolean = true
)

// ─────────────────────────────────────────────────────────────────────────────
// Response DTO  — exact spec from the user request
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Exact JSON contract as specified by teammates:
 * {
 *   "is_fraud":   true,
 *   "risk_score": 0.92,
 *   "risk_level": "HIGH",
 *   "reason":     "Suspicious OTP request"
 * }
 *
 * Extended with optional [signals] and [modelVersion] for richer responses.
 */
data class FraudDetectionResponseDto(
    @SerializedName("is_fraud")      val isFraud     : Boolean,
    @SerializedName("risk_score")    val riskScore   : Float,
    @SerializedName("risk_level")    val riskLevel   : String,
    @SerializedName("reason")        val reason      : String,
    @SerializedName("signals")       val signals     : List<SignalDto>?   = null,
    @SerializedName("model_version") val modelVersion: String?            = null
)

data class SignalDto(
    @SerializedName("type")        val type       : String,
    @SerializedName("description") val description: String,
    @SerializedName("severity")    val severity   : Float
)

// ─────────────────────────────────────────────────────────────────────────────
// Legacy response DTO (kept for FraudDetectionRepositoryImpl compat)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Legacy response shape used by the existing multipart endpoint.
 * @deprecated Use [FraudDetectionResponseDto] for new integrations.
 */
@Deprecated("Use FraudDetectionResponseDto")
data class FraudDetectionResponse(
    val callId      : String,
    val isFraud     : Boolean,
    val confidence  : Float,
    val riskLevel   : String,
    val flags       : List<FraudFlagDto>,
    val modelVersion: String
)

@Deprecated("Use SignalDto")
data class FraudFlagDto(
    val type       : String,
    val description: String,
    val severity   : Float
)
