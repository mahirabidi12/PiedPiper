package com.disastermesh.app.db.dao

import androidx.room.*
import com.disastermesh.app.db.entities.AiMessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AiMessageDao {

    /** Live stream of all active (non-archived) messages in a session, oldest first. */
    @Query("""
        SELECT * FROM ai_messages
        WHERE session_id = :sessionId AND archived = 0
        ORDER BY created_at ASC
    """)
    fun observeSession(sessionId: String): Flow<List<AiMessageEntity>>

    /** Pinned snippets across all sessions — drives the emergency dashboard. */
    @Query("SELECT * FROM ai_messages WHERE pinned = 1 ORDER BY created_at DESC")
    fun observePinned(): Flow<List<AiMessageEntity>>

    /**
     * Latest [limit] active messages in a session (newest first).
     * Used by Feature 5 (battery-saver) to hydrate only a small in-memory window;
     * the rest are loaded on-demand by paging back through SQLite.
     */
    @Query("""
        SELECT * FROM ai_messages
        WHERE session_id = :sessionId AND archived = 0
        ORDER BY created_at DESC
        LIMIT :limit
    """)
    suspend fun latestN(sessionId: String, limit: Int): List<AiMessageEntity>

    /** All active messages, oldest first — used by sitrep export. */
    @Query("""
        SELECT * FROM ai_messages
        WHERE session_id = :sessionId AND archived = 0
        ORDER BY created_at ASC
    """)
    suspend fun allActive(sessionId: String): List<AiMessageEntity>

    @Query("SELECT COUNT(*) FROM ai_messages WHERE session_id = :sessionId AND archived = 0")
    suspend fun activeCount(sessionId: String): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(message: AiMessageEntity)

    @Query("UPDATE ai_messages SET pinned = :pinned WHERE id = :id")
    suspend fun setPinned(id: String, pinned: Boolean)

    /**
     * Archive everything in a session except the most recent [keep] active rows.
     * Pinned rows are preserved regardless of age — they are the user's vital
     * snippets and must not disappear from the dashboard.
     */
    @Query("""
        UPDATE ai_messages SET archived = 1
        WHERE session_id = :sessionId
          AND archived = 0
          AND pinned = 0
          AND id NOT IN (
              SELECT id FROM ai_messages
              WHERE session_id = :sessionId AND archived = 0
              ORDER BY created_at DESC
              LIMIT :keep
          )
    """)
    suspend fun archivePastCap(sessionId: String, keep: Int)
}
