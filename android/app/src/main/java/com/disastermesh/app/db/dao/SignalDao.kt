package com.disastermesh.app.db.dao

import androidx.room.*
import com.disastermesh.app.db.entities.SignalEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SignalDao {

    @Query("SELECT * FROM signals ORDER BY updated_at DESC")
    fun observeAll(): Flow<List<SignalEntity>>

    @Query("SELECT * FROM signals WHERE sender_node_id = :nodeId ORDER BY updated_at DESC")
    fun observeByNode(nodeId: String): Flow<List<SignalEntity>>

    @Query("SELECT * FROM signals WHERE status != 'RESOLVED' AND status != 'EXPIRED' ORDER BY updated_at DESC")
    fun observeActive(): Flow<List<SignalEntity>>

    @Query("SELECT * FROM signals WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): SignalEntity?

    /** Last-write-wins upsert on updated_at. */
    @Transaction
    suspend fun upsert(entity: SignalEntity) {
        val existing = getById(entity.id)
        if (existing == null || entity.updatedAt >= existing.updatedAt) {
            insert(entity)
        }
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: SignalEntity)

    @Query("SELECT * FROM signals WHERE ttl > hop_count ORDER BY created_at ASC")
    suspend fun getAllForSync(): List<SignalEntity>

    @Query("DELETE FROM signals WHERE status = 'RESOLVED' AND updated_at < :cutoff")
    suspend fun purgeOldResolved(cutoff: Long)

    /** Called by SignalProcessor once Gemma has classified a signal. */
    @Query("""
        UPDATE signals
        SET category = :category, priority = :priority,
            ai_summary = :summary, ai_classified = 1, updated_at = :now
        WHERE id = :id
    """)
    suspend fun applyAiClassification(
        id: String, category: String, priority: String, summary: String, now: Long
    )

    /** All signals Gemma has not yet processed (processor runs these on startup/model-ready). */
    @Query("SELECT * FROM signals WHERE ai_classified = 0 ORDER BY created_at ASC")
    suspend fun getAllUnclassified(): List<SignalEntity>

    /** QUEUED signals created by this node while offline — promoted to NEW on peer connect. */
    @Query("SELECT * FROM signals WHERE status = 'QUEUED' AND sender_node_id = :nodeId")
    suspend fun getQueuedByNode(nodeId: String): List<SignalEntity>

    @Query("UPDATE signals SET status = :status, updated_at = :now WHERE id = :id")
    suspend fun updateStatus(id: String, status: String, now: Long = System.currentTimeMillis())

    @Query("""
        UPDATE signals
        SET assigned_volunteer_id   = :volunteerId,
            assigned_volunteer_name = :volunteerName,
            inventory_allocated     = :inventoryJson,
            status                  = :status,
            updated_at              = :now
        WHERE id = :id
    """)
    suspend fun updateAssignment(
        id: String,
        volunteerId: String,
        volunteerName: String,
        inventoryJson: String,
        status: String,
        now: Long = System.currentTimeMillis()
    )

    @Query("""
        UPDATE signals
        SET assigned_volunteer_id   = NULL,
            assigned_volunteer_name = NULL,
            inventory_allocated     = NULL,
            status                  = :status,
            updated_at              = :now
        WHERE id = :id
    """)
    suspend fun clearAssignment(id: String, status: String, now: Long = System.currentTimeMillis())
}
