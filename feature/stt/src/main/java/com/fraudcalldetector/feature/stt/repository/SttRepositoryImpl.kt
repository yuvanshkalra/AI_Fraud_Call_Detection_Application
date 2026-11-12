package com.fraudcalldetector.feature.stt.repository

import com.fraudcalldetector.domain.model.Speaker
import com.fraudcalldetector.domain.model.TranscriptResult
import com.fraudcalldetector.domain.model.TranscriptSegment
import com.fraudcalldetector.domain.repository.SttRepository
import com.fraudcalldetector.domain.stt.SttEngine
import com.fraudcalldetector.domain.stt.SttEngineConfig
import com.fraudcalldetector.feature.stt.local.TranscriptDao
import com.fraudcalldetector.feature.stt.local.toDomain
import com.fraudcalldetector.feature.stt.local.toEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implements [SttRepository] using [SttEngine] (via inject — currently bound
 * to [MockSttEngine]) for on-device transcription and [TranscriptDao] for local
 * persistence.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * Design note
 * ─────────────────────────────────────────────────────────────────────────────
 * The streaming [SttManager] pipeline ([SttManagerImpl]) is the primary consumer
 * during live calls.  This repository's [transcribeAudio] is used only by the
 * one-shot [AnalyseCallUseCase] path (re-analysing a recorded call from history).
 *
 * Both paths share the same [SttEngine] binding, so switching the engine in
 * [SttModule] affects both.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * TEAMMATE INTEGRATION
 * ─────────────────────────────────────────────────────────────────────────────
 * Once your STT REST endpoint is ready:
 *   1. Change the engine binding in [SttModule]:
 *        MockSttEngine → RemoteSttEngine
 *   2. Set STT_BASE_URL in [SttNetworkModule] to your server base URL.
 *   3. [RemoteSttEngine] calls [SttApiService.transcribeAudio] (POST /v1/transcribe).
 * No changes to this class are needed.
 */
@Singleton
class SttRepositoryImpl @Inject constructor(
    private val engine: SttEngine,
    private val dao   : TranscriptDao
) : SttRepository {

    // ─────────────────────────────────────────────────────────────────────────
    // One-shot transcription (used by AnalyseCallUseCase)
    // ─────────────────────────────────────────────────────────────────────────

    override suspend fun transcribeAudio(audioFilePath: String, callId: String): TranscriptResult {
        return try {
            val result = engine.transcribe(audioFilePath, SttEngineConfig.DEFAULT)
            TranscriptResult(
                callEventId = callId,
                fullText    = result.fullText,
                segments    = result.segments.map { seg ->
                    TranscriptSegment(
                        id            = seg.id,
                        callEventId   = callId,
                        text          = seg.text,
                        speaker       = when (seg.speaker) {
                            com.fraudcalldetector.domain.stt.SttSpeaker.CALLER  -> Speaker.CALLER
                            com.fraudcalldetector.domain.stt.SttSpeaker.USER    -> Speaker.USER
                            else -> Speaker.UNKNOWN
                        },
                        startOffsetMs = seg.startOffsetMs,
                        endOffsetMs   = seg.endOffsetMs,
                        confidence    = seg.confidence
                    )
                },
                language = result.detectedLanguage
            )
        } catch (e: Exception) {
            // Non-fatal: return empty transcript rather than crashing the pipeline.
            // The fraud detection step will skip analysis for blank transcripts.
            Timber.e(e, "SttRepositoryImpl: transcription failed for callId=$callId, returning empty")
            TranscriptResult(callEventId = callId, fullText = "", segments = emptyList())
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CRUD
    // ─────────────────────────────────────────────────────────────────────────

    override suspend fun getTranscript(callId: String): TranscriptResult? =
        dao.getByCallId(callId)?.toDomain()

    override suspend fun saveTranscript(transcript: TranscriptResult) =
        dao.insert(transcript.toEntity())

    override fun observeTranscript(callId: String): Flow<TranscriptResult?> =
        dao.observeByCallId(callId).map { it?.toDomain() }

    override suspend fun deleteTranscript(callId: String) =
        dao.deleteByCallId(callId)
}
