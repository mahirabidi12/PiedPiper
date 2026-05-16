package com.disastermesh.app.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.disastermesh.app.db.entities.SafeZoneEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SafeZoneDao {

    @Query("SELECT * FROM safe_zones ORDER BY created_at DESC")
    fun observeAll(): Flow<List<SafeZoneEntity>>

    @Query("SELECT * FROM safe_zones WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): SafeZoneEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: SafeZoneEntity)

    @Transaction
    suspend fun upsertIfNewer(entity: SafeZoneEntity) {
        val existing = getById(entity.id)
        if (existing == null || entity.createdAt >= existing.createdAt) {
            insert(entity)
        }
    }

    @Query("SELECT * FROM safe_zones ORDER BY created_at ASC")
    suspend fun getAllForSync(): List<SafeZoneEntity>

    @Query("SELECT * FROM safe_zones WHERE created_at >= :since ORDER BY created_at ASC")
    suspend fun getUpdatedSince(since: Long): List<SafeZoneEntity>
}
