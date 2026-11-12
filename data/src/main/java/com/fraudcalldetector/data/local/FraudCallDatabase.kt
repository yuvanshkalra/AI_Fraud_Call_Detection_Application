package com.fraudcalldetector.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.fraudcalldetector.data.local.dao.CallEventDao
import com.fraudcalldetector.data.local.entity.CallEventEntity

@Database(
    entities = [CallEventEntity::class],
    version = 1,
    exportSchema = true
)
abstract class FraudCallDatabase : RoomDatabase() {
    abstract fun callEventDao(): CallEventDao

    companion object {
        const val DATABASE_NAME = "fraud_call_db"
    }
}
