package com.fraudcalldetector.feature.frauddetection

import app.cash.turbine.test
import com.fraudcalldetector.domain.fraud.*
import com.fraudcalldetector.domain.model.*
import com.fraudcalldetector.domain.repository.CallRepository
import com.fraudcalldetector.domain.repository.FraudDetectionRepository
import com.fraudcalldetector.domain.stt.SttManager
import com.fraudcalldetector.domain.stt.SttManagerState
import com.fraudcalldetector.feature.notifications.FraudNotificationManager
import io.mockk.*
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [FraudDetectionManagerImpl].
 *
 * Covers:
 *  • auto-trigger on SttManagerState.CallComplete
 *  • state transitions: Idle → Analysing → Complete
 *  • results flow emits FraudAnalysisResult on completion
 *  • FraudDetectionRepository.saveAnalysis is called
 *  • CallRepository.updateCall is called with analysis attached
 *  • FraudNotificationManager.showFraudAlert called for HIGH+ risk
 *  • FraudNotificationManager NOT called for SAFE / LOW / MEDIUM result
 *  • blank transcript short-circuits analysis (no engine call)
 *  • analyseCall() public API returns FraudAnalysis
 *  • state transitions to Failed when engine throws
 *  • cancelAnalysis() is a no-op (does not throw)
 */
class FraudDetectionManagerTest {

    // ─────────────────────────────────────────────────────────────────────────
    // Test dispatcher
    // ─────────────────────────────────────────────────────────────────────────

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var testScope: CoroutineScope

    // ─────────────────────────────────────────────────────────────────────────
    // Mocks
    // ─────────────────────────────────────────────────────────────────────────

    private val engine              = mockk<FraudDetectionEngine>(relaxUnitFun = true)
    private val fraudRepository     = mockk<FraudDetectionRepository>(relaxUnitFun = true)
    private val callRepository      = mockk<CallRepository>(relaxUnitFun = true)
    private val notificationManager = mockk<FraudNotificationManager>(relaxUnitFun = true)

    private val sttStateFlow = MutableStateFlow<SttManagerState>(SttManagerState.Idle)
    private val sttManager   = mockk<SttManager> {
        every { state } returns sttStateFlow
    }

    private lateinit var manager: FraudDetectionManagerImpl

    // ─────────────────────────────────────────────────────────────────────────
    // Test data
    // ─────────────────────────────────────────────────────────────────────────

    private val callId     = "call-test-001"
    private val transcript = "Please share your OTP immediately to avoid account suspension."

    private val safeEngineResult = FraudDetectionEngineResult(
        isFraud      = false,
        riskScore    = 0.05f,
        riskLevel    = RiskLevel.SAFE.name,
        reason       = "No fraud signals detected",
        signals      = emptyList(),
        modelVersion = "mock-v1.0"
    )

    private val highEngineResult = FraudDetectionEngineResult(
        isFraud      = true,
        riskScore    = 0.70f,
        riskLevel    = RiskLevel.HIGH.name,
        reason       = "Suspicious OTP request and urgency pressure",
        signals      = listOf(
            FraudSignal("SUSPICIOUS_KEYWORDS", "OTP request detected",   0.45f),
            FraudSignal("AI_DETECTED_MANIPULATION", "Urgency pressure",  0.25f)
        ),
        modelVersion = "mock-v1.0"
    )

    private val criticalEngineResult = FraudDetectionEngineResult(
        isFraud      = true,
        riskScore    = 1.0f,
        riskLevel    = RiskLevel.CRITICAL.name,
        reason       = "Multiple fraud patterns",
        signals      = listOf(
            FraudSignal("SUSPICIOUS_KEYWORDS", "SSN request", 0.55f),
            FraudSignal("UNUSUAL_PATTERNS",    "Gift card demand", 0.55f)
        ),
        modelVersion = "mock-v1.0"
    )

    private val mockCall = CallEvent(
        id          = callId,
        phoneNumber = "+15551234567",
        callerName  = "Unknown",
        direction   = CallDirection.INBOUND,
        state       = CallState.ENDED,
        startTime   = System.currentTimeMillis() - 60_000L,
        endTime     = System.currentTimeMillis()
    )

    // ─────────────────────────────────────────────────────────────────────────
    // Setup / teardown
    // ─────────────────────────────────────────────────────────────────────────

    @Before
    fun setUp() {
        testScope = CoroutineScope(SupervisorJob() + testDispatcher)

        coEvery { callRepository.getCallById(callId) } returns mockCall
        coEvery { callRepository.updateCall(any()) }   just Runs
        coEvery { fraudRepository.saveAnalysis(any(), any()) } just Runs
        coEvery { notificationManager.showFraudAlert(any(), any(), any()) } just Runs

        manager = FraudDetectionManagerImpl(
            engine              = engine,
            fraudRepository     = fraudRepository,
            callRepository      = callRepository,
            sttManager          = sttManager,
            notificationManager = notificationManager,
            scope               = testScope
        )
    }

    @After
    fun tearDown() {
        testScope.coroutineContext[SupervisorJob]?.cancel()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Auto-trigger tests
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `auto-triggers analysis when SttManager emits CallComplete`() = runTest(testDispatcher) {
        coEvery { engine.analyse(any()) } returns highEngineResult

        manager.state.test {
            assertEquals(FraudDetectionState.Idle, awaitItem())

            // Trigger via SttManager
            sttStateFlow.value = SttManagerState.CallComplete(callId, transcript)
            advanceUntilIdle()

            val analysing = awaitItem()
            assertTrue(analysing is FraudDetectionState.Analysing)
            assertEquals(callId, (analysing as FraudDetectionState.Analysing).callId)

            val complete = awaitItem()
            assertTrue(complete is FraudDetectionState.Complete)
            assertEquals(callId, (complete as FraudDetectionState.Complete).callId)
            assertTrue(complete.analysis.isFraud)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `auto-trigger emits FraudAnalysisResult on results flow`() = runTest(testDispatcher) {
        coEvery { engine.analyse(any()) } returns highEngineResult

        manager.results.test {
            sttStateFlow.value = SttManagerState.CallComplete(callId, transcript)
            advanceUntilIdle()

            val result = awaitItem()
            assertEquals(callId, result.callId)
            assertTrue(result.analysis.isFraud)
            assertEquals(RiskLevel.HIGH, result.analysis.riskLevel)

            cancelAndIgnoreRemainingEvents()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Persistence tests
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `saveAnalysis is called after successful engine run`() = runTest(testDispatcher) {
        coEvery { engine.analyse(any()) } returns safeEngineResult

        sttStateFlow.value = SttManagerState.CallComplete(callId, "hello world")
        advanceUntilIdle()

        coVerify(exactly = 1) { fraudRepository.saveAnalysis(eq(callId), any()) }
    }

    @Test
    fun `updateCall is called with analysis attached to call`() = runTest(testDispatcher) {
        coEvery { engine.analyse(any()) } returns highEngineResult

        sttStateFlow.value = SttManagerState.CallComplete(callId, transcript)
        advanceUntilIdle()

        coVerify(exactly = 1) {
            callRepository.updateCall(
                withArg { updatedCall ->
                    assertNotNull(updatedCall.fraudAnalysis)
                    assertTrue(updatedCall.fraudAnalysis!!.isFraud)
                }
            )
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Notification tests
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `notification shown for HIGH risk result`() = runTest(testDispatcher) {
        coEvery { engine.analyse(any()) } returns highEngineResult

        sttStateFlow.value = SttManagerState.CallComplete(callId, transcript)
        advanceUntilIdle()

        coVerify(exactly = 1) {
            notificationManager.showFraudAlert(eq(callId), any(), any())
        }
    }

    @Test
    fun `notification shown for CRITICAL risk result`() = runTest(testDispatcher) {
        coEvery { engine.analyse(any()) } returns criticalEngineResult

        sttStateFlow.value = SttManagerState.CallComplete(callId, "Buy gift cards and share ssn now.")
        advanceUntilIdle()

        coVerify(exactly = 1) {
            notificationManager.showFraudAlert(eq(callId), any(), any())
        }
    }

    @Test
    fun `notification NOT shown for SAFE result`() = runTest(testDispatcher) {
        coEvery { engine.analyse(any()) } returns safeEngineResult

        sttStateFlow.value = SttManagerState.CallComplete(callId, "Hello, how are you today?")
        advanceUntilIdle()

        coVerify(exactly = 0) { notificationManager.showFraudAlert(any(), any(), any()) }
    }

    @Test
    fun `notification NOT shown for MEDIUM result`() = runTest(testDispatcher) {
        val mediumResult = safeEngineResult.copy(
            riskScore = 0.45f,
            riskLevel = RiskLevel.MEDIUM.name,
            isFraud   = true
        )
        coEvery { engine.analyse(any()) } returns mediumResult

        sttStateFlow.value = SttManagerState.CallComplete(callId, transcript)
        advanceUntilIdle()

        coVerify(exactly = 0) { notificationManager.showFraudAlert(any(), any(), any()) }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Edge case: blank transcript
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `blank transcript skips engine and returns SAFE analysis`() = runTest(testDispatcher) {
        sttStateFlow.value = SttManagerState.CallComplete(callId, "   ")
        advanceUntilIdle()

        coVerify(exactly = 0) { engine.analyse(any()) }
        // State stays Idle (no Analysing transition for blank input)
        assertEquals(FraudDetectionState.Idle, manager.state.value)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public analyseCall() API
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `analyseCall returns correct FraudAnalysis`() = runTest(testDispatcher) {
        coEvery { engine.analyse(any()) } returns highEngineResult

        val analysis = manager.analyseCall(
            callId     = callId,
            transcript = transcript
        )

        assertTrue(analysis.isFraud)
        assertEquals(RiskLevel.HIGH, analysis.riskLevel)
        assertEquals(0.70f, analysis.confidence, 0.001f)
    }

    @Test
    fun `analyseCall transitions state to Analysing then Complete`() = runTest(testDispatcher) {
        coEvery { engine.analyse(any()) } returns safeEngineResult

        manager.state.test {
            assertEquals(FraudDetectionState.Idle, awaitItem())

            // Run in background
            val job = kotlinx.coroutines.launch(testDispatcher) {
                manager.analyseCall(callId = callId, transcript = "Hello there")
            }
            advanceUntilIdle()

            val states = mutableListOf<FraudDetectionState>()
            // Collect any remaining items
            while (true) {
                val item = expectMostRecentItem()
                states.add(item)
                if (item is FraudDetectionState.Complete || item is FraudDetectionState.Failed) break
            }
            assertTrue(states.last() is FraudDetectionState.Complete)
            job.join()
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Error handling
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `engine exception transitions state to Failed`() = runTest(testDispatcher) {
        coEvery { engine.analyse(any()) } throws FraudDetectionEngineException("API timeout")

        manager.state.test {
            assertEquals(FraudDetectionState.Idle, awaitItem())

            sttStateFlow.value = SttManagerState.CallComplete(callId, transcript)
            advanceUntilIdle()

            // Skip Analysing state
            skipItems(1)

            val failed = awaitItem()
            assertTrue(failed is FraudDetectionState.Failed)
            assertEquals(callId, (failed as FraudDetectionState.Failed).callId)
            assertTrue(failed.message.contains("API timeout"))

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `unexpected exception transitions state to Failed`() = runTest(testDispatcher) {
        coEvery { engine.analyse(any()) } throws RuntimeException("Unexpected crash")

        sttStateFlow.value = SttManagerState.CallComplete(callId, transcript)
        advanceUntilIdle()

        val state = manager.state.value
        assertTrue(state is FraudDetectionState.Failed)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // cancelAnalysis
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `cancelAnalysis does not throw`() {
        // Should be a no-op, not throw
        assertDoesNotThrow { manager.cancelAnalysis(callId) }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helper
    // ─────────────────────────────────────────────────────────────────────────

    private fun assertDoesNotThrow(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            fail("Expected no exception but got: ${e.message}")
        }
    }
}
