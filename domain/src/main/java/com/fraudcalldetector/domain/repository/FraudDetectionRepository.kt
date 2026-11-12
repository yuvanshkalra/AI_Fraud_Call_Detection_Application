package com.fraudcalldetector.domain.repository

import com.fraudcalldetector.domain.model.FraudAnalysis
import kotlinx.coroutines.flow.Flow

/**
 * Contract for AI fraud detection persistence and analysis.
 * Implemented in :feature:frauddetection.
 *
 * [FraudDetectionManager] writes results here; the call-history UI and
 * ActiveCallViewModel read from here.
 */
interface FraudDetectionRepository {

    /**
     * Analyse transcript text for fraud signals.
     *
     * This is the one-shot entry point used by [AnalyseCallUseCase].
     * For the auto-trigger pipeline use [FraudDetectionManager] directly.
     *
     * @param callId        Unique call identifier.
     * @param transcript    Full text transcript.
     * @param audioFilePath Optional path to raw audio for direct inference.
     */
    suspend fun analyseCall(
        callId       : String,
        transcript   : String,
        audioFilePath: String? = null
    ): FraudAnalysis

    /**
     * Persist [analysis] for [callId] in the local database.
     */
    suspend fun saveAnalysis(callId: String, analysis: FraudAnalysis)

    /**
     * Retrieve a persisted analysis for [callId], or null if not found.
     */
    suspend fun getAnalysis(callId: String): FraudAnalysis?

    /**
     * Observe the stored analysis for [callId] in real time.
     *
     * Emits whenever a new result is saved (by [FraudDetectionManager]).
     * Emits null if no analysis exists yet for this call.
     */
    fun observeAnalysis(callId: String): Flow<FraudAnalysis?>

    /**
     * Observe all stored analyses, newest first.
     * Suitable for driving a fraud-history list.
     */
    fun observeAllAnalyses(): Flow<List<FraudAnalysis>>

    /**
     * Retrieve cached (in-memory) analysis for a previously analysed call.
     * Returns null if the call was not analysed in the current app session.
     */
    suspend fun getCachedAnalysis(callId: String): FraudAnalysis?

    /**
     * Delete the stored analysis for [callId].
     */
    suspend fun deleteAnalysis(callId: String)
}
