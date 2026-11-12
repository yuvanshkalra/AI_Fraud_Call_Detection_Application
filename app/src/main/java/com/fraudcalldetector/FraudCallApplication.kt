package com.fraudcalldetector

import com.fraudcalldetector.core.utils.BaseApplication
import com.fraudcalldetector.feature.callmonitor.CallPipelineOrchestrator
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber
import javax.inject.Inject

@HiltAndroidApp
class FraudCallApplication : BaseApplication() {

    /**
     * Eagerly injected so [CallPipelineOrchestrator] is instantiated at
     * process start.  The orchestrator subscribes to [CallMonitorRepository.events]
     * in its `init` block, wiring the full pipeline:
     *
     *   CallMonitor → AudioCapture → STT → FraudDetection → FraudAlerts
     *
     * Without this injection the orchestrator would only be created the first
     * time a UI component that depends on it is shown, potentially missing
     * early call events.
     */
    @Inject
    lateinit var callPipelineOrchestrator: CallPipelineOrchestrator

    override fun onCreate() {
        super.onCreate()
        Timber.d("FraudCall Detector started — pipeline orchestrator active")
    }
}
