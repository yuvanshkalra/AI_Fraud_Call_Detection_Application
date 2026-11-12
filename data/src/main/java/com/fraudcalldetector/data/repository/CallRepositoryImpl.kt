package com.fraudcalldetector.data.repository

import com.fraudcalldetector.data.local.dao.CallEventDao
import com.fraudcalldetector.data.local.entity.toDomain
import com.fraudcalldetector.data.local.entity.toEntity
import com.fraudcalldetector.domain.model.CallEvent
import com.fraudcalldetector.domain.model.CallState
import com.fraudcalldetector.domain.repository.CallRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CallRepositoryImpl @Inject constructor(
    private val dao: CallEventDao
) : CallRepository {

    override fun observeActiveCalls(): Flow<List<CallEvent>> =
        dao.observeActiveCalls().map { list -> list.map { it.toDomain() } }

    override fun observeCallHistory(): Flow<List<CallEvent>> =
        dao.observeAll().map { list -> list.map { it.toDomain() } }

    override suspend fun getCallById(id: String): CallEvent? =
        dao.getById(id)?.toDomain()

    override suspend fun insertCall(call: CallEvent) =
        dao.insert(call.toEntity())

    override suspend fun updateCall(call: CallEvent) =
        dao.update(call.toEntity())

    override suspend fun updateCallState(id: String, state: CallState) =
        dao.updateState(id, state.name)

    override suspend fun deleteCall(id: String) =
        dao.delete(id)

    override suspend fun getCallCount(): Int =
        dao.count()
}
