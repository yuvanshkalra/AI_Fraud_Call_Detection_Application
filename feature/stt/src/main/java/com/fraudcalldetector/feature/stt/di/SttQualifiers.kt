package com.fraudcalldetector.feature.stt.di

import javax.inject.Qualifier

/**
 * Qualifier for the [CoroutineScope] injected into [SttManagerImpl].
 * Bound in [SttModule] to an application-lifetime supervisor scope.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class SttManagerScope

/**
 * Qualifier that explicitly requests [MockSttEngine] as the [SttEngine].
 * Useful in Hilt test modules:
 *   @BindValue @JvmField val engine: SttEngine = MockSttEngine()
 *   @Inject @MockEngine lateinit var engine: SttEngine
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class MockEngine
