package com.disastermesh.app.db.dao

import androidx.room.*
import com.disastermesh.app.db.entities.InventoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface InventoryDao {

    @Query("SELECT * FROM inventory WHERE is_deleted = 0 ORDER BY label ASC")
    fun observeAll(): Flow<List<InventoryEntity>>

    @Query("SELECT * FROM inventory WHERE key = :key LIMIT 1")
    suspend fun getByKey(key: String): InventoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: InventoryEntity)

    @Transaction
    suspend fun upsertIfNewer(entity: InventoryEntity) {
        val existing = getByKey(entity.key)
        if (existing == null || entity.updatedAt >= existing.updatedAt) {
            upsert(entity)
        }
    }

    @Query("UPDATE inventory SET count = MAX(0, count + :delta) WHERE key = :key")
    suspend fun adjustCount(key: String, delta: Int)

    @Query("SELECT COUNT(*) FROM inventory WHERE is_deleted = 0")
    suspend fun count(): Int

    @Query("SELECT * FROM inventory WHERE is_deleted = 0 ORDER BY label ASC")
    suspend fun getActiveItems(): List<InventoryEntity>

    @Query("SELECT * FROM inventory ORDER BY updated_at ASC")
    suspend fun getAllForSync(): List<InventoryEntity>

    @Query("UPDATE inventory SET is_deleted = 1, updated_at = :now, updated_by = :by, updated_by_name = :byName WHERE key = :key")
    suspend fun markDeleted(key: String, by: String, byName: String, now: Long = System.currentTimeMillis())
}
