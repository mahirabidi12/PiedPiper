package com.disastermesh.app.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.disastermesh.app.db.entities.CriticalPoiAmenity
import com.disastermesh.app.db.entities.CriticalPoiEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CriticalPoiDao {

    @Query(
        """
        SELECT * FROM critical_pois
        WHERE latitude BETWEEN :south AND :north
          AND longitude BETWEEN :west AND :east
        ORDER BY updated_at DESC
        LIMIT :limit
        """
    )
    suspend fun getInBounds(
        south: Double,
        north: Double,
        west: Double,
        east: Double,
        limit: Int
    ): List<CriticalPoiEntity>

    @Query(
        """
        SELECT * FROM critical_pois
        WHERE latitude BETWEEN :south AND :north
          AND (longitude >= :west OR longitude <= :east)
        ORDER BY updated_at DESC
        LIMIT :limit
        """
    )
    suspend fun getInBoundsAcrossDateLine(
        south: Double,
        north: Double,
        west: Double,
        east: Double,
        limit: Int
    ): List<CriticalPoiEntity>

    @Query("SELECT * FROM critical_pois WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): CriticalPoiEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: CriticalPoiEntity)

    @Transaction
    suspend fun upsertIfNewer(entity: CriticalPoiEntity) {
        val existing = getById(entity.id)
        if (existing == null || entity.updatedAt >= existing.updatedAt) {
            insert(entity)
        }
    }

    @Query(
        """
        UPDATE critical_pois
        SET operational_status = :status,
            is_verified = :isVerified,
            updated_at = :updatedAt
        WHERE id = :id
          AND updated_at <= :updatedAt
        """
    )
    suspend fun updateStatusIfNewer(
        id: String,
        status: String,
        isVerified: Boolean,
        updatedAt: Long
    ): Int

    @Query("SELECT IFNULL(MAX(updated_at), 0) FROM critical_pois")
    fun observeLatestUpdateTick(): Flow<Long>

    @Query("SELECT * FROM critical_pois WHERE updated_at >= :since ORDER BY updated_at ASC")
    suspend fun getUpdatedSince(since: Long): List<CriticalPoiEntity>

    @Query("SELECT * FROM critical_pois WHERE amenity_type IN (:amenities)")
    suspend fun getByAmenitySet(amenities: List<String> = CriticalPoiAmenity.allowed.toList()): List<CriticalPoiEntity>
}
