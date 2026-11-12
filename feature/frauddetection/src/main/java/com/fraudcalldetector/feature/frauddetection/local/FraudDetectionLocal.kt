package com.fraudcalldetector.feature.frauddetection.local

import androidx.room.*
import com.fraudcalldetector.domain.model.FraudAnalysis
import com.fraudcalldetector.domain.model.FraudFlag
import com.fraudcalldetector.domain.model.FraudFlagType
import com.fraudcalldetector.domain.model.RiskLevel
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.Flow

// ─────────────────────────────────────────────────────────────────────────────
// Entity
// ─────────────────────────────────────────────────────────────────────────────

@Entity(tableName = "fraud_analyses")
data class FraudAnalysisEntity(
    @PrimaryKey val callId    : String,
    val isFraud               : Boolean,
    val confidence            : Float,
    val riskLevel             : String,   // RiskLevel.name
    val flagsJson             : String,   // JSON array of FraudFlagEntity
    val analysedAt            : Long,
    val updatedAt             : Long = analysedAt
)

/** Lightweight representation of FraudFlag stored inside [FraudAnalysisEntity.flagsJson]. */
data class FraudFlagRecord(
    val type       : String,
    val description: String,
    val severity   : Float
)

// ─────────────────────────────────────────────────────────────────────────────
// DAO
// ─────────────────────────────────────────────────────────────────────────────

@Dao
interface FraudAnalysisDao {

    @Query("SELECT * FROM fraud_analyses WHERE callId = :callId LIMIT 1")
    suspend fun getByCallId(callId: String): FraudAnalysisEntity?

    @Query("SELECT * FROM fraud_analyses WHERE callId = :callId LIMIT 1")
    fun observeByCallId(callId: String): Flow<FraudAnalysisEntity?>

    @Query("SELECT * FROM fraud_analyses ORDER BY analysedAt DESC")
    fun observeAll(): Flow<List<FraudAnalysisEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: FraudAnalysisEntity)

    @Query("DELETE FROM fraud_analyses WHERE callId = :callId")
    suspend fun deleteByCallId(callId: String)

    @Query("SELECT COUNT(*) FROM fraud_analyses")
    suspend fun count(): Int
}

// ─────────────────────────────────────────────────────────────────────────────
// Database
// ─────────────────────────────────────────────────────────────────────────────

@Database(
    entities      = [FraudAnalysisEntity::class],
    version       = 1,
    exportSchema  = false
)
abstract class FraudDetectionDatabase : RoomDatabase() {
    abstract fun fraudAnalysisDao(): FraudAnalysisDao

    companion object {
        const val DATABASE_NAME = "fraud_detection_db"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Mappers
// ─────────────────────────────────────────────────────────────────────────────

private val gson = Gson()
private val flagListType = object : TypeToken<List<FraudFlagRecord>>() {}.type

fun FraudAnalysis.toEntity(callId: String): FraudAnalysisEntity {
    val records = flags.map { f ->
        FraudFlagRecord(f.type.name, f.description, f.severity)
    }
    return FraudAnalysisEntity(
        callId      = callId,
        isFraud     = isFraud,
        confidence  = confidence,
        riskLevel   = riskLevel.name,
        flagsJson   = gson.toJson(records),
        analysedAt  = analysedAt,
        updatedAt   = System.currentTimeMillis()
    )
}

fun FraudAnalysisEntity.toDomain(): FraudAnalysis {
    val records: List<FraudFlagRecord> = runCatching {
        gson.fromJson<List<FraudFlagRecord>>(flagsJson, flagListType)
    }.getOrNull() ?: emptyList()

    return FraudAnalysis(
        isFraud    = isFraud,
        confidence = confidence,
        riskLevel  = runCatching { RiskLevel.valueOf(riskLevel) }.getOrDefault(RiskLevel.SAFE),
        flags      = records.map { r ->
            FraudFlag(
                type        = runCatching { FraudFlagType.valueOf(r.type) }
                                  .getOrDefault(FraudFlagType.SUSPICIOUS_KEYWORDS),
                description = r.description,
                severity    = r.severity
            )
        },
        analysedAt = analysedAt
    )
}
