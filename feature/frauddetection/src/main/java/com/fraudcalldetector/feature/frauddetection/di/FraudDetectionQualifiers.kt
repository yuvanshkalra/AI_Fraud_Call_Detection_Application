package com.fraudcalldetector.feature.frauddetection.di

import javax.inject.Qualifier

/**
 * Qualifier for the [CoroutineScope] injected into [FraudDetectionManagerImpl].
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class FraudDetectionManagerScope

/**
 * Qualifier that explicitly requests [MockFraudDetectionEngine].
 * Use in Hilt test modules:
 *   @BindValue @JvmField val engine: FraudDetectionEngine = MockFraudDetectionEngine()
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class MockFraudEngine
