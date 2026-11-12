package com.fraudcalldetector.feature.audiorecorder.di

import javax.inject.Qualifier

/**
 * Qualifier for the [CoroutineScope] injected into [AudioCaptureManager].
 *
 * Bound in [AudioCaptureModule] to an application-lifetime scope so the
 * capture manager survives configuration changes.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AudioRecorderScope
