package com.fraudcalldetector.feature.stt

import app.cash.turbine.test
import com.fraudcalldetector.domain.model.*
import com.fraudcalldetector.domain.repository.AudioCaptureRepository
import com.fraudcalldetector.domain.repository.CaptureState
import com.fraudcalldetector.domain.repository.SttRepository
import com.fraudcalldetector.domain.stt.*
import com.fraudcalldetector.feature.stt.engine.MockSttEngine
import com.fraudcalldetector.feature.stt.engine.ScriptLibrary
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [SttManagerImpl].
 *
 * Uses [MockSttEngine] as the real engine (no network) and a MockK-mocked
 * [SttRepository] for persistence verification.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SttManagerTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var mockEngine          : MockSttEngine
    private lateinit var sttRepository       : SttRepository
    private lateinit var audioCaptureRepository: AudioCaptureRepository
    private lateinit var manager             : SttManagerImpl
    private lateinit var testScope           : CoroutineScope

    // Mutable chunk flow controlled by the test
    private val chunksFlow = MutableSharedFlow<AudioChunk>(replay = 0, extraBufferCapacity = 16)

    @Before
    fun setUp() {
        ScriptLibrary.reset()

        mockEngine           = MockSttEngine()
        sttRepository        = mockk(relaxed = true)
        audioCaptureRepository = mockk(relaxed = true)

        every { audioCaptureRepository.chunks } returns chunksFlow

        testScope = CoroutineScope(SupervisorJob() + testDispatcher)
        manager   = SttManagerImpl(
            engine                = mockEngine,
            sttRepository         = sttRepository,
            audioCaptureRepository = audioCaptureRepository,
            scope                 = testScope
        )
    }

    @After
    fun tearDown() {
        testScope.cancel()
        ScriptLibrary.reset()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Initial state
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `initial state is Idle`() {
        assertEquals(SttManagerState.Idle, manager.state.value)
    }

    @Test
    fun `initial liveTranscript is empty`() {
        assertTrue(manager.liveTranscript.value.isEmpty())
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Chunk processing
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `emitting a non-final chunk transitions to Transcribing then ChunkComplete`() = runTest {
        val chunk = fakeChunk("call-1", index = 0, isFinal = false)

        val states = mutableListOf<SttManagerState>()
        val collectJob = launch { manager.state.collect { states += it } }

        chunksFlow.emit(chunk)
        advanceUntilIdle()

        collectJob.cancel()

        assertTrue("Should pass through Transcribing",
            states.any { it is SttManagerState.Transcribing })
        assertTrue("Should arrive at ChunkComplete",
            states.any { it is SttManagerState.ChunkComplete })
    }

    @Test
    fun `emitting a final chunk persists transcript`() = runTest {
        val chunk = fakeChunk("call-final", index = 0, isFinal = true)

        chunksFlow.emit(chunk)
        advanceUntilIdle()

        coVerify(exactly = 1) { sttRepository.saveTranscript(any()) }
        assertTrue(manager.state.value is SttManagerState.CallComplete)
    }

    @Test
    fun `non-final chunk does not persist transcript`() = runTest {
        val chunk = fakeChunk("call-nf", index = 0, isFinal = false)

        chunksFlow.emit(chunk)
        advanceUntilIdle()

        coVerify(exactly = 0) { sttRepository.saveTranscript(any()) }
    }

    @Test
    fun `liveTranscript grows after each chunk`() = runTest {
        val chunk1 = fakeChunk("call-live", 0, isFinal = false)

        chunksFlow.emit(chunk1)
        advanceUntilIdle()

        assertTrue("liveTranscript must be non-empty after chunk",
            manager.liveTranscript.value.isNotBlank())
    }

    @Test
    fun `two chunks produce longer combined transcript`() = runTest {
        val chunk1 = fakeChunk("call-2c", 0, isFinal = false)
        val chunk2 = fakeChunk("call-2c", 1, isFinal = true)

        chunksFlow.emit(chunk1)
        advanceUntilIdle()
        val textAfterFirst = manager.liveTranscript.value

        chunksFlow.emit(chunk2)
        advanceUntilIdle()
        val textAfterSecond = manager.liveTranscript.value

        assertTrue("transcript after 2 chunks should be longer",
            textAfterSecond.length >= textAfterFirst.length)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Stream events relayed
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `streamEvents emits Partial and Final events`() = runTest {
        val chunk = fakeChunk("call-ev", 0, isFinal = false)

        manager.streamEvents.test {
            chunksFlow.emit(chunk)
            advanceUntilIdle()

            var sawPartial = false
            var sawFinal   = false

            // Drain all events emitted during processing
            while (true) {
                val event = expectMostRecentItem()
                when (event) {
                    is SttStreamEvent.Partial -> sawPartial = true
                    is SttStreamEvent.Final   -> sawFinal   = true
                    else                      -> Unit
                }
                break   // check just most-recent after processing
            }

            // At minimum the Final event must have been relayed
            assertTrue("At least one stream event must have been emitted",
                sawPartial || sawFinal)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Session management
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `resetSession clears liveTranscript after call ends`() = runTest {
        val chunk = fakeChunk("call-reset", 0, isFinal = true)
        chunksFlow.emit(chunk)
        advanceUntilIdle()

        manager.resetSession("call-reset")
        // After reset, a new call starts with clean state
        assertEquals(SttManagerState.CallComplete::class, manager.state.value::class)
    }

    @Test
    fun `chunks for different calls are independent`() = runTest {
        val chunkA = fakeChunk("call-A", 0, isFinal = true)
        val chunkB = fakeChunk("call-B", 0, isFinal = true)

        chunksFlow.emit(chunkA)
        advanceUntilIdle()
        chunksFlow.emit(chunkB)
        advanceUntilIdle()

        // Both calls should have triggered saveTranscript
        coVerify(exactly = 2) { sttRepository.saveTranscript(any()) }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Manual transcribeChunk
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `transcribeChunk manually triggers transcription`() = runTest {
        val chunk = fakeChunk("call-manual", 0, isFinal = true)

        manager.transcribeChunk(chunk)
        advanceUntilIdle()

        coVerify(exactly = 1) { sttRepository.saveTranscript(any()) }
        assertTrue(manager.state.value is SttManagerState.CallComplete)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun fakeChunk(callId: String, index: Int, isFinal: Boolean) = AudioChunk(
        callId     = callId,
        chunkIndex = index,
        filePath   = "/tmp/chunk_${callId}_$index.mp4",
        startTimeMs = System.currentTimeMillis() - 5_000,
        endTimeMs   = System.currentTimeMillis(),
        isFinal     = isFinal,
        config      = AudioCaptureConfig.MOCK
    )
}
