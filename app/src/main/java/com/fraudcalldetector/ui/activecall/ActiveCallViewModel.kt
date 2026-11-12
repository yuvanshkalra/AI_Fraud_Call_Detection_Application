package com.fraudcalldetector.ui.activecall

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fraudcalldetector.domain.fraud.FraudAnalysisResult
import com.fraudcalldetector.domain.fraud.FraudDetectionManager
import com.fraudcalldetector.domain.fraud.FraudDetectionState
import com.fraudcalldetector.domain.model.AlertStatus
import com.fraudcalldetector.domain.model.AudioChunk
import com.fraudcalldetector.domain.model.CallEvent
import com.fraudcalldetector.domain.model.CallState
import com.fraudcalldetector.domain.model.FraudAnalysis
import com.fraudcalldetector.domain.model.RiskLevel
import com.fraudcalldetector.domain.repository.AlertHistoryRepository
import com.fraudcalldetector.domain.repository.AudioCaptureRepository
import com.fraudcalldetector.domain.repository.CallRepository
import com.fraudcalldetector.domain.repository.CaptureState
import com.fraudcalldetector.domain.stt.SttManager
import com.fraudcalldetector.domain.stt.SttManagerState
import com.fraudcalldetector.domain.usecase.AnalyseCallUseCase
import com.fraudcalldetector.feature.notifications.FraudNotificationManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ActiveCallViewModel @Inject constructor(
    private val callRepository            : CallRepository,
    private val analyseCallUseCase        : AnalyseCallUseCase,
    private val audioCaptureRepository    : AudioCaptureRepository,
    private val sttManager                : SttManager,
    private val fraudDetectionManager     : FraudDetectionManager,
    private val notificationManager       : FraudNotificationManager,
    private val alertHistoryRepository    : AlertHistoryRepository,
    savedStateHandle                      : SavedStateHandle
) : ViewModel() {

    private val callId = savedStateHandle.get<String>("callId") ?: ""

    private val _uiState = MutableStateFlow(ActiveCallState())
    val uiState: StateFlow<ActiveCallState> = _uiState.asStateFlow()

    private var timerJob: Job? = null

    init {
        observeCall()
        observeCaptureState()
        observeChunks()
        observeLiveTranscript()
        observeFraudDetection()
        startTimer()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Observation
    // ─────────────────────────────────────────────────────────────────────────

    private fun observeCall() {
        viewModelScope.launch {
            callRepository.observeActiveCalls()
                .map { calls -> calls.firstOrNull { it.id == callId } }
                .filterNotNull()
                .collect { call ->
                    _uiState.update {
                        it.copy(
                            call           = call,
                            isCallActive   = call.state == CallState.ACTIVE,
                            fraudAnalysis  = call.fraudAnalysis ?: it.fraudAnalysis,
                            analysisStatus = if (call.fraudAnalysis != null) AnalysisStatus.COMPLETE
                                            else it.analysisStatus
                        )
                    }
                }
        }
    }

    private fun observeCaptureState() {
        viewModelScope.launch {
            audioCaptureRepository.captureState.collect { state ->
                val isRec = state is CaptureState.Recording || state is CaptureState.Rotating
                _uiState.update { it.copy(isRecording = isRec, captureState = state) }
            }
        }
    }

    private fun observeChunks() {
        viewModelScope.launch {
            audioCaptureRepository.chunks
                .filter { it.callId == callId }
                .collect { chunk ->
                    _uiState.update { current ->
                        current.copy(
                            recordedChunks = current.recordedChunks + chunk,
                            chunkCount     = current.chunkCount + 1
                        )
                    }
                }
        }
    }

    private fun observeLiveTranscript() {
        viewModelScope.launch {
            sttManager.liveTranscript.collect { text ->
                _uiState.update { it.copy(liveTranscriptText = text) }
            }
        }
        viewModelScope.launch {
            sttManager.state.collect { sttState ->
                val isTranscribing = sttState is SttManagerState.Transcribing ||
                                     sttState is SttManagerState.StreamingPartial
                _uiState.update { it.copy(isTranscribing = isTranscribing) }
            }
        }
    }

    /**
     * Observes [FraudDetectionManager] state and result flow.
     *
     * • [FraudDetectionState.Analysing]        → set [AnalysisStatus.ANALYSING]
     * • [FraudDetectionState.IncrementalResult]→ update live risk badge mid-call
     *   without changing [analysisStatus] to COMPLETE (the call is still ongoing)
     * • [FraudDetectionState.Complete]         → set [AnalysisStatus.COMPLETE] + attach analysis
     * • [FraudDetectionState.Failed]           → revert to [AnalysisStatus.IDLE]
     * • [FraudDetectionManager.results]        → update [fraudAnalysis] as soon as a result
     *   for *this* call arrives (avoids waiting for the call DB record to refresh)
     */
    private fun observeFraudDetection() {
        viewModelScope.launch {
            fraudDetectionManager.state.collect { fdState ->
                when (fdState) {
                    is FraudDetectionState.Analysing -> {
                        if (fdState.callId == callId || callId.isEmpty()) {
                            _uiState.update { it.copy(analysisStatus = AnalysisStatus.ANALYSING) }
                        }
                    }
                    is FraudDetectionState.IncrementalResult -> {
                        // Real-time per-chunk risk update — update the live risk badge
                        // but keep analysisStatus as ANALYSING so the UI shows the
                        // scanning indicator alongside the partial score.
                        if (fdState.callId == callId || callId.isEmpty()) {
                            _uiState.update {
                                it.copy(
                                    liveRiskAnalysis = fdState.analysis,
                                    analysisStatus   = AnalysisStatus.ANALYSING
                                )
                            }
                        }
                    }
                    is FraudDetectionState.Complete -> {
                        if (fdState.callId == callId || callId.isEmpty()) {
                            _uiState.update {
                                it.copy(
                                    analysisStatus   = AnalysisStatus.COMPLETE,
                                    fraudAnalysis    = fdState.analysis,
                                    liveRiskAnalysis = null   // clear incremental badge
                                )
                            }
                        }
                    }
                    is FraudDetectionState.Failed -> {
                        if (fdState.callId == callId || callId.isEmpty()) {
                            _uiState.update {
                                it.copy(
                                    analysisStatus      = AnalysisStatus.IDLE,
                                    liveRiskAnalysis    = null,
                                    fraudDetectionError = fdState.message
                                )
                            }
                        }
                    }
                    FraudDetectionState.Idle -> Unit
                }
            }
        }
        viewModelScope.launch {
            fraudDetectionManager.results
                .filter { it.callId == callId || callId.isEmpty() }
                .collect { result: FraudAnalysisResult ->
                    _uiState.update {
                        it.copy(
                            fraudAnalysis    = result.analysis,
                            liveRiskAnalysis = null,
                            analysisStatus   = AnalysisStatus.COMPLETE
                        )
                    }
                }
        }
    }

    private fun startTimer() {
        timerJob = viewModelScope.launch {
            while (true) {
                val startTime = _uiState.value.call?.startTime ?: System.currentTimeMillis()
                _uiState.update { it.copy(elapsedMs = System.currentTimeMillis() - startTime) }
                delay(1_000)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Actions
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Dismiss the in-app fraud alert banner.
     *
     * Also cancels the corresponding system notification (so it doesn't linger
     * in the shade while the user is already viewing it in-app) and marks the
     * alert-history record as [AlertStatus.DISMISSED].
     */
    fun dismissFraudBanner() {
        val callId = _uiState.value.call?.id ?: return
        notificationManager.dismissFraudAlert(callId)
        viewModelScope.launch {
            runCatching {
                alertHistoryRepository.updateStatus(callId, AlertStatus.DISMISSED)
            }
        }
    }

    /**
     * Mark the alert as VIEWED (user tapped "View Details").
     * Cancels the system notification and updates history status.
     */
    fun markAlertViewed() {
        val callId = _uiState.value.call?.id ?: return
        notificationManager.dismissFraudAlert(callId)
        viewModelScope.launch {
            runCatching {
                alertHistoryRepository.updateStatus(callId, AlertStatus.VIEWED)
            }
        }
    }

    fun requestAnalysis() {
        val call      = _uiState.value.call ?: return
        val audioPath = call.recordingPath ?: return
        _uiState.update { it.copy(analysisStatus = AnalysisStatus.ANALYSING) }
        viewModelScope.launch {
            runCatching {
                val analysis = analyseCallUseCase(call.id, audioPath)
                _uiState.update { it.copy(fraudAnalysis = analysis, analysisStatus = AnalysisStatus.COMPLETE) }
            }.onFailure {
                _uiState.update { s ->
                    s.copy(
                        analysisStatus      = AnalysisStatus.IDLE,
                        fraudDetectionError = it.message
                    )
                }
            }
        }
    }

    override fun onCleared() {
        timerJob?.cancel()
        super.onCleared()
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// UI state
// ─────────────────────────────────────────────────────────────────────────────

data class ActiveCallState(
    val call                : CallEvent?          = null,
    val isCallActive        : Boolean             = true,
    val elapsedMs           : Long                = 0L,
    val fraudAnalysis       : FraudAnalysis?      = null,
    /**
     * Mid-call incremental fraud score emitted after each audio chunk is
     * transcribed and scored.  Cleared when the final analysis arrives.
     * The UI shows this as a live risk badge (e.g. "~72% suspicious") while
     * [analysisStatus] remains [AnalysisStatus.ANALYSING].
     */
    val liveRiskAnalysis    : FraudAnalysis?      = null,
    val analysisStatus      : AnalysisStatus      = AnalysisStatus.IDLE,
    val fraudDetectionError : String?             = null,
    // ── Recording state ──────────────────────────────────────────────────────
    val isRecording         : Boolean             = false,
    val captureState        : CaptureState        = CaptureState.Idle,
    val recordedChunks      : List<AudioChunk>    = emptyList(),
    val chunkCount          : Int                 = 0,
    // ── Transcription state ───────────────────────────────────────────────────
    val liveTranscriptText  : String              = "",
    val isTranscribing      : Boolean             = false
)

enum class AnalysisStatus { IDLE, ANALYSING, COMPLETE }
