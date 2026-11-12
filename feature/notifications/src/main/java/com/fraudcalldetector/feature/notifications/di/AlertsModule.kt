package com.fraudcalldetector.feature.notifications.di

import com.fraudcalldetector.domain.repository.AlertHistoryRepository
import com.fraudcalldetector.feature.notifications.AlertHistoryRepositoryImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AlertsModule {

    @Binds
    @Singleton
    abstract fun bindAlertHistoryRepository(
        impl: AlertHistoryRepositoryImpl
    ): AlertHistoryRepository
}
