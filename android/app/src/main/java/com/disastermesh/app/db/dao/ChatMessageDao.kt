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

    @Query("SELECT * FROM mesh_chat_messages WHERE ttl > hop_count ORDER BY created_at ASC")
    suspend fun getAllForSync(): List<ChatMessageEntity>

    @Query("DELETE FROM mesh_chat_messages WHERE created_at < :cutoff")
    suspend fun purgeOld(cutoff: Long)
}
