package com.disastermesh.app.db.dao

import androidx.room.*
import com.disastermesh.app.db.entities.AiSessionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AiSessionDao {

    @Query("SELECT * FROM ai_sessions ORDER BY last_message_at DESC")
    fun observeAll(): Flow<List<AiSessionEntity>>

    @Query("SELECT * FROM ai_sessions WHERE id = :id")
    suspend fun getById(id: String): AiSessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(session: AiSessionEntity)

    /** Bumps the activity stamp and increments the active-message counter. */
    @Query("""
        UPDATE ai_sessions
        SET last_message_at = :ts, message_count = message_count + 1
        WHERE id = :sessionId
    """)
    suspend fun bumpActivity(sessionId: String, ts: Long)

    @Query("UPDATE ai_sessions SET language_code = :langCode WHERE id = :sessionId")
    suspend fun setLanguage(sessionId: String, langCode: String)

    @Query("DELETE FROM ai_sessions WHERE id = :id")
    suspend fun delete(id: String)
}
