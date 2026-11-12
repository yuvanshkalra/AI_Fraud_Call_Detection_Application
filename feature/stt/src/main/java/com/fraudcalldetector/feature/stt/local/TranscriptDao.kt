package com.fraudcalldetector.feature.stt.local

import androidx.room.*
import com.fraudcalldetector.domain.model.TranscriptResult
import kotlinx.coroutines.flow.Flow

// ─────────────────────────────────────────────────────────────────────────────
// Entity
// ─────────────────────────────────────────────────────────────────────────────

@Entity(tableName = "transcripts")
data class TranscriptEntity(
    @PrimaryKey val callEventId : String,
    val fullText                : String,
    val language                : String,
    val segmentCount            : Int  = 0,
    val createdAt               : Long,
    val updatedAt               : Long = createdAt
)

// ─────────────────────────────────────────────────────────────────────────────
// DAO
// ─────────────────────────────────────────────────────────────────────────────

@Dao
interface TranscriptDao {

    @Query("SELECT * FROM transcripts WHERE callEventId = :callId LIMIT 1")
    suspend fun getByCallId(callId: String): TranscriptEntity?

    /**
     * Live observation — emits every time the row changes.
     * Used by [SttRepositoryImpl.observeTranscript].
     */
    @Query("SELECT * FROM transcripts WHERE callEventId = :callId LIMIT 1")
    fun observeByCallId(callId: String): Flow<TranscriptEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: TranscriptEntity)

    @Update
    suspend fun update(entity: TranscriptEntity)

    @Query("DELETE FROM transcripts WHERE callEventId = :callId")
    suspend fun deleteByCallId(callId: String)

    @Query("SELECT COUNT(*) FROM transcripts")
    suspend fun count(): Int
}

// ─────────────────────────────────────────────────────────────────────────────
// Mappers
// ─────────────────────────────────────────────────────────────────────────────

fun TranscriptResult.toEntity() = TranscriptEntity(
    callEventId  = callEventId,
    fullText     = fullText,
    language     = language,
    segmentCount = segments.size,
    createdAt    = createdAt,
    updatedAt    = System.currentTimeMillis()
)

fun TranscriptEntity.toDomain() = TranscriptResult(
    callEventId = callEventId,
    fullText    = fullText,
    segments    = emptyList(),   // per-segment storage is handled by SttManager in-memory
    language    = language,
    createdAt   = createdAt
)
