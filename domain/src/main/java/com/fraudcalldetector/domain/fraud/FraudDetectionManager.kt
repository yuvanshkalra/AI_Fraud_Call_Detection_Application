package com.fraudcalldetector.domain.fraud

import com.fraudcalldetector.domain.model.FraudAlertConfig
import com.fraudcalldetector.domain.model.FraudAnalysis
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * High-level fraud-detection orchestrator contract.
 *
 * [FraudDetectionManager] sits above [FraudDetectionEngine] and handles:
 *  • Subscribing to [SttManager.state] — triggers analysis when a call's
 *    transcript reaches [SttManagerState.CallComplete].
 *  • Real-time per-chunk scoring whenever [SttManager.liveTranscript] grows —
 *    emits [FraudDetectionState.IncrementalResult] as each chunk lands.
 *  • Firing a fraud alert (via [FraudNotificationManager]) as soon as
 *    [FraudAlertConfig.alertThreshold] is crossed, even mid-call.
 *  • Driving [FraudDetectionEngine] with a [FraudDetectionRequest] built
 *    from the completed transcript + call metadata.
 *  • Persisting results via [FraudDetectionRepository].
 *  • Emitting notifications via [FraudNotificationManager].
 *  • Exposing observable state for the active-call and call-history UIs.
 *
 * Implemented by [FraudDetectionManagerImpl] in :feature:frauddetection.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * Data flow
 * ─────────────────────────────────────────────────────────────────────────────
 *
 *   SttManager.liveTranscript (each chunk adds text)
 *         │
 *         ▼
 *   FraudDetectionManager → engine.analyse(partialTranscript)
 *         │
 *         ├── emits IncrementalResult  ──► ActiveCallViewModel (live badge)
 *         │
 *         └── if confidence >= threshold → showFraudAlert() immediately
 *
 *   SttManagerState.CallComplete(callId, fullTranscript)
 *         │
 *         ▼
 *   FraudDetectionManager.analyseCall(callId, transcript, ...)
 *         │
 *         ├── FraudDetectionEngine.analyse(request)  →  FraudDetectionEngineResult
 *         │
 *         ├── map to FraudAnalysis domain model
 *         │
 *         ├── FraudDetectionRepository.saveAnalysis(callId, analysis)
 *         │
 *         ├── CallRepository.updateCall(call.copy(fraudAnalysis = analysis))
 *         │
 *         └── FraudNotificationManager.showFraudAlert(...)  (if >= threshold)
 */
interface FraudDetectionManager {

    /** Observable state of the fraud detection subsystem. */
    val state: StateFlow<FraudDetectionState>

    /**
     * Hot flow of completed [FraudAnalysis] results, one per call.
     * Replay = 1 so a late subscriber sees the most recent result.
     */
    val results: Flow<FraudAnalysisResult>

    /**
     * Manually trigger fraud analysis for [callId].
     *
     * Normally auto-triggered when [SttManager] emits [SttManagerState.CallComplete].
     * Exposed here for one-shot calls (e.g., re-analysing a call from history).
     *
     * @param callId        Stable call identifier.
     * @param transcript    Full transcript text.
     * @param audioFilePath Optional path to call recording.
     * @param callerNumber  Caller's phone number (for scam-number lookup).
     * @param durationMs    Call duration in ms.
     * @param config        Alert threshold and mode for this analysis pass.
     */
    suspend fun analyseCall(
        callId       : String,
        transcript   : String,
        audioFilePath: String?         = null,
        callerNumber : String?         = null,
        durationMs   : Long            = 0L,
        config       : FraudAlertConfig = FraudAlertConfig.DEFAULT
    ): FraudAnalysis

    /**
     * Cancel any in-progress analysis for [callId].
     */
    fun cancelAnalysis(callId: String)
}

// ─────────────────────────────────────────────────────────────────────────────
// State
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Observable state of the fraud detection subsystem.
 */
sealed class FraudDetectionState {
    /** No analysis is running. */
    object Idle : FraudDetectionState()

    /** An analysis job is in progress for [callId]. */
    data class Analysing(val callId: String) : FraudDetectionState()

    /**
     * A real-time incremental result arrived for a chunk of [callId] during
     * the call.  The UI should update the live risk badge without waiting for
     * the final pass.
     *
     * @param callId       The call being monitored.
     * @param analysis     Partial analysis from the current transcript snapshot.
     * @param chunkIndex   Which audio chunk triggered this intermediate score.
     */
    data class IncrementalResult(
        val callId     : String,
        val analysis   : FraudAnalysis,
        val chunkIndex : Int
    ) : FraudDetectionState()

    /** Analysis completed for [callId]. */
    data class Complete(val callId: String, val analysis: FraudAnalysis) : FraudDetectionState()

    /** Analysis failed for [callId]. */
    data class Failed(val callId: String, val message: String) : FraudDetectionState()
}

/**
 * Carries a completed [FraudAnalysis] together with the call it belongs to.
 */
data class FraudAnalysisResult(
    val callId  : String,
    val analysis: FraudAnalysis
)
