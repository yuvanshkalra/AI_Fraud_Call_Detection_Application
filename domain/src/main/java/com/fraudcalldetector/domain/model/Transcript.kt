package com.fraudcalldetector.domain.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Represents a transcribed segment from audio analysis.
 */
@Parcelize
data class TranscriptSegment(
    val id: String,
    val callEventId: String,
    val text: String,
    val speaker: Speaker,
    val startOffsetMs: Long,
    val endOffsetMs: Long,
    val confidence: Float
) : Parcelable

enum class Speaker { CALLER, USER, UNKNOWN }

/**
 * Full STT result for a call.
 */
@Parcelize
data class TranscriptResult(
    val callEventId: String,
    val fullText: String,
    val segments: List<TranscriptSegment>,
    val language: String = "en-US",
    val createdAt: Long = System.currentTimeMillis()
) : Parcelable
