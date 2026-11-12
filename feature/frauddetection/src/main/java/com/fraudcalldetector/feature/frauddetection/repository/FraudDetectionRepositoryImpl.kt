package com.fraudcalldetector.feature.frauddetection.repository

import com.fraudcalldetector.core.network.ApiResult
import com.fraudcalldetector.core.network.safeApiCall
import com.fraudcalldetector.domain.model.FraudAnalysis
import com.fraudcalldetector.domain.model.FraudFlag
import com.fraudcalldetector.domain.model.FraudFlagType
import com.fraudcalldetector.domain.model.RiskLevel
import com.fraudcalldetector.domain.repository.FraudDetectionRepository
import com.fraudcalldetector.feature.frauddetection.api.*
import com.fraudcalldetector.feature.frauddetection.local.FraudAnalysisDao
import com.fraudcalldetector.feature.frauddetection.local.toDomain
import com.fraudcalldetector.feature.frauddetection.local.toEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implements [FraudDetectionRepository].
 *
 *  • One-shot analysis via [FraudDetectionApiService] (used by [AnalyseCallUseCase]).
 *  • CRUD and live observation via [FraudAnalysisDao] (used by UI and [FraudDetectionManager]).
 *  • In-memory LRU cache for current-session results.
 */
@Singleton
class FraudDetectionRepositoryImpl @Inject constructor(
    private val apiService: FraudDetectionApiService,
    private val dao       : FraudAnalysisDao
) : FraudDetectionRepository {

    /** In-memory cache for current-session results. */
    private val cache = mutableMapOf<String, FraudAnalysis>()

    // ─────────────────────────────────────────────────────────────────────────
    // One-shot analysis (used by AnalyseCallUseCase)
    // ─────────────────────────────────────────────────────────────────────────

    override suspend fun analyseCall(
        callId       : String,
        transcript   : String,
        audioFilePath: String?
    ): FraudAnalysis {
        val request = FraudDetectionRequestDto(
            callId    = callId,
            transcript = transcript
        )

        return when (val result = safeApiCall { apiService.detectFraud(request) }) {
            is ApiResult.Success -> {
                val dto      = result.data
                val analysis = dto.toDomainAnalysis()
                cache[callId] = analysis
                saveAnalysis(callId, analysis)
                analysis
            }
            is ApiResult.Error -> {
                Timber.e("Fraud API error ${result.code}: ${result.message}")
                FraudAnalysis(false, 0f, RiskLevel.SAFE, emptyList())
            }
            is ApiResult.Exception -> {
                Timber.e(result.throwable, "Fraud API exception")
                FraudAnalysis(false, 0f, RiskLevel.SAFE, emptyList())
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CRUD + observation
    // ─────────────────────────────────────────────────────────────────────────

    override suspend fun saveAnalysis(callId: String, analysis: FraudAnalysis) {
        cache[callId] = analysis
        dao.insert(analysis.toEntity(callId))
    }

    override suspend fun getAnalysis(callId: String): FraudAnalysis? =
        dao.getByCallId(callId)?.toDomain()

    override fun observeAnalysis(callId: String): Flow<FraudAnalysis?> =
        dao.observeByCallId(callId).map { it?.toDomain() }

    override fun observeAllAnalyses(): Flow<List<FraudAnalysis>> =
        dao.observeAll().map { list -> list.map { it.toDomain() } }

    override suspend fun getCachedAnalysis(callId: String): FraudAnalysis? =
        cache[callId] ?: getAnalysis(callId)

    override suspend fun deleteAnalysis(callId: String) {
        cache.remove(callId)
        dao.deleteByCallId(callId)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// DTO → domain mapper
// ─────────────────────────────────────────────────────────────────────────────

private fun FraudDetectionResponseDto.toDomainAnalysis(): FraudAnalysis {
    val parsedRiskLevel = runCatching { RiskLevel.valueOf(riskLevel) }
        .getOrDefault(RiskLevel.SAFE)

    val flags = signals?.map { s ->
        FraudFlag(
            type        = runCatching { FraudFlagType.valueOf(s.type) }
                              .getOrDefault(FraudFlagType.SUSPICIOUS_KEYWORDS),
            description = s.description,
            severity    = s.severity
        )
    } ?: if (isFraud && reason.isNotBlank()) {
        listOf(FraudFlag(FraudFlagType.SUSPICIOUS_KEYWORDS, reason, riskScore))
    } else {
        emptyList()
    }

    return FraudAnalysis(
        isFraud    = isFraud,
        confidence = riskScore,
        riskLevel  = parsedRiskLevel,
        flags      = flags
    )
}
