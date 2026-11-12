package com.fraudcalldetector.domain.fraud

import com.fraudcalldetector.domain.model.FraudAnalysis
import com.fraudcalldetector.domain.model.RiskLevel
import kotlinx.coroutines.flow.Flow

/**
 * Low-level fraud-detection engine abstraction.
 *
 * Each implementation knows how to classify one call's transcript (and
 * optionally its audio) as fraudulent or safe.  It has no knowledge of
 * call sessions, persistence, notifications, or the UI — those concerns
 * live in [FraudDetectionManager].
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * Implementations
 * ─────────────────────────────────────────────────────────────────────────────
 *  • [MockFraudDetectionEngine]   — keyword-driven deterministic predictions for tests / offline dev
 *  • [RemoteFraudDetectionEngine] — HTTP POST to teammates' AI endpoint
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * Replacing the AI model
 * ─────────────────────────────────────────────────────────────────────────────
 * Swap the Hilt binding in [FraudDetectionModule]:
 *   @Binds abstract fun bindEngine(impl: YourEngine): FraudDetectionEngine
 * No other changes required.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * JSON contract (teammates)
 * ─────────────────────────────────────────────────────────────────────────────
 * POST /v1/detect
 * Request body (JSON):
 * {
 *   "call_id":    "string",
 *   "transcript": "string",
 *   "language":   "en-US",          // optional
 *   "metadata": {                   // optional
 *     "caller_number": "string",
 *     "duration_ms": 12000,
 *     "chunk_index": 0,
 *     "is_final":     true
 *   }
 * }
 *
 * Response body (JSON):
 * {
 *   "is_fraud":   true,
 *   "risk_score": 0.92,
 *   "risk_level": "HIGH",
 *   "reason":     "Suspicious OTP request"
 * }
 */
interface FraudDetectionEngine {

    /**
     * True when this engine can return incremental results before the full
     * call transcript is available (streaming mode).
     * [MockFraudDetectionEngine] = false; set true when your model supports
     * per-chunk scoring.
     */
    val supportsIncrementalAnalysis: Boolean

    /**
     * Classify [request] synchronously (suspends until the engine responds).
     *
     * @param request  All available context for this detection pass.
     * @return         [FraudDetectionEngineResult] with prediction details.
     * @throws [FraudDetectionEngineException] on hard failure.
     */
    @Throws(FraudDetectionEngineException::class)
    suspend fun analyse(request: FraudDetectionRequest): FraudDetectionEngineResult

    /**
     * Emit incremental results as more transcript text arrives.
     *
     * Only meaningful when [supportsIncrementalAnalysis] is true; engines
     * that don't support this emit a single [FraudDetectionEngineResult] at
     * the end of collection.
     *
     * @param requests Hot [Flow] of updated [FraudDetectionRequest]s. The engine
     *                 collects them and emits a new result for each one that
     *                 changes the prediction meaningfully.
     * @return         Cold [Flow] of [FraudDetectionEngineResult]s.
     */
    fun analyseStream(
        requests: Flow<FraudDetectionRequest>
    ): Flow<FraudDetectionEngineResult>
}

// ─────────────────────────────────────────────────────────────────────────────
// Request
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Everything the fraud-detection engine knows about one analysis pass.
 *
 * Built by [FraudDetectionManager] and passed to [FraudDetectionEngine.analyse].
 */
data class FraudDetectionRequest(
    /** Stable call identifier from [MonitoringEvent]. */
    val callId          : String,

    /** Full transcript text assembled by [SttManager] so far. */
    val transcript      : String,

    /** BCP-47 language code detected by the STT engine. */
    val language        : String           = "en-US",

    /** Optional absolute path to a recording file for audio-level inference. */
    val audioFilePath   : String?          = null,

    /** Caller's phone number (passed to the model for known-scam-number lookup). */
    val callerNumber    : String?          = null,

    /** Duration of the call so far, in ms. */
    val durationMs      : Long             = 0L,

    /** Index of the audio chunk that triggered this analysis pass. */
    val chunkIndex      : Int              = 0,

    /** True when this is triggered by the final chunk of the call. */
    val isFinalPass     : Boolean          = true,

    /** Additional key/value context (model-specific; passed in [metadata]). */
    val extraContext    : Map<String, String> = emptyMap()
)

// ─────────────────────────────────────────────────────────────────────────────
// Result
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Raw output from [FraudDetectionEngine.analyse].
 *
 * Converted to [FraudAnalysis] (the domain model) by [FraudDetectionManager]
 * before being persisted or sent to the UI.
 */
data class FraudDetectionEngineResult(
    /** Whether the engine predicts this is a fraudulent call. */
    val isFraud         : Boolean,

    /**
     * Continuous fraud score (0.0 = definitely safe, 1.0 = definitely fraud).
     * This is the raw model output; maps to [FraudAnalysis.confidence].
     */
    val riskScore       : Float,

    /**
     * Categorical risk level string from the model.
     * Expected values: "SAFE" | "LOW" | "MEDIUM" | "HIGH" | "CRITICAL".
     * Parsed to [RiskLevel] by [FraudDetectionManager].
     */
    val riskLevel       : String,

    /** Human-readable primary reason for the prediction. */
    val reason          : String,

    /**
     * Detailed signal breakdown.  May be empty if the model only returns a
     * top-level reason (the [reason] field is used as a single flag in that case).
     */
    val signals         : List<FraudSignal>  = emptyList(),

    /** Model version string — logged for traceability. */
    val modelVersion    : String             = "unknown",

    /** Server-side timestamp when the model produced this result (epoch ms, or 0). */
    val analysedAtMs    : Long               = System.currentTimeMillis()
) {
    companion object {
        /** Safe prediction — used as fallback when the engine fails non-fatally. */
        fun safe(reason: String = "No fraud signals detected") = FraudDetectionEngineResult(
            isFraud   = false,
            riskScore = 0f,
            riskLevel = RiskLevel.SAFE.name,
            reason    = reason
        )
    }
}

/**
 * One fraud signal detected by the model.
 *
 * Mirrors the [FraudFlag] domain model but uses raw strings so the engine
 * layer stays decoupled from the domain enum set.
 */
data class FraudSignal(
    /** Signal category — should match a [FraudFlagType] name where possible. */
    val type       : String,
    /** Human-readable description of the signal. */
    val description: String,
    /** Severity weight in [0, 1]. */
    val severity   : Float
)

// ─────────────────────────────────────────────────────────────────────────────
// Exception
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Thrown by [FraudDetectionEngine] on a hard failure.
 */
class FraudDetectionEngineException(
    message      : String,
    cause        : Throwable? = null,
    /** HTTP status code if the failure was a network/API error. */
    val httpCode : Int?       = null,
    /** Whether retrying the request might succeed. */
    val retryable: Boolean    = false
) : Exception(message, cause)
