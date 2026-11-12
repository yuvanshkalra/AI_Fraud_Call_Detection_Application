package com.fraudcalldetector.feature.stt.di

import android.content.Context
import androidx.room.Room
import com.fraudcalldetector.core.network.NetworkClient
import com.fraudcalldetector.domain.repository.SttRepository
import com.fraudcalldetector.domain.stt.SttEngine
import com.fraudcalldetector.domain.stt.SttManager
import com.fraudcalldetector.feature.stt.SttManagerImpl
import com.fraudcalldetector.feature.stt.api.SttApiService
import com.fraudcalldetector.feature.stt.engine.MockSttEngine
import com.fraudcalldetector.feature.stt.engine.RemoteSttEngine
import com.fraudcalldetector.feature.stt.local.SttDatabase
import com.fraudcalldetector.feature.stt.local.TranscriptDao
import com.fraudcalldetector.feature.stt.repository.SttRepositoryImpl
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

// ─────────────────────────────────────────────────────────────────────────────
// Network & Room
// ─────────────────────────────────────────────────────────────────────────────

@Module
@InstallIn(SingletonComponent::class)
object SttNetworkModule {

    /**
     * ─────────────────────────────────────────────────────────────────────────
     * Teammate integration point
     * ─────────────────────────────────────────────────────────────────────────
     * Replace this URL with your STT backend's base URL.
     * The endpoint must implement POST /v1/transcribe (see [SttApiService]).
     */
    private const val STT_BASE_URL = "https://api.your-stt-backend.com/"

    @Provides
    @Singleton
    fun provideSttApiService(networkClient: NetworkClient): SttApiService =
        networkClient.createService(STT_BASE_URL)

    @Provides
    @Singleton
    fun provideSttDatabase(@ApplicationContext context: Context): SttDatabase =
        Room.databaseBuilder(context, SttDatabase::class.java, SttDatabase.DATABASE_NAME)
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    @Singleton
    fun provideTranscriptDao(db: SttDatabase): TranscriptDao = db.transcriptDao()

    /**
     * Application-scoped coroutine scope for [SttManagerImpl].
     * Uses [SupervisorJob] so a failed transcription does not cancel siblings.
     */
    @Provides
    @Singleton
    @SttManagerScope
    fun provideSttManagerScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
}

// ─────────────────────────────────────────────────────────────────────────────
// Engine binding
// ─────────────────────────────────────────────────────────────────────────────

@Module
@InstallIn(SingletonComponent::class)
abstract class SttEngineModule {

    /**
     * [MockSttEngine] is the default binding — it runs fully offline with no
     * network required, making the app functional without a real STT backend.
     *
     * ─────────────────────────────────────────────────────────────────────────
     * TEAMMATE INTEGRATION
     * ─────────────────────────────────────────────────────────────────────────
     * To switch to the real remote STT endpoint once your backend is ready:
     *   1. Set STT_BASE_URL in [SttNetworkModule] to your server's base URL.
     *   2. Change the binding below:
     *        MockSttEngine  →  RemoteSttEngine
     *   3. Your endpoint must implement POST /v1/transcribe (see [SttApiService]).
     *
     * No other changes are required — [SttManagerImpl] and [SttRepositoryImpl]
     * are engine-agnostic.
     * ─────────────────────────────────────────────────────────────────────────
     */
    @Binds
    @Singleton
    abstract fun bindSttEngine(impl: MockSttEngine): SttEngine
}

// ─────────────────────────────────────────────────────────────────────────────
// Repository + Manager bindings
// ─────────────────────────────────────────────────────────────────────────────

@Module
@InstallIn(SingletonComponent::class)
abstract class SttBindingModule {

    @Binds
    @Singleton
    abstract fun bindSttRepository(impl: SttRepositoryImpl): SttRepository

    @Binds
    @Singleton
    abstract fun bindSttManager(impl: SttManagerImpl): SttManager
}
