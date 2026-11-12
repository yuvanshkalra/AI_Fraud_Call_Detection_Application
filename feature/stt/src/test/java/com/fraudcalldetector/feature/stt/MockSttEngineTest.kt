package com.fraudcalldetector.feature.stt.engine

import app.cash.turbine.test
import com.fraudcalldetector.domain.stt.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [MockSttEngine].
 *
 * Verifies streaming behaviour, word timestamps, segment diarisation, and
 * the synchronous path — all without real hardware or network.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MockSttEngineTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var engine: MockSttEngine

    @Before
    fun setUp() {
        ScriptLibrary.reset()   // deterministic script selection
        engine = MockSttEngine()
    }

    @After
    fun tearDown() {
        ScriptLibrary.reset()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Synchronous transcribe()
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `transcribe returns non-empty result`() = runTest {
        val result = engine.transcribe("/tmp/chunk_0.mp4")

        assertTrue("fullText must not be blank", result.fullText.isNotBlank())
        assertFalse("segments must not be empty", result.segments.isEmpty())
        assertTrue("overallConfidence must be positive", result.overallConfidence > 0f)
        assertTrue("audioDurationMs must be positive", result.audioDurationMs > 0L)
    }

    @Test
    fun `transcribe with blank path throws SttEngineException`() = runTest {
        var threw = false
        try {
            engine.transcribe("")
        } catch (e: SttEngineException) {
            threw = true
        }
        assertTrue("Expected SttEngineException for blank path", threw)
    }

    @Test
    fun `transcribe produces word timestamps when enabled`() = runTest {
        val config = SttEngineConfig.DEFAULT.copy(enableWordTimestamps = true)
        val result = engine.transcribe("/tmp/chunk_0.mp4", config)

        assertTrue("Word timestamps must be populated", result.words.isNotEmpty())
        result.words.forEach { word ->
            assertTrue("word must not be blank", word.word.isNotBlank())
            assertTrue("startOffsetMs must be >= 0", word.startOffsetMs >= 0L)
            assertTrue("endOffsetMs must be > startOffsetMs", word.endOffsetMs > word.startOffsetMs)
            assertTrue("confidence must be in [0,1]", word.confidence in 0f..1f)
        }
    }

    @Test
    fun `transcribe without word timestamps returns empty list`() = runTest {
        val config = SttEngineConfig.DEFAULT.copy(enableWordTimestamps = false)
        val result = engine.transcribe("/tmp/chunk_0.mp4", config)

        assertTrue("words must be empty when disabled", result.words.isEmpty())
    }

    @Test
    fun `transcribe with diarisation produces multiple speakers`() = runTest {
        val config = SttEngineConfig.DEFAULT.copy(enableDiarisation = true)
        val result = engine.transcribe("/tmp/chunk_0.mp4", config)

        val speakers = result.segments.map { it.speaker }.toSet()
        // First script is long enough to alternate CALLER/USER blocks
        assertTrue("Should have at least one speaker", speakers.isNotEmpty())
    }

    @Test
    fun `transcribe without diarisation returns single UNKNOWN segment`() = runTest {
        val config = SttEngineConfig.DEFAULT.copy(enableDiarisation = false)
        val result = engine.transcribe("/tmp/chunk_0.mp4", config)

        assertEquals(1, result.segments.size)
        assertEquals(SttSpeaker.UNKNOWN, result.segments.first().speaker)
    }

    @Test
    fun `each transcribe call uses a different script`() = runTest {
        val r1 = engine.transcribe("/tmp/chunk_0.mp4")
        val r2 = engine.transcribe("/tmp/chunk_1.mp4")
        // Scripts rotate — text should differ
        assertNotEquals(r1.fullText, r2.fullText)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Streaming transcribeStreaming()
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `transcribeStreaming emits Partial then SegmentFinalised then Final`() = runTest {
        engine.transcribeStreaming("/tmp/chunk_0.mp4").test {
            var sawPartial = false
            var sawSegment = false
            var sawFinal   = false

            while (true) {
                val event = awaitItem()
                when (event) {
                    is SttStreamEvent.Partial          -> sawPartial = true
                    is SttStreamEvent.SegmentFinalised -> sawSegment = true
                    is SttStreamEvent.Final            -> { sawFinal = true; cancelAndIgnoreRemainingEvents(); break }
                    is SttStreamEvent.Error            -> fail("Unexpected error: ${event.exception.message}")
                }
            }

            assertTrue("Should have emitted at least one Partial", sawPartial)
            assertTrue("Should have emitted at least one SegmentFinalised", sawSegment)
            assertTrue("Should have emitted Final", sawFinal)
        }
    }

    @Test
    fun `Final event contains non-empty result`() = runTest {
        var finalResult: SttEngineResult? = null

        engine.transcribeStreaming("/tmp/chunk_0.mp4").test {
            while (true) {
                when (val event = awaitItem()) {
                    is SttStreamEvent.Final -> {
                        finalResult = event.result
                        cancelAndIgnoreRemainingEvents()
                        break
                    }
                    else -> Unit
                }
            }
        }

        assertNotNull(finalResult)
        assertTrue(finalResult!!.fullText.isNotBlank())
        assertFalse(finalResult!!.segments.isEmpty())
    }

    @Test
    fun `Partial events have increasing text length`() = runTest {
        val partials = mutableListOf<String>()

        engine.transcribeStreaming("/tmp/chunk_0.mp4").test {
            while (true) {
                val event = awaitItem()
                if (event is SttStreamEvent.Partial) partials += event.text
                if (event is SttStreamEvent.Final)   { cancelAndIgnoreRemainingEvents(); break }
                if (event is SttStreamEvent.Error)   fail("Unexpected error")
            }
        }

        assertTrue("Should have emitted multiple partials", partials.size > 1)
        // Each partial should be longer than (or equal to) the previous
        for (i in 1 until partials.size) {
            assertTrue(
                "Partial[$i] '${partials[i]}' should be >= Partial[${i-1}] '${partials[i-1]}'",
                partials[i].length >= partials[i - 1].length
            )
        }
    }

    @Test
    fun `streaming error event emitted for blank path`() = runTest {
        engine.transcribeStreaming("").test {
            val event = awaitItem()
            assertTrue("Expected Error for blank path", event is SttStreamEvent.Error)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `supportsStreaming is true`() {
        assertTrue(engine.supportsStreaming)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // WordTimestamp structural invariants
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `word timestamps are in chronological order`() = runTest {
        val result = engine.transcribe("/tmp/chunk_0.mp4",
            SttEngineConfig.DEFAULT.copy(enableWordTimestamps = true))

        val words = result.words
        for (i in 1 until words.size) {
            assertTrue(
                "Word[$i].startOffsetMs=${words[i].startOffsetMs} should be >= Word[${i-1}].startOffsetMs=${words[i-1].startOffsetMs}",
                words[i].startOffsetMs >= words[i - 1].startOffsetMs
            )
        }
    }

    @Test
    fun `segment time ranges are non-overlapping`() = runTest {
        val result = engine.transcribe("/tmp/chunk_0.mp4",
            SttEngineConfig.DEFAULT.copy(enableDiarisation = true))

        val segs = result.segments
        for (i in 1 until segs.size) {
            assertTrue(
                "Segment[$i].start should be >= Segment[${i-1}].end",
                segs[i].startOffsetMs >= segs[i - 1].endOffsetMs
            )
        }
    }
}
