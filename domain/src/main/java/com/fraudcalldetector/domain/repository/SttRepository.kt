package com.fraudcalldetector.domain.repository

import com.fraudcalldetector.domain.model.TranscriptResult
import kotlinx.coroutines.flow.Flow

/**
 * Contract for speech-to-text persistence and retrieval.
 * Implemented in :feature:stt.
 *
 * The [SttManager] writes results here; downstream consumers (fraud detection,
 * call history UI) read from here.
 */
interface SttRepository {

    /**
     * Transcribe a single audio file and return the complete result.
     * Suspends until transcription finishes.
     *
     * @param audioFilePath Absolute path to the audio file.
     * @param callId        The call this audio belongs to.
     */
    suspend fun transcribeAudio(audioFilePath: String, callId: String): TranscriptResult

    /**
     * Retrieve the persisted transcript for [callId], or null if not found.
     */
    suspend fun getTranscript(callId: String): TranscriptResult?

    /**
     * Persist [transcript] to the local database.
     */
    suspend fun saveTranscript(transcript: TranscriptResult)

    /**
     * Observe the transcript for [callId] in real time.
     *
     * Emits whenever a new chunk is appended to the stored transcript
     * (the [SttManager] updates the DB after each chunk).
     * Emits null if no transcript exists yet for this call.
     */
    fun observeTranscript(callId: String): Flow<TranscriptResult?>

    /**
     * Delete the transcript and all associated segments for [callId].
     */
    suspend fun deleteTranscript(callId: String)
}
