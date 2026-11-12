package com.fraudcalldetector.data.local.entity

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.fraudcalldetector.domain.model.*

@Entity(tableName = "call_events")
data class CallEventEntity(
    @PrimaryKey val id: String,
    val phoneNumber: String,
    val callerName: String?,
    val startTime: Long,
    val endTime: Long?,
    val direction: String,       // CallDirection.name
    val state: String,           // CallState.name
    val recordingPath: String?,
    val transcript: String?,
    // Fraud analysis fields (flattened)
    val fraudIsFraud: Boolean?,
    val fraudConfidence: Float?,
    val fraudRiskLevel: String?,  // RiskLevel.name
    val fraudAnalysedAt: Long?,
    val fraudFlagsJson: String?   // JSON list of FraudFlag
)

fun CallEventEntity.toDomain(): CallEvent {
    val fraudAnalysis = if (fraudIsFraud != null && fraudConfidence != null && fraudRiskLevel != null) {
        FraudAnalysis(
            isFraud = fraudIsFraud,
            confidence = fraudConfidence,
            riskLevel = RiskLevel.valueOf(fraudRiskLevel),
            flags = emptyList(), // parsed by data layer from fraudFlagsJson
            analysedAt = fraudAnalysedAt ?: 0L
        )
    } else null

    return CallEvent(
        id = id,
        phoneNumber = phoneNumber,
        callerName = callerName,
        startTime = startTime,
        endTime = endTime,
        direction = CallDirection.valueOf(direction),
        state = CallState.valueOf(state),
        recordingPath = recordingPath,
        transcript = transcript,
        fraudAnalysis = fraudAnalysis
    )
}

fun CallEvent.toEntity(): CallEventEntity = CallEventEntity(
    id = id,
    phoneNumber = phoneNumber,
    callerName = callerName,
    startTime = startTime,
    endTime = endTime,
    direction = direction.name,
    state = state.name,
    recordingPath = recordingPath,
    transcript = transcript,
    fraudIsFraud = fraudAnalysis?.isFraud,
    fraudConfidence = fraudAnalysis?.confidence,
    fraudRiskLevel = fraudAnalysis?.riskLevel?.name,
    fraudAnalysedAt = fraudAnalysis?.analysedAt,
    fraudFlagsJson = null // serialized separately
)
