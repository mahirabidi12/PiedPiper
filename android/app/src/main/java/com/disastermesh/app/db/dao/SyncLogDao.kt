package com.disastermesh.app.db.dao

import androidx.room.*
import com.disastermesh.app.db.entities.SyncLogEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncLogDao {

    @Query("SELECT * FROM sync_log ORDER BY created_at DESC LIMIT 200")
    fun observeRecent(): Flow<List<SyncLogEntity>>

    @Insert
    suspend fun insert(entry: SyncLogEntity)

    @Query("DELETE FROM sync_log WHERE created_at < :cutoff")
    suspend fun pruneOlderThan(cutoff: Long)
}
