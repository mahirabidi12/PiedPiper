package com.disastermesh.app.db.dao

import androidx.room.*
import com.disastermesh.app.db.entities.InventoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface InventoryDao {

    @Query("SELECT * FROM inventory ORDER BY label ASC")
    fun observeAll(): Flow<List<InventoryEntity>>

    @Query("SELECT * FROM inventory WHERE key = :key LIMIT 1")
    suspend fun getByKey(key: String): InventoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: InventoryEntity)

    @Query("UPDATE inventory SET count = MAX(0, count + :delta) WHERE key = :key")
    suspend fun adjustCount(key: String, delta: Int)

    @Query("SELECT COUNT(*) FROM inventory")
    suspend fun count(): Int
}
