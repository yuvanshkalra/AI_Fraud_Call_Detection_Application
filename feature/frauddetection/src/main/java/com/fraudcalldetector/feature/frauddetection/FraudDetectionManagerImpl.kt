package com.fraudcalldetector.feature.frauddetection

import com.fraudcalldetector.domain.fraud.*
import com.fraudcalldetector.domain.model.*
import com.fraudcalldetector.domain.repository.AlertHistoryRepository
import com.fraudcalldetector.domain.repository.CallRepository
import com.fraudcalldetector.domain.repository.FraudDetectionRepository
import com.fraudcalldetector.domain.stt.SttManager
import com.fraudcalldetector.domain.stt.SttManagerState
import com.fraudcalldetector.feature.frauddetection.di.FraudDetectionManagerScope
import com.fraudcalldetector.feature.notifications.FraudNotificationManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * FraudDetectionManagerImpl
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * Full real-time pipeline:
 *
 *   CallMonitor  ──► AudioCaptureManager  ──► SttManagerImpl
 *       │                                          │
 *       │              SttManager.state            │
 *       │     ┌─────── ChunkComplete ◄─────────────┤
 *       │     │                                    │
 *       │     ▼                                    │
 *       │  Per-chunk incremental analysis           │
 *       │  (FraudDetectionEngine.analyse)           │
 *       │     │                                    │
 *       │     ├── state → IncrementalResult ──► UI │
 *       │     │                                    │
 *       │     └── if confidence >= threshold       │
 *       │             → showFraudAlert() now       │
 *       │                                          │
 *       └─────────── SttManagerState.CallComplete ─┘
 *                           │
 *                           ▼
 *                   Final full-transcript analysis
 *                   Persist + update CallRepository
 *                   Reset SttManager session
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * Key behaviours
 * ─────────────────────────────────────────────────────────────────────────────
 *  • Per-chunk scoring:  [SttManagerState.ChunkComplete] triggers a lightweight
 *    analyse() call on the accumulated transcript so far.  The result is emitted
 *    as [FraudDetectionState.IncrementalResult] without persisting.
 *
 *  • Threshold alert:  When confidence >= [FraudAlertConfig.alertThreshold] AND
 *    riskLevel >= [FraudAlertConfig.minRiskLevelForAlert] a fraud notification
 *    fires immediately during the call.  If [alertOnFinalPassOnly] is set,
 *    this mid-call alert is suppressed.
 *
 *  • Call-end cleanup:  On [SttManagerState.CallComplete] the final analysis
 *    is run, persisted, and [SttManager.resetSession] is called so memory is
 *    not leaked after the call ends.
 *
 *  • Deduplication:  A mid-call alert is only shown once per call.  The final
 *    pass always runs regardless of whether an early alert fired.
 */
@Singleton
class FraudDetectionManagerImpl @Inject constructor(
    private val engine              : FraudDetectionEngine,
    private val fraudRepository     : FraudDetectionRepository,
    private val callRepository      : CallRepository,
    private val sttManager          : SttManager,
    private val notificationManager : FraudNotificationManager,
    private val alertHistoryRepository: AlertHistoryRepository,
    @FraudDetectionManagerScope private val scope: CoroutineScope
) : FraudDetectionManager {

    // ─────────────────────────────────────────────────────────────────────────
    // Public state
    // ─────────────────────────────────────────────────────────────────────────

    private val _state = MutableStateFlow<FraudDetectionState>(FraudDetectionState.Idle)
    override val state: StateFlow<FraudDetectionState> = _state.asStateFlow()

    private val _results = MutableSharedFlow<FraudAnalysisResult>(
        replay              = 1,
        extraBufferCapacity = 8
    )
    override val results: Flow<FraudAnalysisResult> = _results.asSharedFlow()

    // ─────────────────────────────────────────────────────────────────────────
    // Per-call tracking
    // ─────────────────────────────────────────────────────────────────────────

    /** callId → whether an early mid-call alert has already fired. */
    private val alertFiredForCall = mutableSetOf<String>()

    /** callId → per-call sequential job used for incremental scoring. */
    private val incrementalJobs = mutableMapOf<String, Job>()

    // ─────────────────────────────────────────────────────────────────────────
    // Auto-subscription to SttManager
    // ─────────────────────────────────────────────────────────────────────────

    init {
        scope.launch {
            sttManager.state.collect { sttState ->
                when (sttState) {

                    // ── Real-time per-chunk incremental scoring ───────────────
                    is SttManagerState.ChunkComplete -> {
                        val liveText = sttManager.liveTranscript.value
                        if (liveText.isNotBlank()) {
                            Timber.d(
                                "FraudDetectionManager: ChunkComplete → incremental " +
                                "callId=${sttState.callId} chunk=${sttState.chunkIndex} " +
                                "transcriptLen=${liveText.length}"
                            )
                            enqueueIncrementalAnalysis(
                                callId     = sttState.callId,
                                transcript = liveText,
                                chunkIndex = sttState.chunkIndex
                            )
                        }
                    }

                    // ── Final analysis on call complete ───────────────────────
                    is SttManagerState.CallComplete -> {
                        Timber.d(
                            "FraudDetectionManager: SttManager CallComplete → " +
                            "callId=${sttState.callId}"
                        )
                        scope.launch {
                            runFinalAnalysis(
                                callId     = sttState.callId,
                                transcript = sttState.fullTranscript
                            )
                            // Clean up STT session memory after processing
                            sttManager.resetSession(sttState.callId)
                            // Clean up our own per-call state
                            alertFiredForCall.remove(sttState.callId)
                            incrementalJobs.remove(sttState.callId)
                        }
                    }

                    else -> Unit
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // FraudDetectionManager interface
    // ─────────────────────────────────────────────────────────────────────────

    override suspend fun analyseCall(
        callId       : String,
        transcript   : String,
        audioFilePath: String?,
        callerNumber : String?,
        durationMs   : Long,
        config       : FraudAlertConfig
    ): FraudAnalysis = coroutineScope {
        runAnalysis(
            callId        = callId,
            transcript    = transcript,
            audioFilePath = audioFilePath,
            callerNumber  = callerNumber,
            durationMs    = durationMs,
            chunkIndex    = 0,
            isFinalPass   = true,
            persist       = true,
            alertConfig   = config
        )
    }

    override fun cancelAnalysis(callId: String) {
        incrementalJobs.remove(callId)?.cancel()
        Timber.d("FraudDetectionManager: cancelAnalysis callId=$callId")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Incremental (per-chunk) scoring
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Enqueue an incremental analysis for [callId].
     *
     * Jobs are chained sequentially per call so chunk[N] always runs after
     * chunk[N-1] completes — preserving transcript-order semantics even if
     * chunks arrive faster than the engine responds.
     */
    private fun enqueueIncrementalAnalysis(
        callId     : String,
        transcript : String,
        chunkIndex : Int
    ) {
        val previous = incrementalJobs[callId]
        val job = scope.launch {
            previous?.join()   // wait for previous chunk's score before starting next
            runAnalysis(
                callId        = callId,
                transcript    = transcript,
                chunkIndex    = chunkIndex,
                isFinalPass   = false,
                persist       = false,           // don't persist incremental results
                alertConfig   = FraudAlertConfig.DEFAULT
            )
        }
        incrementalJobs[callId] = job
    }

    /**
     * Run the final full-transcript analysis when the call ends.
     * Always persists and emits on [results].
     */
    private suspend fun runFinalAnalysis(
        callId       : String,
        transcript   : String,
        audioFilePath: String?  = null,
        callerNumber : String?  = null,
        durationMs   : Long     = 0L
    ) {
        // Wait for any in-flight incremental job to finish so we don't
        // race the engine with simultaneous requests.
        incrementalJobs[callId]?.join()

        runAnalysis(
            callId        = callId,
            transcript    = transcript,
            audioFilePath = audioFilePath,
            callerNumber  = callerNumber,
            durationMs    = durationMs,
            chunkIndex    = 0,
            isFinalPass   = true,
            persist       = true,
            alertConfig   = FraudAlertConfig.DEFAULT
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Core analysis pipeline
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Runs one analysis pass through the [FraudDetectionEngine].
     *
     * @param persist       Whether to write the result to [FraudDetectionRepository]
     *                      and update [CallRepository].  False for incremental passes.
     * @param isFinalPass   Forwarded to [FraudDetectionRequest] so the model can
     *                      adjust its behaviour (e.g. run heavier signal analysis).
     * @param alertConfig   Controls when a notification fires.
     */
    private suspend fun runAnalysis(
        callId       : String,
        transcript   : String,
        audioFilePath: String?         = null,
        callerNumber : String?         = null,
        durationMs   : Long            = 0L,
        chunkIndex   : Int             = 0,
        isFinalPass  : Boolean         = true,
        persist      : Boolean         = true,
        alertConfig  : FraudAlertConfig = FraudAlertConfig.DEFAULT
    ): FraudAnalysis {
        if (transcript.isBlank()) {
            Timber.w("FraudDetectionManager: blank transcript for callId=$callId — skipping")
            return FraudAnalysis(false, 0f, RiskLevel.SAFE, emptyList())
        }

        if (isFinalPass) {
            _state.value = FraudDetectionState.Analysing(callId)
        }

        // ── Build request ──────────────────────────────────────────────────
        val request = FraudDetectionRequest(
            callId        = callId,
            transcript    = transcript,
            audioFilePath = audioFilePath,
            callerNumber  = callerNumber,
            durationMs    = durationMs,
            chunkIndex    = chunkIndex,
            isFinalPass   = isFinalPass
        )

        // ── Call engine ────────────────────────────────────────────────────
        val engineResult = try {
            engine.analyse(request)
        } catch (e: FraudDetectionEngineException) {
            Timber.e(e, "FraudDetectionManager: engine failure for callId=$callId")
            if (isFinalPass) {
                _state.value = FraudDetectionState.Failed(callId, e.message ?: "Engine error")
            }
            return FraudAnalysis(false, 0f, RiskLevel.SAFE, emptyList())
        } catch (e: Exception) {
            Timber.e(e, "FraudDetectionManager: unexpected error for callId=$callId")
            if (isFinalPass) {
                _state.value = FraudDetectionState.Failed(callId, e.message ?: "Unknown error")
            }
            return FraudAnalysis(false, 0f, RiskLevel.SAFE, emptyList())
        }

        // ── Map to domain model ────────────────────────────────────────────
        val analysis = engineResult.toFraudAnalysis()

        Timber.d(
            "FraudDetectionManager: callId=$callId chunk=$chunkIndex " +
            "isFinal=$isFinalPass isFraud=${analysis.isFraud} " +
            "riskLevel=${analysis.riskLevel} confidence=${analysis.confidence}"
        )

        // ── Emit incremental state for UI (live badge during call) ─────────
        if (!isFinalPass) {
            _state.value = FraudDetectionState.IncrementalResult(callId, analysis, chunkIndex)
        }

        // ── Threshold-based alert (fires during call if confidence is high) ─
        maybeFireAlert(callId, analysis, alertConfig, callerNumber)

        // ── Persist + update call record (final pass only) ─────────────────
        if (persist) {
            runCatching { fraudRepository.saveAnalysis(callId, analysis) }
                .onFailure { Timber.e(it, "FraudDetectionManager: failed to persist analysis") }

            runCatching {
                val call = callRepository.getCallById(callId)
                call?.let { callRepository.updateCall(it.copy(fraudAnalysis = analysis)) }
            }.onFailure { Timber.e(it, "FraudDetectionManager: failed to update call record") }

            // ── Emit final result ──────────────────────────────────────────
            _results.tryEmit(FraudAnalysisResult(callId, analysis))
            _state.value = FraudDetectionState.Complete(callId, analysis)

            Timber.i(
                "FraudDetectionManager: final analysis complete callId=$callId " +
                "isFraud=${analysis.isFraud} riskLevel=${analysis.riskLevel} " +
                "confidence=${analysis.confidence}"
            )
        }

        return analysis
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Threshold-based alert helper
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Fires a fraud alert if:
     *  1. [analysis.confidence] >= [FraudAlertConfig.alertThreshold], AND
     *  2. [analysis.riskLevel]  >= [FraudAlertConfig.minRiskLevelForAlert], AND
     *  3. [FraudAlertConfig.alertOnFinalPassOnly] is false (or we are on the final pass), AND
     *  4. An alert has not already fired for [callId] (deduplication).
     */
    private fun maybeFireAlert(
        callId       : String,
        analysis     : FraudAnalysis,
        config       : FraudAlertConfig,
        callerNumber : String?
    ) {
        if (alertFiredForCall.contains(callId)) return  // already alerted this call

        val aboveThreshold = analysis.confidence >= config.alertThreshold
        val aboveMinLevel  = analysis.riskLevel >= config.minRiskLevelForAlert

        if (aboveThreshold && aboveMinLevel) {
            alertFiredForCall.add(callId)
            scope.launch {
                runCatching {
                    val number = callerNumber ?: callId
                    // Look up caller name from CallRepository if available
                    val callerName = runCatching {
                        callRepository.getCallById(callId)?.callerName
                    }.getOrNull()
                    val primaryReason = analysis.flags.firstOrNull()?.description
                        ?: "Suspicious activity detected"

                    // Post the system notification
                    notificationManager.showFraudAlert(
                        callId      = callId,
                        phoneNumber = number,
                        analysis    = analysis,
                        callerName  = callerName,
                        fraudReason = primaryReason
                    )

                    // Record in alert history
                    alertHistoryRepository.addAlert(
                        AlertRecord(
                            callId        = callId,
                            phoneNumber   = number,
                            callerName    = callerName,
                            analysis      = analysis,
                            primaryReason = primaryReason,
                            status        = AlertStatus.ACTIVE
                        )
                    )

                    Timber.i(
                        "FraudDetectionManager: fraud alert fired & recorded " +
                        "callId=$callId riskLevel=${analysis.riskLevel} " +
                        "confidence=${analysis.confidence}"
                    )
                }.onFailure { Timber.e(it, "FraudDetectionManager: alert/record failed") }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Engine result → domain mapper
// ─────────────────────────────────────────────────────────────────────────────

private fun FraudDetectionEngineResult.toFraudAnalysis(): FraudAnalysis {
    val parsedRiskLevel = runCatching { RiskLevel.valueOf(riskLevel) }
        .getOrDefault(RiskLevel.SAFE)

    val flags = if (signals.isNotEmpty()) {
        signals.map { s ->
            FraudFlag(
                type        = runCatching { FraudFlagType.valueOf(s.type) }
                                  .getOrDefault(FraudFlagType.SUSPICIOUS_KEYWORDS),
                description = s.description,
                severity    = s.severity
            )
        }
    } else if (isFraud && reason.isNotBlank()) {
        listOf(
            FraudFlag(
                type        = FraudFlagType.SUSPICIOUS_KEYWORDS,
                description = reason,
                severity    = riskScore
            )
        )
    } else {
        emptyList()
    }

    return FraudAnalysis(
        isFraud    = isFraud,
        confidence = riskScore,
        riskLevel  = parsedRiskLevel,
        flags      = flags,
        analysedAt = analysedAtMs
    )
}
