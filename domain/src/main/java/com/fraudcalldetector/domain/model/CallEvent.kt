package com.fraudcalldetector.domain.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Core domain entity representing a phone call event.
 */
@Parcelize
data class CallEvent(
    val id: String,
    val phoneNumber: String,
    val callerName: String?,
    val startTime: Long,
    val endTime: Long?,
    val direction: CallDirection,
    val state: CallState,
    val recordingPath: String? = null,
    val transcript: String? = null,
    val fraudAnalysis: FraudAnalysis? = null
) : Parcelable {
    val durationMs: Long
        get() = if (endTime != null && endTime > startTime) endTime - startTime else 0L
}

@Parcelize
data class FraudAnalysis(
    val isFraud: Boolean,
    val confidence: Float,        // 0.0 – 1.0
    val riskLevel: RiskLevel,
    val flags: List<FraudFlag>,
    val analysedAt: Long = System.currentTimeMillis()
) : Parcelable

@Parcelize
data class FraudFlag(
    val type: FraudFlagType,
    val description: String,
    val severity: Float           // 0.0 – 1.0
) : Parcelable

enum class CallDirection { INBOUND, OUTBOUND }

enum class CallState { RINGING, ACTIVE, ENDED, MISSED }

enum class RiskLevel { SAFE, LOW, MEDIUM, HIGH, CRITICAL }

enum class FraudFlagType {
    SUSPICIOUS_KEYWORDS,
    UNUSUAL_PATTERNS,
    KNOWN_SCAM_NUMBER,
    AI_DETECTED_MANIPULATION,
    RAPID_SPEECH,
    VOICE_SPOOFING
}
