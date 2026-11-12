package com.fraudcalldetector.feature.callhistory

import app.cash.turbine.test
import com.fraudcalldetector.domain.model.*
import com.fraudcalldetector.domain.usecase.DeleteCallUseCase
import com.fraudcalldetector.domain.usecase.GetCallHistoryUseCase
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CallHistoryViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var getCallHistory: GetCallHistoryUseCase
    private lateinit var deleteCall: DeleteCallUseCase
    private lateinit var viewModel: CallHistoryViewModel

    private val sampleCalls = listOf(
        CallEvent(
            id = "1",
            phoneNumber = "5551234567",
            callerName = "Alice",
            startTime = System.currentTimeMillis() - 60_000,
            endTime = System.currentTimeMillis(),
            direction = CallDirection.INBOUND,
            state = CallState.ENDED,
            fraudAnalysis = FraudAnalysis(true, 0.92f, RiskLevel.HIGH, emptyList())
        ),
        CallEvent(
            id = "2",
            phoneNumber = "5559876543",
            callerName = null,
            startTime = System.currentTimeMillis() - 120_000,
            endTime = System.currentTimeMillis() - 60_000,
            direction = CallDirection.OUTBOUND,
            state = CallState.ENDED,
            fraudAnalysis = FraudAnalysis(false, 0.05f, RiskLevel.SAFE, emptyList())
        )
    )

    @Before
    fun setUp() {
        getCallHistory = mockk { every { invoke() } returns flowOf(sampleCalls) }
        deleteCall = mockk(relaxed = true)
        viewModel = CallHistoryViewModel(getCallHistory, deleteCall)
    }

    @Test
    fun `initial state loads all calls`() = runTest(testDispatcher) {
        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals(2, state.calls.size)
            assertEquals(HistoryFilter.ALL, state.activeFilter)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `filter FRAUD_ONLY shows only fraud calls`() = runTest(testDispatcher) {
        viewModel.onEvent(CallHistoryEvent.FilterChanged(HistoryFilter.FRAUD_ONLY))
        viewModel.uiState.test {
            val state = awaitItem()
            assertTrue(state.calls.all { it.fraudAnalysis?.isFraud == true })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `filter SAFE_ONLY shows only safe calls`() = runTest(testDispatcher) {
        viewModel.onEvent(CallHistoryEvent.FilterChanged(HistoryFilter.SAFE_ONLY))
        viewModel.uiState.test {
            val state = awaitItem()
            assertTrue(state.calls.all { it.fraudAnalysis?.isFraud == false })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `search by phone number filters correctly`() = runTest(testDispatcher) {
        viewModel.onEvent(CallHistoryEvent.SearchQueryChanged("123"))
        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals(1, state.calls.size)
            assertTrue(state.calls.first().phoneNumber.contains("123"))
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `delete call invokes use case`() = runTest(testDispatcher) {
        viewModel.onEvent(CallHistoryEvent.DeleteCall("1"))
        coVerify { deleteCall("1") }
    }
}
