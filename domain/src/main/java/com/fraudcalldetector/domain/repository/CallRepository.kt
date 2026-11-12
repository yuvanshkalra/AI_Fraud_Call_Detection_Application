package com.fraudcalldetector.domain.repository

import com.fraudcalldetector.domain.model.CallEvent
import com.fraudcalldetector.domain.model.CallState
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for call event persistence and observation.
 * Implemented in the :data module.
 */
interface CallRepository {
    fun observeActiveCalls(): Flow<List<CallEvent>>
    fun observeCallHistory(): Flow<List<CallEvent>>
    suspend fun getCallById(id: String): CallEvent?
    suspend fun insertCall(call: CallEvent)
    suspend fun updateCall(call: CallEvent)
    suspend fun updateCallState(id: String, state: CallState)
    suspend fun deleteCall(id: String)
    suspend fun getCallCount(): Int
}
