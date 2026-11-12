package com.fraudcalldetector.feature.stt.local

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Room database for STT transcript storage.
 *
 * Version history:
 *  1 — Initial schema: TranscriptEntity (callEventId, fullText, language, createdAt)
 *  2 — Added: segmentCount, updatedAt columns to TranscriptEntity
 */
@Database(
    entities = [TranscriptEntity::class],
    version  = 2,
    exportSchema = false
)
abstract class SttDatabase : RoomDatabase() {
    abstract fun transcriptDao(): TranscriptDao

    companion object {
        const val DATABASE_NAME = "stt_db"
    }
}
