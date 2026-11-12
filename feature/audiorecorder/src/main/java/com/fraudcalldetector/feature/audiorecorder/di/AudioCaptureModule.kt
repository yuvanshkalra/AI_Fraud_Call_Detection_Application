package com.fraudcalldetector.feature.audiorecorder.di

import android.content.Context
import com.fraudcalldetector.domain.audio.AudioCaptureSource
import com.fraudcalldetector.domain.repository.AudioCaptureRepository
import com.fraudcalldetector.domain.repository.CallMonitorRepository
import com.fraudcalldetector.feature.audiorecorder.AudioCaptureManager
import com.fraudcalldetector.feature.audiorecorder.source.MediaRecorderAudioSource
import com.fraudcalldetector.feature.audiorecorder.source.MockAudioSource
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.plus
import kotlinx.coroutines.Dispatchers
import javax.inject.Qualifier
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AudioCaptureModule {

    // ─────────────────────────────────────────────────────────────────────────
    // Source binding
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Bind the real [MediaRecorderAudioSource] as the default [AudioCaptureSource].
     *
     * For unit tests / CI, override this binding in a test module with:
     *   @BindValue @JvmField val source: AudioCaptureSource = MockAudioSource()
     */
    @Binds
    @Singleton
    abstract fun bindAudioCaptureSource(impl: MediaRecorderAudioSource): AudioCaptureSource

    // ─────────────────────────────────────────────────────────────────────────
    // Repository binding
    // ─────────────────────────────────────────────────────────────────────────

    @Binds
    @Singleton
    abstract fun bindAudioCaptureRepository(impl: AudioCaptureManager): AudioCaptureRepository

    companion object {

        /**
         * Application-scoped [CoroutineScope] for [AudioCaptureManager].
         *
         * Uses [SupervisorJob] so a crash in one child (e.g. a rotation job)
         * does not cancel the parent or other children.
         */
        @Provides
        @Singleton
        @AudioRecorderScope
        fun provideAudioRecorderScope(): CoroutineScope =
            CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Test qualifier — lets tests inject MockAudioSource directly
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Inject [MockAudioSource] explicitly, bypassing the default binding.
 *
 * Usage in a Hilt test:
 *   @Inject @MockSource lateinit var mockSource: AudioCaptureSource
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class MockSource
