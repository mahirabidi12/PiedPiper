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

    @Query("SELECT * FROM signals WHERE status NOT IN ('RESOLVED','EXPIRED','CANCELLED') ORDER BY updated_at DESC")
    fun observeActive(): Flow<List<SignalEntity>>

    /**
     * Returns signals assigned to a specific volunteer node.
     * Uses substring match on the JSON array because SQLite has no JSON_CONTAINS.
     * Node IDs are UUIDs — the `"nodeId"` pattern is collision-safe.
     */
    @Query("""
        SELECT * FROM signals
        WHERE volunteer_ids LIKE '%"' || :nodeId || '"%'
        ORDER BY updated_at DESC
    """)
    fun observeAssignedTo(nodeId: String): Flow<List<SignalEntity>>

    /**
     * Active signals assigned to a volunteer — filters out terminal states.
     */
    @Query("""
        SELECT * FROM signals
        WHERE volunteer_ids LIKE '%"' || :nodeId || '"%'
          AND status NOT IN ('RESOLVED','EXPIRED','REJECTED','CANCELLED','FAILED')
        ORDER BY updated_at DESC
    """)
    fun observeActiveAssignedTo(nodeId: String): Flow<List<SignalEntity>>

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

    @Query("""
        UPDATE signals
        SET category = :category, priority = :priority,
            ai_summary = :summary, ai_classified = 1, updated_at = :now
        WHERE id = :id
    """)
    suspend fun applyAiClassification(
        id: String, category: String, priority: String, summary: String, now: Long
    )

    @Query("SELECT * FROM signals WHERE ai_classified = 0 ORDER BY created_at ASC")
    suspend fun getAllUnclassified(): List<SignalEntity>

    @Query("SELECT * FROM signals WHERE status = 'QUEUED' AND sender_node_id = :nodeId")
    suspend fun getQueuedByNode(nodeId: String): List<SignalEntity>

    @Query("UPDATE signals SET status = :status, updated_at = :now WHERE id = :id")
    suspend fun updateStatus(id: String, status: String, now: Long = System.currentTimeMillis())

    /** Full assignment update — sets all volunteer + inventory + instruction fields atomically. */
    @Query("""
        UPDATE signals
        SET assigned_volunteer_id   = :primaryVolunteerId,
            assigned_volunteer_name = :primaryVolunteerName,
            volunteer_ids           = :volunteerIdsJson,
            volunteer_names         = :volunteerNamesJson,
            inventory_allocated     = :inventoryJson,
            instructions            = :instructions,
            status                  = :status,
            updated_at              = :now
        WHERE id = :id
    """)
    suspend fun updateAssignment(
        id: String,
        primaryVolunteerId: String,
        primaryVolunteerName: String,
        volunteerIdsJson: String,
        volunteerNamesJson: String,
        inventoryJson: String,
        instructions: String,
        status: String,
        now: Long = System.currentTimeMillis()
    )

    /** Clear all assignment data and set a terminal/refund status. */
    @Query("""
        UPDATE signals
        SET assigned_volunteer_id   = NULL,
            assigned_volunteer_name = NULL,
            volunteer_ids           = NULL,
            volunteer_names         = NULL,
            inventory_allocated     = NULL,
            status                  = :status,
            updated_at              = :now
        WHERE id = :id
    """)
    suspend fun clearAssignment(id: String, status: String, now: Long = System.currentTimeMillis())

    /** Update only status + timestamp — used by volunteers for accept/reject/progress/resolve. */
    @Query("""
        UPDATE signals
        SET status     = :status,
            updated_at = :now
        WHERE id = :id
    """)
    suspend fun updateStatusTimestamped(id: String, status: String, now: Long = System.currentTimeMillis())
}
