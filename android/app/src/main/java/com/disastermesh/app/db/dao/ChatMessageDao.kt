package com.disastermesh.app.db.dao

import androidx.room.*
import com.disastermesh.app.db.entities.ChatMessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatMessageDao {

    @Query("SELECT * FROM mesh_chat_messages WHERE room_id = :roomId ORDER BY created_at ASC")
    fun observeRoom(roomId: String): Flow<List<ChatMessageEntity>>

    @Query("SELECT * FROM mesh_chat_messages WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): ChatMessageEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfNew(entity: ChatMessageEntity): Long

    /** Returns true if the message was new (not a duplicate). */
    suspend fun insertIfNew(entity: ChatMessageEntity, onNew: suspend () -> Unit): Boolean {
        val rows = insertIfNew(entity)
        val isNew = rows != -1L
        if (isNew) onNew()
        return isNew
    }

    @Query("SELECT COUNT(*) FROM mesh_chat_messages WHERE room_id = :roomId")
    suspend fun countInRoom(roomId: String): Int

    /**
     * Bounded sync — only forward recent (within window), unexpired messages
     * on reconnect. Replaces getAllForSync() which would re-flood the entire
     * (possibly thousands-of-rows) history on every peer reconnect.
     */
    @Query("SELECT * FROM mesh_chat_messages WHERE ttl > hop_count AND created_at >= :sinceMs ORDER BY created_at ASC")
    suspend fun getRecentForSync(sinceMs: Long): List<ChatMessageEntity>

    /**
     * Content-level dedup safety net. The primary defence is the packet id,
     * but if any code path ever issues a fresh id for an existing logical
     * message (as the old buggy store-and-forward did), this catches it
     * before the message is rendered to the user.
     */
    @Query("SELECT EXISTS(SELECT 1 FROM mesh_chat_messages WHERE sender_node_id = :sender AND room_id = :room AND text = :text)")
    suspend fun existsByContent(sender: String, room: String, text: String): Boolean

    /**
     * One-time cleanup: remove rows whose (sender, room, text) tuple already
     * has an older copy. Used on app start to flush garbage rows the earlier
     * buggy version produced. Returns the number of rows deleted.
     */
    @Query("""
        DELETE FROM mesh_chat_messages
        WHERE rowid NOT IN (
            SELECT MIN(rowid) FROM mesh_chat_messages
            GROUP BY sender_node_id, room_id, text
        )
    """)
    suspend fun purgeContentDuplicates(): Int

    @Query("DELETE FROM mesh_chat_messages WHERE created_at < :cutoff")
    suspend fun purgeOld(cutoff: Long)
}
