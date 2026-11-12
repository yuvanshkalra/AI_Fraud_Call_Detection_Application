package com.fraudcalldetector.feature.callmonitor

import com.fraudcalldetector.domain.fraud.FraudDetectionManager
import com.fraudcalldetector.domain.model.MonitoringEvent
import com.fraudcalldetector.domain.repository.AudioCaptureRepository
import com.fraudcalldetector.domain.repository.CallMonitorRepository
import com.fraudcalldetector.domain.stt.SttManager
import com.fraudcalldetector.feature.callmonitor.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * CallPipelineOrchestrator
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * Single integration point that connects all five pipeline stages:
 *
 *   CallMonitorRepository
 *         │  MonitoringEvent.Active  → AudioCaptureRepository.startCapture(callId)
 *         │  MonitoringEvent.Ended   → AudioCaptureRepository.stopCapture(callId)
 *         │                            (emits final AudioChunk with isFinal=true)
 *         │  MonitoringEvent.Missed  → AudioCaptureRepository.cancelCapture(callId)
 *         │
 *         ▼
 *   AudioCaptureRepository               [chunks Flow]
 *         │  AudioChunk (every 30 s)  ─────────────────────────────────────────┐
 *         │                                                                     │
 *         ▼                                                                     ▼
 *   SttManagerImpl ◄──────── subscribes to chunks in its own init block ───────┘
 *         │  SttManagerState.ChunkComplete(callId, chunkIndex, result)
 *         │  SttManagerState.CallComplete(callId, fullTranscript)
 *         │
 *         ▼
 *   FraudDetectionManagerImpl ◄───── subscribes to SttManager.state in its init
 *         │  Per-chunk: enqueueIncrementalAnalysis → IncrementalResult state
 *         │  Final:     runFinalAnalysis → Complete state + persist + alert
 *         │
 *         ▼
 *   FraudNotificationManager  (called inside FraudDetectionManagerImpl)
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * Responsibilities
 * ─────────────────────────────────────────────────────────────────────────────
 *  • React to [MonitoringEvent.Active]  → start audio capture for the call.
 *  • React to [MonitoringEvent.Ended]   → stop audio capture; the final chunk
 *    flows through STT → FraudDetection automatically.
 *  • React to [MonitoringEvent.Missed]  → cancel audio capture cleanly.
 *  • Log all pipeline transitions for traceability.
 *
 * This class does NOT contain business logic.  It is purely a wiring layer —
 * it owns no state of its own, only coordinates the lifecycle of other
 * Singleton components.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * Lifecycle
 * ─────────────────────────────────────────────────────────────────────────────
 * [CallPipelineOrchestrator] is a [Singleton] injected eagerly by Hilt via
 * [CallPipelineModule].  The subscription to [CallMonitorRepository.events]
 * starts at injection time and lives for the life of the application process.
 *
 * STT and FraudDetection subscriptions are set up inside their own managers
 * (in their respective `init` blocks) — the orchestrator only drives audio
 * capture.  This avoids double-subscriptions if those managers are started
 * independently (e.g. in tests).
 */
@Singleton
class CallPipelineOrchestrator @Inject constructor(
    private val callMonitorRepository  : CallMonitorRepository,
    private val audioCaptureRepository : AudioCaptureRepository,
    private val sttManager             : SttManager,          // forces eager init
    private val fraudDetectionManager  : FraudDetectionManager, // forces eager init
    @ApplicationScope private val scope: CoroutineScope
) {

    init {
        startPipeline()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Pipeline wiring
    // ─────────────────────────────────────────────────────────────────────────

    private fun startPipeline() {
        callMonitorRepository.events
            .onEach { event -> handleMonitoringEvent(event) }
            .launchIn(scope)

        Timber.i("CallPipelineOrchestrator: pipeline started — listening for call events")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Event handling
    // ─────────────────────────────────────────────────────────────────────────

    private suspend fun handleMonitoringEvent(event: MonitoringEvent) {
        when (event) {

            // ── Call became active → start audio capture ──────────────────
            is MonitoringEvent.Active -> {
                Timber.d(
                    "CallPipelineOrchestrator: Active " +
                    "callId=${event.callId} number=${event.number} " +
                    "direction=${event.direction}"
                )
                runCatching {
                    audioCaptureRepository.startCapture(event.callId)
                    Timber.i("CallPipelineOrchestrator: audio capture started for callId=${event.callId}")
                }.onFailure { e ->
                    Timber.e(e, "CallPipelineOrchestrator: failed to start audio capture for callId=${event.callId}")
                }
            }

            // ── Call ended normally → stop capture, emit final chunk ───────
            is MonitoringEvent.Ended -> {
                Timber.d(
                    "CallPipelineOrchestrator: Ended " +
                    "callId=${event.callId} durationMs=${event.durationMs}"
                )
                runCatching {
                    val session = audioCaptureRepository.stopCapture(event.callId)
                    Timber.i(
                        "CallPipelineOrchestrator: audio capture stopped for callId=${event.callId} " +
                        "chunks=${session?.chunks?.size ?: 0}"
                    )
                }.onFailure { e ->
                    Timber.e(e, "CallPipelineOrchestrator: failed to stop audio capture for callId=${event.callId}")
                }
                // STT session reset and FraudDetection cleanup happen inside
                // FraudDetectionManagerImpl after CallComplete fires.
                // Nothing more to do here — structured cleanup is handled downstream.
            }

            // ── Missed call → cancel capture, discard partial audio ────────
            is MonitoringEvent.Missed -> {
                Timber.d(
                    "CallPipelineOrchestrator: Missed " +
                    "callId=${event.callId} number=${event.number}"
                )
                runCatching {
                    audioCaptureRepository.cancelCapture(event.callId)
                    // Also cancel any in-flight fraud analysis for this call
                    fraudDetectionManager.cancelAnalysis(event.callId)
                    Timber.i("CallPipelineOrchestrator: missed-call cleanup done for callId=${event.callId}")
                }.onFailure { e ->
                    Timber.e(e, "CallPipelineOrchestrator: cleanup failed for callId=${event.callId}")
                }
            }

            // ── Ringing — no action needed (audio capture waits for Active) ─
            is MonitoringEvent.Ringing -> {
                Timber.d(
                    "CallPipelineOrchestrator: Ringing " +
                    "callId=${event.callId} number=${event.number}"
                )
            }

            // ── Monitoring lifecycle events — informational ────────────────
            MonitoringEvent.MonitoringStarted -> {
                Timber.i("CallPipelineOrchestrator: monitoring started")
            }

            MonitoringEvent.MonitoringStopped -> {
                Timber.i("CallPipelineOrchestrator: monitoring stopped")
            }

            is MonitoringEvent.PermissionDenied -> {
                Timber.w("CallPipelineOrchestrator: permission denied — ${event.permission}")
            }
        }
    }
}
