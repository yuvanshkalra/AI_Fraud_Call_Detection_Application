package com.fraudcalldetector.feature.stt.engine

import com.fraudcalldetector.domain.stt.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import timber.log.Timber
import java.io.File
import java.util.UUID
import javax.inject.Inject

/**
 * Deterministic [SttEngine] for unit tests, CI, and offline development.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * Behaviour contract
 * ─────────────────────────────────────────────────────────────────────────────
 *  • Generates a realistic fake transcript using a configurable [ScriptLibrary].
 *  • Emits incremental [SttStreamEvent.Partial] events word-by-word with a
 *    configurable word-emission delay — simulates real-time streaming.
 *  • Builds per-word [WordTimestamp]s from the fake script's word positions.
 *  • Groups words into [SttSegment]s alternating CALLER / USER speaker labels.
 *  • Never touches real hardware, network, or the file system beyond checking
 *    that the given path is non-blank.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * Swapping in a real engine
 * ─────────────────────────────────────────────────────────────────────────────
 * In [SttModule], change:
 *   @Binds abstract fun bindEngine(impl: MockSttEngine): SttEngine
 * to:
 *   @Binds abstract fun bindEngine(impl: RemoteSttEngine): SttEngine
 * No other changes are required.
 */
class MockSttEngine @Inject constructor() : SttEngine {

    override val supportsStreaming: Boolean = true

    // ─────────────────────────────────────────────────────────────────────────
    // SttEngine — synchronous
    // ─────────────────────────────────────────────────────────────────────────

    override suspend fun transcribe(
        audioFilePath: String,
        config       : SttEngineConfig
    ): SttEngineResult {
        validatePath(audioFilePath)
        Timber.d("MockSttEngine.transcribe → $audioFilePath")

        // Simulate processing latency proportional to (mock) file size
        delay(MOCK_LATENCY_MS)

        val script = ScriptLibrary.pick()
        return buildResult(script, config, isFinal = true)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SttEngine — streaming
    // ─────────────────────────────────────────────────────────────────────────

    override fun transcribeStreaming(
        audioFilePath: String,
        config       : SttEngineConfig
    ): Flow<SttStreamEvent> = flow {
        try {
            validatePath(audioFilePath)
            Timber.d("MockSttEngine.transcribeStreaming → $audioFilePath")

            val script = ScriptLibrary.pick()
            val words  = script.split(" ")
            val partial = StringBuilder()

            // ── Emit partial events word-by-word ──────────────────────────
            for ((index, word) in words.withIndex()) {
                delay(WORD_EMIT_DELAY_MS)
                partial.append(if (index == 0) word else " $word")

                // Every STABLE_WORD_WINDOW words, mark earlier words as stable
                val stableCount = maxOf(0, index - STABLE_WORD_WINDOW)
                val stableWords = buildWordTimestamps(
                    words.take(stableCount),
                    startOffsetMs = 0L,
                    config        = config
                )

                emit(SttStreamEvent.Partial(
                    text        = partial.toString(),
                    confidence  = 0.5f + (index.toFloat() / words.size) * 0.4f,
                    wordsStable = stableWords
                ))
            }

            // ── Build the full result ──────────────────────────────────────
            val result = buildResult(script, config, isFinal = true)

            // ── Emit one SegmentFinalised per speaker block ────────────────
            result.segments.forEach { segment ->
                val segWords = result.words.filter {
                    it.startOffsetMs >= segment.startOffsetMs &&
                    it.endOffsetMs   <= segment.endOffsetMs
                }
                emit(SttStreamEvent.SegmentFinalised(segment, segWords))
            }

            // ── Final event ───────────────────────────────────────────────
            emit(SttStreamEvent.Final(result))
            Timber.d("MockSttEngine: streaming complete, ${words.size} words")

        } catch (e: SttEngineException) {
            emit(SttStreamEvent.Error(e))
        } catch (e: Exception) {
            emit(SttStreamEvent.Error(
                SttEngineException("MockSttEngine error: ${e.message}", e)
            ))
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Internal helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun validatePath(audioFilePath: String) {
        if (audioFilePath.isBlank()) {
            throw SttEngineException("Audio file path is blank")
        }
    }

    /**
     * Build a realistic [SttEngineResult] from a script string.
     * Segments alternate CALLER/USER; words get even-spaced timestamps.
     */
    private fun buildResult(
        script  : String,
        config  : SttEngineConfig,
        isFinal : Boolean
    ): SttEngineResult {
        val allWords = script.split(" ")
        val durationMs = allWords.size * WORD_EMIT_DELAY_MS

        val wordTimestamps = if (config.enableWordTimestamps) {
            buildWordTimestamps(allWords, startOffsetMs = 0L, config = config)
        } else emptyList()

        val segments = buildSegments(allWords, wordTimestamps, config)

        return SttEngineResult(
            fullText          = script,
            segments          = segments,
            words             = wordTimestamps,
            detectedLanguage  = config.languageCode,
            audioDurationMs   = durationMs,
            overallConfidence = 0.91f,
            isFinal           = isFinal
        )
    }

    /**
     * Build evenly-spaced [WordTimestamp]s for [words].
     */
    private fun buildWordTimestamps(
        words        : List<String>,
        startOffsetMs: Long,
        config       : SttEngineConfig
    ): List<WordTimestamp> {
        if (!config.enableWordTimestamps || words.isEmpty()) return emptyList()

        val msPerWord = WORD_EMIT_DELAY_MS
        return words.mapIndexed { i, word ->
            val start  = startOffsetMs + i * msPerWord
            val end    = start + msPerWord - 50L   // 50 ms gap between words
            // Alternate speaker per sentence-ish block
            val speaker = if ((i / WORDS_PER_SPEAKER_BLOCK) % 2 == 0) SttSpeaker.CALLER
                          else SttSpeaker.USER
            WordTimestamp(
                word          = word,
                startOffsetMs = start,
                endOffsetMs   = end,
                confidence    = 0.88f + (i % 5) * 0.02f,   // vary slightly
                speaker       = speaker
            )
        }
    }

    /**
     * Group words into [SttSegment]s by speaker block.
     */
    private fun buildSegments(
        words          : List<String>,
        wordTimestamps : List<WordTimestamp>,
        config         : SttEngineConfig
    ): List<SttSegment> {
        if (!config.enableDiarisation || words.isEmpty()) {
            // Single-segment result
            val endMs = wordTimestamps.lastOrNull()?.endOffsetMs ?: (words.size * WORD_EMIT_DELAY_MS)
            return listOf(
                SttSegment(
                    id            = UUID.randomUUID().toString(),
                    text          = words.joinToString(" "),
                    speaker       = SttSpeaker.UNKNOWN,
                    startOffsetMs = 0L,
                    endOffsetMs   = endMs,
                    confidence    = 0.91f
                )
            )
        }

        // Chunk into blocks of WORDS_PER_SPEAKER_BLOCK
        return words.chunked(WORDS_PER_SPEAKER_BLOCK).mapIndexed { blockIndex, block ->
            val speaker     = if (blockIndex % 2 == 0) SttSpeaker.CALLER else SttSpeaker.USER
            val blockStart  = blockIndex * WORDS_PER_SPEAKER_BLOCK
            val startMs     = wordTimestamps.getOrNull(blockStart)?.startOffsetMs
                              ?: (blockStart * WORD_EMIT_DELAY_MS)
            val endMs       = wordTimestamps.getOrNull(
                                  minOf(blockStart + WORDS_PER_SPEAKER_BLOCK - 1, wordTimestamps.lastIndex)
                              )?.endOffsetMs ?: (startMs + block.size * WORD_EMIT_DELAY_MS)

            SttSegment(
                id            = UUID.randomUUID().toString(),
                text          = block.joinToString(" "),
                speaker       = speaker,
                startOffsetMs = startMs,
                endOffsetMs   = endMs,
                confidence    = 0.88f + (blockIndex % 3) * 0.03f
            )
        }
    }

    companion object {
        /** Simulated total processing latency for the synchronous path. */
        const val MOCK_LATENCY_MS       : Long = 400L
        /** Delay between individual word emissions in streaming mode. */
        const val WORD_EMIT_DELAY_MS    : Long = 120L
        /** Number of already-emitted words kept "unstable" (not yet marked stable). */
        const val STABLE_WORD_WINDOW    : Int  = 5
        /** Words per speaker diarisation block. */
        const val WORDS_PER_SPEAKER_BLOCK: Int = 8
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Built-in script library  (realistic fraud-scenario dialogues)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Provides canned scripts used by [MockSttEngine].
 * Cycles through them so tests get varied but deterministic content.
 */
object ScriptLibrary {

    private val SCRIPTS = listOf(
        // Classic IRS / government impersonation
        "Hello this is a call from the IRS regarding unpaid taxes on your account. " +
        "You must verify your social security number immediately or face legal action. " +
        "Please stay on the line and do not hang up.",

        // Tech-support scam
        "We are calling from Microsoft support. We have detected suspicious activity on your computer. " +
        "Your system has been compromised and we need you to install remote access software immediately. " +
        "Please do not ignore this message.",

        // Grandparent / family emergency scam
        "Hi grandma it is me your grandson. I am in trouble and I need you to send money right away. " +
        "Please do not tell mom or dad. I will explain everything later just please help me.",

        // Bank account fraud
        "This is an automated message from your bank. Your account has been flagged for unusual transactions. " +
        "To prevent your account from being frozen please confirm your account number and PIN now.",

        // Safe call — normal conversation
        "Hey how are you doing? Just calling to check in and see if you want to grab lunch this week. " +
        "I was thinking maybe Tuesday or Wednesday would work. Let me know what fits your schedule."
    )

    private var index = 0

    /** Returns the next script, cycling through the list. */
    fun pick(): String {
        val script = SCRIPTS[index % SCRIPTS.size]
        index++
        return script
    }

    /** Reset the cycle — useful in test setUp(). */
    fun reset() { index = 0 }
}
