package com.fraudcalldetector.data.local.dao

import androidx.room.*
import com.fraudcalldetector.data.local.entity.CallEventEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CallEventDao {

    @Query("SELECT * FROM call_events ORDER BY startTime DESC")
    fun observeAll(): Flow<List<CallEventEntity>>

    @Query("SELECT * FROM call_events WHERE state IN ('RINGING','ACTIVE') ORDER BY startTime DESC")
    fun observeActiveCalls(): Flow<List<CallEventEntity>>

    @Query("SELECT * FROM call_events WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): CallEventEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: CallEventEntity)

    @Update
    suspend fun update(entity: CallEventEntity)

    @Query("UPDATE call_events SET state = :state WHERE id = :id")
    suspend fun updateState(id: String, state: String)

    @Query("DELETE FROM call_events WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT COUNT(*) FROM call_events")
    suspend fun count(): Int
}
