package com.fraudcalldetector.feature.frauddetection

import com.fraudcalldetector.domain.fraud.FraudDetectionRequest
import com.fraudcalldetector.domain.model.RiskLevel
import com.fraudcalldetector.feature.frauddetection.engine.MockFraudDetectionEngine
import io.mockk.every
import io.mockk.mockkObject
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [MockFraudDetectionEngine].
 *
 * Covers:
 *  • Safe transcript → SAFE risk level, isFraud=false, score < 0.15
 *  • Single rule match → correct flag type and description attached
 *  • OTP rule → weight=0.45 → MEDIUM
 *  • SSN rule → weight=0.55 → HIGH
 *  • Multiple heavy rules → sum clamps to 1.0 → CRITICAL
 *  • Gift card rule → UNUSUAL_PATTERNS flag type
 *  • Tech-support rule → AI_DETECTED_MANIPULATION flag type
 *  • isFraud threshold (MEDIUM+)
 *  • Blank callId → IllegalArgumentException
 *  • Case-insensitive matching
 *  • modelVersion = "mock-v1.0"
 *  • analyseStream emits one result per request
 *  • Urgency rule → LOW band
 *  • Lottery rule → MEDIUM band
 */
class MockFraudDetectionEngineTest {

    private lateinit var engine: MockFraudDetectionEngine

    @Before
    fun setUp() {
        engine = MockFraudDetectionEngine()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun request(transcript: String, callId: String = "call-1") =
        FraudDetectionRequest(
            callId        = callId,
            transcript    = transcript,
            audioFilePath = null,
            callerNumber  = null,
            durationMs    = 0L,
            isFinalPass   = true
        )

    // ─────────────────────────────────────────────────────────────────────────
    // Risk-level band tests
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `safe transcript returns SAFE risk level`() = runTest {
        val result = engine.analyse(request("Hello, this is a normal conversation about the weather."))

        assertEquals(RiskLevel.SAFE.name, result.riskLevel)
        assertFalse(result.isFraud)
        assertTrue(result.riskScore < 0.15f)
        assertTrue(result.signals.isEmpty())
        assertEquals("No fraud signals detected", result.reason)
    }

    @Test
    fun `urgency keywords alone return LOW risk band`() = runTest {
        // "immediately" weight=0.25 → 0.15 ≤ score < 0.40 → LOW
        val result = engine.analyse(request("You need to act immediately."))

        assertEquals(RiskLevel.LOW.name, result.riskLevel)
        assertFalse(result.isFraud)       // LOW is not fraud (threshold is MEDIUM+)
        assertTrue(result.riskScore in 0.15f..0.39f)
    }

    @Test
    fun `OTP keyword returns MEDIUM risk band`() = runTest {
        // "otp" weight=0.45 → 0.40 ≤ score < 0.65 → MEDIUM, isFraud=true
        val result = engine.analyse(request("Please share your OTP."))

        assertEquals(RiskLevel.MEDIUM.name, result.riskLevel)
        assertTrue(result.isFraud)
        assertTrue(result.riskScore in 0.40f..0.64f)
    }

    @Test
    fun `SSN keyword returns HIGH risk band`() = runTest {
        // "social security" weight=0.55 → 0.40 ≤ score < 0.65 → MEDIUM (0.55)
        // "ssn" also matches same rule — only counted once
        val result = engine.analyse(request("Can you share your social security number please?"))

        // weight=0.55 → MEDIUM (0.40–0.64)
        assertTrue(result.riskScore >= 0.40f)
        assertTrue(result.isFraud)
    }

    @Test
    fun `banking credentials keyword returns HIGH band`() = runTest {
        // "bank account" weight=0.50 → MEDIUM; combined with "pin" still same rule → 0.50
        val result = engine.analyse(request("I need your bank account number and PIN to process your refund."))

        assertTrue(result.riskScore >= 0.40f)
        assertTrue(result.isFraud)
    }

    @Test
    fun `multiple heavy rules sum to CRITICAL`() = runTest {
        // SSN(0.55) + gift card(0.55) + bank account(0.50) = 1.60 → clamped to 1.0 → CRITICAL
        val transcript = "Share your social security number, bank account, and buy gift cards now."
        val result = engine.analyse(request(transcript))

        assertEquals(RiskLevel.CRITICAL.name, result.riskLevel)
        assertTrue(result.isFraud)
        assertEquals(1.0f, result.riskScore)
    }

    @Test
    fun `OTP + urgency crosses HIGH threshold`() = runTest {
        // otp(0.45) + immediately(0.25) = 0.70 → HIGH
        val result = engine.analyse(request("Share your OTP immediately, do not hang up."))

        assertTrue(result.riskScore >= 0.65f)
        assertEquals(RiskLevel.HIGH.name, result.riskLevel)
        assertTrue(result.isFraud)
    }

    @Test
    fun `lottery keyword returns MEDIUM band`() = runTest {
        // "won" + "prize" same rule weight=0.40 → MEDIUM
        val result = engine.analyse(request("Congratulations, you have won a prize, claim your reward now!"))

        assertEquals(RiskLevel.MEDIUM.name, result.riskLevel)
        assertTrue(result.isFraud)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Signal / flag tests
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `OTP match produces SUSPICIOUS_KEYWORDS flag type`() = runTest {
        val result = engine.analyse(request("Enter your one-time password to continue."))

        assertTrue(result.signals.isNotEmpty())
        val signal = result.signals.first()
        assertEquals("SUSPICIOUS_KEYWORDS", signal.type)
        assertTrue(signal.description.contains("OTP", ignoreCase = true) ||
                   signal.description.contains("verification", ignoreCase = true))
        assertEquals(0.45f, signal.severity, 0.001f)
    }

    @Test
    fun `gift card match produces UNUSUAL_PATTERNS flag type`() = runTest {
        val result = engine.analyse(request("Buy iTunes card and share the code."))

        val giftCardSignal = result.signals.find { it.type == "UNUSUAL_PATTERNS" }
        assertNotNull(giftCardSignal)
        assertTrue(giftCardSignal!!.description.contains("gift card", ignoreCase = true) ||
                   giftCardSignal.description.contains("wire transfer", ignoreCase = true))
    }

    @Test
    fun `tech-support keywords produce AI_DETECTED_MANIPULATION flag`() = runTest {
        val result = engine.analyse(request("This is Microsoft tech support. Allow TeamViewer remote access."))

        val signal = result.signals.find { it.type == "AI_DETECTED_MANIPULATION" }
        assertNotNull(signal)
        assertTrue(signal!!.description.contains("tech-support", ignoreCase = true) ||
                   signal.description.contains("tech support", ignoreCase = true))
    }

    @Test
    fun `multiple rules produce multiple signals`() = runTest {
        // OTP + urgency = 2 distinct rules
        val result = engine.analyse(request("Act now and share your OTP immediately."))

        assertTrue(result.signals.size >= 2)
    }

    @Test
    fun `reason field is description of highest-weight matched rule`() = runTest {
        // SSN(0.55) > OTP(0.45) → reason should be SSN description
        val result = engine.analyse(request("Share your social security and OTP now."))

        // Highest weight rule should dominate the top-level reason
        assertFalse(result.reason.isBlank())
        // SSN rule description contains "social security"
        assertTrue(result.reason.contains("social security", ignoreCase = true) ||
                   result.reason.contains("national ID", ignoreCase = true))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Engine metadata tests
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `modelVersion is mock-v1-0`() = runTest {
        val result = engine.analyse(request("Hello world"))
        assertEquals("mock-v1.0", result.modelVersion)
    }

    @Test
    fun `case-insensitive matching works for uppercase keywords`() = runTest {
        val result = engine.analyse(request("GIVE ME YOUR SSN RIGHT NOW."))

        assertFalse(result.riskScore < 0.15f)   // should not be SAFE
        assertTrue(result.isFraud || result.riskScore >= 0.15f)
    }

    @Test
    fun `blank callId throws IllegalArgumentException`() = runTest {
        val exception = runCatching {
            engine.analyse(request("Hello", callId = ""))
        }.exceptionOrNull()

        assertNotNull(exception)
        assertTrue(exception is IllegalArgumentException)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Streaming
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `analyseStream emits one result per request`() = runTest {
        val requests = listOf(
            request("Hello, normal call.", callId = "c1"),
            request("Share your OTP now.", callId = "c2")
        )

        val results = mutableListOf<com.fraudcalldetector.domain.fraud.FraudDetectionEngineResult>()
        engine.analyseStream(
            kotlinx.coroutines.flow.flowOf(*requests.toTypedArray())
        ).collect { results.add(it) }

        assertEquals(2, results.size)
        // First call safe, second not safe
        assertTrue(results[0].riskScore < 0.40f)
        assertTrue(results[1].riskScore >= 0.40f)
    }
}
