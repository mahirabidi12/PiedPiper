package com.disastermesh.app.db.dao

import androidx.room.*
import com.disastermesh.app.db.entities.AuditLogEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AuditLogDao {
    @Query("SELECT * FROM audit_log ORDER BY created_at DESC LIMIT 100")
    fun observeRecent(): Flow<List<AuditLogEntity>>

    @Query("SELECT * FROM audit_log WHERE entity_type = :type ORDER BY created_at DESC LIMIT 50")
    fun observeByType(type: String): Flow<List<AuditLogEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entry: AuditLogEntity)

    @Query("DELETE FROM audit_log WHERE created_at < :cutoff")
    suspend fun pruneOlderThan(cutoff: Long)
}
