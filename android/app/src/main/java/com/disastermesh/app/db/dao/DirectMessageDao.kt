package com.disastermesh.app.db.dao

import androidx.room.*
import com.disastermesh.app.db.entities.DirectMessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DirectMessageDao {

    @Query("SELECT * FROM direct_messages WHERE thread_id = :threadId ORDER BY created_at ASC")
    fun observeThread(threadId: String): Flow<List<DirectMessageEntity>>

    /** Returns the latest message per thread — used to build the peer inbox list. */
    @Query("""
        SELECT * FROM direct_messages
        WHERE id IN (
            SELECT id FROM direct_messages
            GROUP BY thread_id
            HAVING created_at = MAX(created_at)
        )
        ORDER BY created_at DESC
    """)
    fun observeLatestPerThread(): Flow<List<DirectMessageEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfNew(entity: DirectMessageEntity): Long
}
