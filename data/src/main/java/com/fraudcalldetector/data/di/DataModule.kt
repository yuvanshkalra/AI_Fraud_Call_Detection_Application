package com.fraudcalldetector.data.di

import android.content.Context
import androidx.room.Room
import com.fraudcalldetector.data.local.FraudCallDatabase
import com.fraudcalldetector.data.local.dao.CallEventDao
import com.fraudcalldetector.data.repository.CallRepositoryImpl
import com.fraudcalldetector.domain.repository.CallRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): FraudCallDatabase =
        Room.databaseBuilder(
            context,
            FraudCallDatabase::class.java,
            FraudCallDatabase.DATABASE_NAME
        ).build()

    @Provides
    @Singleton
    fun provideCallEventDao(db: FraudCallDatabase): CallEventDao = db.callEventDao()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindCallRepository(impl: CallRepositoryImpl): CallRepository
}
