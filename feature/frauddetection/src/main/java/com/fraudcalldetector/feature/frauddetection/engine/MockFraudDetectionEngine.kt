package com.fraudcalldetector.feature.frauddetection.engine

import com.fraudcalldetector.domain.fraud.*
import com.fraudcalldetector.domain.model.RiskLevel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import timber.log.Timber
import javax.inject.Inject

/**
 * Deterministic [FraudDetectionEngine] for unit tests, CI, and offline development.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * Prediction logic
 * ─────────────────────────────────────────────────────────────────────────────
 * Scores the transcript by counting keyword matches against a built-in
 * [KeywordRules] table.  Each rule carries a weight and an associated
 * [FraudSignal].  The final [riskScore] is the sum of triggered weights,
 * clamped to 1.0.
 *
 *  score ≥ 0.85 → CRITICAL
 *  score ≥ 0.65 → HIGH
 *  score ≥ 0.40 → MEDIUM
 *  score ≥ 0.15 → LOW
 *  score <  0.15 → SAFE
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * Swapping in the real engine
 * ─────────────────────────────────────────────────────────────────────────────
 * In [FraudDetectionModule] change:
 *   @Binds abstract fun bindEngine(impl: MockFraudDetectionEngine): FraudDetectionEngine
 * to:
 *   @Binds abstract fun bindEngine(impl: RemoteFraudDetectionEngine): FraudDetectionEngine
 */
class MockFraudDetectionEngine @Inject constructor() : FraudDetectionEngine {

    override val supportsIncrementalAnalysis: Boolean = false

    // ─────────────────────────────────────────────────────────────────────────
    // FraudDetectionEngine — synchronous
    // ─────────────────────────────────────────────────────────────────────────

    override suspend fun analyse(request: FraudDetectionRequest): FraudDetectionEngineResult {
        require(request.callId.isNotBlank()) { "callId must not be blank" }
        Timber.d("MockFraudDetectionEngine.analyse callId=${request.callId}")

        delay(MOCK_LATENCY_MS)   // simulate network latency

        return score(request)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // FraudDetectionEngine — streaming
    // ─────────────────────────────────────────────────────────────────────────

    /** No incremental support — emits one result per request in the flow. */
    override fun analyseStream(
        requests: Flow<FraudDetectionRequest>
    ): Flow<FraudDetectionEngineResult> = flow {
        requests.collect { request ->
            try {
                emit(analyse(request))
            } catch (e: FraudDetectionEngineException) {
                throw e
            } catch (e: Exception) {
                throw FraudDetectionEngineException("Mock stream error: ${e.message}", e)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Scoring
    // ─────────────────────────────────────────────────────────────────────────

    private fun score(request: FraudDetectionRequest): FraudDetectionEngineResult {
        val text    = request.transcript.lowercase()
        val matched = KeywordRules.ALL.filter { rule ->
            rule.keywords.any { kw -> text.contains(kw) }
        }

        val rawScore = matched.sumOf { it.weight.toDouble() }.toFloat().coerceAtMost(1f)

        val riskLevel = when {
            rawScore >= 0.85f -> RiskLevel.CRITICAL
            rawScore >= 0.65f -> RiskLevel.HIGH
            rawScore >= 0.40f -> RiskLevel.MEDIUM
            rawScore >= 0.15f -> RiskLevel.LOW
            else              -> RiskLevel.SAFE
        }

        val isFraud = riskLevel >= RiskLevel.MEDIUM   // treat MEDIUM+ as fraud

        val signals = matched.map { rule ->
            FraudSignal(
                type        = rule.flagType,
                description = rule.description,
                severity    = rule.weight
            )
        }

        val reason = when {
            matched.isNotEmpty() -> matched.maxByOrNull { it.weight }!!.description
            else                 -> "No fraud signals detected"
        }

        Timber.d("MockFraudDetectionEngine: score=$rawScore riskLevel=$riskLevel isFraud=$isFraud signals=${signals.size}")

        return FraudDetectionEngineResult(
            isFraud      = isFraud,
            riskScore    = rawScore,
            riskLevel    = riskLevel.name,
            reason       = reason,
            signals      = signals,
            modelVersion = "mock-v1.0"
        )
    }

    companion object {
        const val MOCK_LATENCY_MS: Long = 250L
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Keyword rule table
// ─────────────────────────────────────────────────────────────────────────────

data class KeywordRule(
    /** Keyword phrases to look for (case-insensitive). */
    val keywords    : List<String>,
    /** Fraud score contribution (0.0–1.0). */
    val weight      : Float,
    /** Matching FraudFlagType name. */
    val flagType    : String,
    /** Human-readable signal description. */
    val description : String
)

object KeywordRules {
    val ALL: List<KeywordRule> = listOf(
        KeywordRule(
            keywords    = listOf("otp", "one-time password", "one time password", "verification code"),
            weight      = 0.45f,
            flagType    = "SUSPICIOUS_KEYWORDS",
            description = "Suspicious OTP / verification code request"
        ),
        KeywordRule(
            keywords    = listOf("social security", "ssn", "national id"),
            weight      = 0.55f,
            flagType    = "SUSPICIOUS_KEYWORDS",
            description = "Social security or national ID solicitation"
        ),
        KeywordRule(
            keywords    = listOf("bank account", "account number", "routing number", "pin", "cvv"),
            weight      = 0.50f,
            flagType    = "SUSPICIOUS_KEYWORDS",
            description = "Banking credential solicitation"
        ),
        KeywordRule(
            keywords    = listOf("irs", "tax", "unpaid taxes", "tax refund", "legal action"),
            weight      = 0.40f,
            flagType    = "SUSPICIOUS_KEYWORDS",
            description = "IRS / tax authority impersonation"
        ),
        KeywordRule(
            keywords    = listOf("microsoft", "tech support", "computer virus", "remote access", "anydesk", "teamviewer"),
            weight      = 0.45f,
            flagType    = "AI_DETECTED_MANIPULATION",
            description = "Tech-support scam pattern detected"
        ),
        KeywordRule(
            keywords    = listOf("amazon", "your order", "package", "delivery issue"),
            weight      = 0.30f,
            flagType    = "SUSPICIOUS_KEYWORDS",
            description = "Parcel / e-commerce impersonation"
        ),
        KeywordRule(
            keywords    = listOf("immediately", "urgent", "right now", "act now", "do not hang up", "do not tell"),
            weight      = 0.25f,
            flagType    = "AI_DETECTED_MANIPULATION",
            description = "High-urgency / pressure language"
        ),
        KeywordRule(
            keywords    = listOf("gift card", "itunes card", "google play card", "wire transfer"),
            weight      = 0.55f,
            flagType    = "UNUSUAL_PATTERNS",
            description = "Gift card or wire transfer payment demand"
        ),
        KeywordRule(
            keywords    = listOf("warrant", "arrest", "police", "lawsuit", "court"),
            weight      = 0.35f,
            flagType    = "AI_DETECTED_MANIPULATION",
            description = "Legal-threat intimidation language"
        ),
        KeywordRule(
            keywords    = listOf("lottery", "won", "prize", "claim your reward"),
            weight      = 0.40f,
            flagType    = "UNUSUAL_PATTERNS",
            description = "Advance-fee / lottery scam language"
        ),
        KeywordRule(
            keywords    = listOf("compromised", "suspicious activity", "fraud department", "security alert"),
            weight      = 0.35f,
            flagType    = "SUSPICIOUS_KEYWORDS",
            description = "Fake fraud alert / account compromise claim"
        )
    )
}
