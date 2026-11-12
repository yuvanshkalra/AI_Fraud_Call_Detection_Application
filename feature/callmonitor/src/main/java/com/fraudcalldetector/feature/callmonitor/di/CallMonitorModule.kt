package com.fraudcalldetector.feature.callmonitor.di

import com.fraudcalldetector.domain.repository.CallMonitorRepository
import com.fraudcalldetector.feature.callmonitor.CallMonitoringManager
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

// ─────────────────────────────────────────────────────────────────────────────
// Qualifier: application-scoped coroutine scope for the monitoring manager.
// Keeps it independent of ViewModel scopes (which die with the screen).
// ─────────────────────────────────────────────────────────────────────────────

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

// ─────────────────────────────────────────────────────────────────────────────
// Provides
// ─────────────────────────────────────────────────────────────────────────────

@Module
@InstallIn(SingletonComponent::class)
object CallMonitorScopeModule {

    /**
     * Application-lifetime coroutine scope shared by [CallMonitoringManager].
     * Uses [SupervisorJob] so a failed child never cancels siblings.
     * Uses [Dispatchers.Default] for CPU-bound work; IO dispatching is done
     * inside the manager itself where needed.
     */
    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
}

// ─────────────────────────────────────────────────────────────────────────────
// Bindings
// ─────────────────────────────────────────────────────────────────────────────

@Module
@InstallIn(SingletonComponent::class)
abstract class CallMonitorBindingModule {

    /**
     * Bind [CallMonitoringManager] (implementation) as [CallMonitorRepository] (interface).
     * All other modules depend only on the interface — zero coupling to this feature module.
     */
    @Binds
    @Singleton
    abstract fun bindCallMonitorRepository(
        impl: CallMonitoringManager
    ): CallMonitorRepository
}
