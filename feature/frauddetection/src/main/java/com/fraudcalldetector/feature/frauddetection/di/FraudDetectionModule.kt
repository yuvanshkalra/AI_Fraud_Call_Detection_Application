package com.fraudcalldetector.feature.frauddetection.di

import android.content.Context
import androidx.room.Room
import com.fraudcalldetector.core.network.NetworkClient
import com.fraudcalldetector.domain.fraud.FraudDetectionEngine
import com.fraudcalldetector.domain.fraud.FraudDetectionManager
import com.fraudcalldetector.domain.repository.FraudDetectionRepository
import com.fraudcalldetector.feature.frauddetection.FraudDetectionManagerImpl
import com.fraudcalldetector.feature.frauddetection.api.FraudDetectionApiService
import com.fraudcalldetector.feature.frauddetection.engine.MockFraudDetectionEngine
import com.fraudcalldetector.feature.frauddetection.engine.RemoteFraudDetectionEngine
import com.fraudcalldetector.feature.frauddetection.local.FraudAnalysisDao
import com.fraudcalldetector.feature.frauddetection.local.FraudDetectionDatabase
import com.fraudcalldetector.feature.frauddetection.repository.FraudDetectionRepositoryImpl
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
// Network + Room
// ─────────────────────────────────────────────────────────────────────────────

@Module
@InstallIn(SingletonComponent::class)
object FraudDetectionNetworkModule {

    /**
     * ─────────────────────────────────────────────────────────────────────────
     * TEAMMATE INTEGRATION POINT
     * ─────────────────────────────────────────────────────────────────────────
     * Replace this URL with your AI model's base URL.
     * The endpoint must implement POST /v1/detect (see [FraudDetectionApiService]).
     * Authentication headers are set in core/AuthInterceptor.kt.
     */
    private const val FRAUD_API_BASE_URL = "https://api.your-ai-model.com/"

    @Provides
    @Singleton
    fun provideFraudApiService(networkClient: NetworkClient): FraudDetectionApiService =
        networkClient.createService(FRAUD_API_BASE_URL)

    @Provides
    @Singleton
    fun provideFraudDetectionDatabase(@ApplicationContext context: Context): FraudDetectionDatabase =
        Room.databaseBuilder(context, FraudDetectionDatabase::class.java, FraudDetectionDatabase.DATABASE_NAME)
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    @Singleton
    fun provideFraudAnalysisDao(db: FraudDetectionDatabase): FraudAnalysisDao =
        db.fraudAnalysisDao()

    /**
     * Application-scoped [CoroutineScope] for [FraudDetectionManagerImpl].
     * [SupervisorJob] ensures a single failed analysis doesn't cancel the manager.
     */
    @Provides
    @Singleton
    @FraudDetectionManagerScope
    fun provideFraudDetectionManagerScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
}

// ─────────────────────────────────────────────────────────────────────────────
// Engine binding
// ─────────────────────────────────────────────────────────────────────────────

@Module
@InstallIn(SingletonComponent::class)
abstract class FraudDetectionEngineModule {

    /**
     * Bind [MockFraudDetectionEngine] as the default engine during development.
     *
     * ─────────────────────────────────────────────────────────────────────────
     * To switch to the real AI endpoint:
     *   Change: MockFraudDetectionEngine → RemoteFraudDetectionEngine
     *   Or override in a Hilt test module:
     *     @BindValue @JvmField val engine: FraudDetectionEngine = MockFraudDetectionEngine()
     * ─────────────────────────────────────────────────────────────────────────
     */
    @Binds
    @Singleton
    abstract fun bindFraudDetectionEngine(impl: MockFraudDetectionEngine): FraudDetectionEngine
}

// ─────────────────────────────────────────────────────────────────────────────
// Repository + Manager bindings
// ─────────────────────────────────────────────────────────────────────────────

@Module
@InstallIn(SingletonComponent::class)
abstract class FraudDetectionBindingModule {

    @Binds
    @Singleton
    abstract fun bindFraudDetectionRepository(
        impl: FraudDetectionRepositoryImpl
    ): FraudDetectionRepository

    @Binds
    @Singleton
    abstract fun bindFraudDetectionManager(
        impl: FraudDetectionManagerImpl
    ): FraudDetectionManager
}
