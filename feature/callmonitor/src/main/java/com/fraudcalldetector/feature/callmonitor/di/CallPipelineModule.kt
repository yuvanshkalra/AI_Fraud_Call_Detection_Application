package com.fraudcalldetector.feature.callmonitor.di

import com.fraudcalldetector.feature.callmonitor.CallPipelineOrchestrator
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Hilt module that ensures [CallPipelineOrchestrator] is part of the
 * [SingletonComponent] graph so it is constructed eagerly at app start.
 *
 * [CallPipelineOrchestrator] uses [@Singleton @Inject constructor] — Hilt
 * resolves it automatically without a @Provides method.  This module is kept
 * as a marker so the Hilt component generator includes the orchestrator in the
 * correct component scope.
 *
 * Eager initialisation: [CallPipelineOrchestrator] is injected into
 * [com.fraudcalldetector.FraudCallApplication] which forces Hilt to construct
 * it when the process starts, before any Activity or Service runs.
 */
@Module
@InstallIn(SingletonComponent::class)
object CallPipelineModule
// No @Provides needed — CallPipelineOrchestrator has @Singleton @Inject constructor.
// A @Provides(impl: CallPipelineOrchestrator): CallPipelineOrchestrator would create
// a self-referential dependency cycle caught by Dagger at compile time.
