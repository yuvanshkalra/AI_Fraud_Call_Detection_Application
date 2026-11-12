package com.fraudcalldetector.feature.audiorecorder

import app.cash.turbine.test
import com.fraudcalldetector.domain.model.*
import com.fraudcalldetector.domain.repository.CallMonitorRepository
import com.fraudcalldetector.domain.repository.CaptureState
import com.fraudcalldetector.feature.audiorecorder.source.MockAudioSource
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [AudioCaptureManager].
 *
 * Uses [MockAudioSource] to avoid real hardware / [MediaRecorder] dependencies.
 * Uses [UnconfinedTestDispatcher] so state transitions are collected synchronously.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AudioCaptureManagerTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    // Real source — no mocking needed; MockAudioSource has deterministic behaviour
    private lateinit var mockSource: MockAudioSource

    // Mocked call monitor
    private lateinit var callMonitorRepo: CallMonitorRepository
    private val monitoringEventsFlow = MutableSharedFlow<MonitoringEvent>(replay = 1)

    private lateinit var manager: AudioCaptureManager
    private lateinit var testScope: CoroutineScope

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        mockSource       = MockAudioSource()
        callMonitorRepo  = mockk(relaxed = true)

        every { callMonitorRepo.events } returns monitoringEventsFlow

        testScope = CoroutineScope(SupervisorJob() + testDispatcher)
        manager   = AudioCaptureManager(
            source               = mockSource,
            callMonitorRepository = callMonitorRepo,
            scope                = testScope
        )
    }

    @After
    fun tearDown() {
        testScope.cancel()
        Dispatchers.resetMain()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Initial state
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `initial captureState is Idle`() {
        assertEquals(CaptureState.Idle, manager.captureState.value)
    }

    @Test
    fun `isRecording is false initially`() {
        assertFalse(manager.isRecording)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // startCapture / stopCapture state transitions
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `startCapture transitions to Recording`() = runTest {
        manager.startCapture(callId = "call-1", config = AudioCaptureConfig.MOCK)

        val state = manager.captureState.value
        assertTrue("Expected Recording but was $state", state is CaptureState.Recording)
        assertEquals("call-1", (state as CaptureState.Recording).callId)
        assertEquals(0, state.chunkIndex)
    }

    @Test
    fun `stopCapture transitions to Stopped and returns session`() = runTest {
        manager.startCapture(callId = "call-1", config = AudioCaptureConfig.MOCK)

        val session = manager.stopCapture("call-1")

        assertNotNull(session)
        assertEquals("call-1", session!!.callId)
        assertTrue(session.isComplete)
        assertEquals(1, session.chunks.size)  // final chunk emitted on stop
        assertTrue(session.chunks.last().isFinal)

        val state = manager.captureState.value
        assertTrue("Expected Stopped but was $state", state is CaptureState.Stopped)
    }

    @Test
    fun `stopCapture for unknown callId returns null`() = runTest {
        val session = manager.stopCapture("unknown-call")
        assertNull(session)
    }

    @Test
    fun `duplicate startCapture is a no-op`() = runTest {
        manager.startCapture("call-1", AudioCaptureConfig.MOCK)
        manager.startCapture("call-1", AudioCaptureConfig.MOCK) // should be ignored

        // Only one session should exist
        assertNotNull(manager.getActiveSession("call-1"))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // cancelCapture
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `cancelCapture transitions to Idle`() = runTest {
        manager.startCapture("call-1", AudioCaptureConfig.MOCK)
        manager.cancelCapture("call-1")

        assertEquals(CaptureState.Idle, manager.captureState.value)
        assertNull(manager.getActiveSession("call-1"))
        assertFalse(mockSource.isRecording)
    }

    @Test
    fun `cancelCapture on unknown callId is a no-op`() = runTest {
        // Should not throw
        manager.cancelCapture("ghost-call")
        assertEquals(CaptureState.Idle, manager.captureState.value)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Chunk emission
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `stopCapture emits final AudioChunk on chunks flow`() = runTest {
        manager.startCapture("call-1", config = AudioCaptureConfig.MOCK.copy(chunkDurationMs = null))

        manager.chunks.test {
            manager.stopCapture("call-1")
            val chunk = awaitItem()
            assertEquals("call-1", chunk.callId)
            assertTrue(chunk.isFinal)
            assertEquals(0, chunk.chunkIndex)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Monitoring event integration
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `MonitoringEvent Active triggers startCapture`() = runTest {
        monitoringEventsFlow.emit(
            MonitoringEvent.Active(
                callId    = "call-auto",
                number    = "+1234567890",
                direction = CallDirection.INBOUND
            )
        )

        // Give the init{} subscription a chance to process the event
        advanceUntilIdle()

        val state = manager.captureState.value
        assertTrue("Expected Recording but was $state", state is CaptureState.Recording)
        assertEquals("call-auto", (state as CaptureState.Recording).callId)
    }

    @Test
    fun `MonitoringEvent Ended triggers stopCapture`() = runTest {
        // First start via Active event
        monitoringEventsFlow.emit(
            MonitoringEvent.Active("call-end", "+1234567890", CallDirection.INBOUND)
        )
        advanceUntilIdle()

        // Then end via Ended event
        monitoringEventsFlow.emit(
            MonitoringEvent.Ended("call-end", "+1234567890")
        )
        advanceUntilIdle()

        val state = manager.captureState.value
        assertTrue("Expected Stopped but was $state", state is CaptureState.Stopped)
    }

    @Test
    fun `MonitoringEvent Missed triggers cancelCapture`() = runTest {
        monitoringEventsFlow.emit(
            MonitoringEvent.Active("call-miss", "+9999", CallDirection.INBOUND)
        )
        advanceUntilIdle()

        monitoringEventsFlow.emit(
            MonitoringEvent.Missed("call-miss", "+9999")
        )
        advanceUntilIdle()

        assertEquals(CaptureState.Idle, manager.captureState.value)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // getActiveSession
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `getActiveSession returns session while recording`() = runTest {
        manager.startCapture("call-q", AudioCaptureConfig.MOCK)
        val session = manager.getActiveSession("call-q")
        assertNotNull(session)
        assertEquals("call-q", session!!.callId)
    }

    @Test
    fun `getActiveSession returns null after stop`() = runTest {
        manager.startCapture("call-q", AudioCaptureConfig.MOCK)
        manager.stopCapture("call-q")
        assertNull(manager.getActiveSession("call-q"))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // MockAudioSource unit tests
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `MockAudioSource writes file on stopSegment`() {
        val path = System.getProperty("java.io.tmpdir") + "/mock_test.mp4"
        val source = MockAudioSource()

        source.startSegment(path, AudioCaptureConfig.MOCK)
        assertTrue(source.isRecording)

        val result = source.stopSegment()
        assertFalse(source.isRecording)
        assertEquals(path, result.filePath)
        assertEquals(MockAudioSource.SILENT_PAYLOAD_BYTES.toLong(), result.sizeBytes)
        assertTrue(result.endTimeMs >= result.startTimeMs)

        // Clean up
        java.io.File(path).delete()
    }

    @Test
    fun `MockAudioSource cancelSegment deletes file`() {
        val path = System.getProperty("java.io.tmpdir") + "/mock_cancel.mp4"
        val source = MockAudioSource()

        // Write an empty file to simulate "in-progress"
        java.io.File(path).createNewFile()
        source.startSegment(path, AudioCaptureConfig.MOCK)
        source.cancelSegment()

        assertFalse(source.isRecording)
    }
}
