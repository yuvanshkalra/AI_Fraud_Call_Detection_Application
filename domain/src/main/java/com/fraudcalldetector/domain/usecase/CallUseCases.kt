package com.fraudcalldetector.domain.usecase

import com.fraudcalldetector.domain.model.CallEvent
import com.fraudcalldetector.domain.model.FraudAnalysis
import com.fraudcalldetector.domain.repository.CallRepository
import com.fraudcalldetector.domain.repository.FraudDetectionRepository
import com.fraudcalldetector.domain.repository.SttRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/**
 * Orchestrates the full pipeline: record → transcribe → detect fraud → persist.
 * Each step is independently substitutable by teammates.
 */
class AnalyseCallUseCase @Inject constructor(
    private val sttRepository: SttRepository,
    private val fraudRepository: FraudDetectionRepository,
    private val callRepository: CallRepository
) {
    suspend operator fun invoke(
        callId: String,
        audioFilePath: String
    ): FraudAnalysis {
        // Step 1: Transcribe audio → text
        val transcript = sttRepository.transcribeAudio(audioFilePath, callId)
        sttRepository.saveTranscript(transcript)

        // Step 2: Send transcript (+ optional audio path) to AI model
        val analysis = fraudRepository.analyseCall(
            callId = callId,
            transcript = transcript.fullText,
            audioFilePath = audioFilePath
        )

        // Step 3: Attach analysis result to the call record
        val call = callRepository.getCallById(callId)
        call?.let {
            callRepository.updateCall(it.copy(
                transcript = transcript.fullText,
                fraudAnalysis = analysis
            ))
        }

        return analysis
    }
}

class GetCallHistoryUseCase @Inject constructor(
    private val repository: CallRepository
) {
    operator fun invoke(): Flow<List<CallEvent>> = repository.observeCallHistory()
}

class GetCallByIdUseCase @Inject constructor(
    private val repository: CallRepository
) {
    suspend operator fun invoke(id: String): CallEvent? = repository.getCallById(id)
}

class DeleteCallUseCase @Inject constructor(
    private val repository: CallRepository
) {
    suspend operator fun invoke(id: String) = repository.deleteCall(id)
}
