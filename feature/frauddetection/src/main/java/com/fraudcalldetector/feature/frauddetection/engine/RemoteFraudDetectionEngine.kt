package com.fraudcalldetector.feature.frauddetection.engine

import com.fraudcalldetector.core.network.ApiResult
import com.fraudcalldetector.core.network.safeApiCall
import com.fraudcalldetector.domain.fraud.*
import com.fraudcalldetector.feature.frauddetection.api.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Production [FraudDetectionEngine] backed by the teammates' AI REST endpoint.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * Configuration
 * ─────────────────────────────────────────────────────────────────────────────
 * 1. Set the base URL in [FraudDetectionModule.FRAUD_API_BASE_URL].
 * 2. Authentication headers are injected by [AuthInterceptor] in :core.
 * 3. The exact JSON contract is documented in [FraudDetectionApiService].
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * Mapping
 * ─────────────────────────────────────────────────────────────────────────────
 * [FraudDetectionRequest]  →  [FraudDetectionRequestDto]  (JSON body)
 * [FraudDetectionResponseDto]  →  [FraudDetectionEngineResult]
 *
 * The response field [FraudDetectionResponseDto.riskScore] maps to
 * [FraudDetectionEngineResult.riskScore] as-is; the deprecated
 * [FraudDetectionResponse.confidence] field is no longer used.
 */
@Singleton
class RemoteFraudDetectionEngine @Inject constructor(
    private val apiService: FraudDetectionApiService
) : FraudDetectionEngine {

    override val supportsIncrementalAnalysis: Boolean = false

    // ─────────────────────────────────────────────────────────────────────────
    // FraudDetectionEngine — synchronous
    // ─────────────────────────────────────────────────────────────────────────

    override suspend fun analyse(request: FraudDetectionRequest): FraudDetectionEngineResult {
        Timber.d("RemoteFraudDetectionEngine.analyse callId=${request.callId}")

        val dto = FraudDetectionRequestDto(
            callId    = request.callId,
            transcript = request.transcript,
            language  = request.language,
            metadata  = RequestMetadataDto(
                callerNumber = request.callerNumber,
                durationMs   = request.durationMs,
                chunkIndex   = request.chunkIndex,
                isFinal      = request.isFinalPass
            )
        )

        return when (val result = safeApiCall { apiService.detectFraud(dto) }) {
            is ApiResult.Success -> {
                Timber.d("RemoteFraudDetectionEngine: success riskLevel=${result.data.riskLevel}")
                result.data.toEngineResult()
            }
            is ApiResult.Error -> {
                val retryable = (result.code ?: 0) >= 500
                val msg = "Fraud API error ${result.code}: ${result.message}"
                Timber.e(msg)
                throw FraudDetectionEngineException(msg, httpCode = result.code, retryable = retryable)
            }
            is ApiResult.Exception -> {
                Timber.e(result.throwable, "Fraud API exception")
                throw FraudDetectionEngineException(
                    "Fraud API network error: ${result.throwable.message}",
                    cause    = result.throwable,
                    retryable = true
                )
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // FraudDetectionEngine — streaming (non-streaming fallback)
    // ─────────────────────────────────────────────────────────────────────────

    override fun analyseStream(
        requests: Flow<FraudDetectionRequest>
    ): Flow<FraudDetectionEngineResult> = flow {
        requests.collect { request ->
            try {
                emit(analyse(request))
            } catch (e: FraudDetectionEngineException) {
                // Re-throw so FraudDetectionManager can handle non-retryable failures
                throw e
            } catch (e: Exception) {
                throw FraudDetectionEngineException(
                    "Remote stream error: ${e.message}", e, retryable = true
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// DTO → Engine result mapper
// ─────────────────────────────────────────────────────────────────────────────

private fun FraudDetectionResponseDto.toEngineResult(): FraudDetectionEngineResult =
    FraudDetectionEngineResult(
        isFraud      = isFraud,
        riskScore    = riskScore,
        riskLevel    = riskLevel,
        reason       = reason,
        signals      = signals?.map { s ->
            FraudSignal(
                type        = s.type,
                description = s.description,
                severity    = s.severity
            )
        } ?: emptyList(),
        modelVersion  = modelVersion ?: "unknown",
        analysedAtMs  = System.currentTimeMillis()
    )
