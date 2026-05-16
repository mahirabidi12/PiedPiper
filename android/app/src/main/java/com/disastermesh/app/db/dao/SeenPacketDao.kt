package com.disastermesh.app.db.dao

import androidx.room.*
import com.disastermesh.app.db.entities.SeenPacketEntity

@Dao
interface SeenPacketDao {

    @Query("SELECT COUNT(*) > 0 FROM seen_packets WHERE id = :packetId")
    suspend fun isSeen(packetId: String): Boolean

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun markSeen(entity: SeenPacketEntity)

    /** Prune entries older than [cutoffMs] to prevent unbounded growth. */
    @Query("DELETE FROM seen_packets WHERE seen_at < :cutoffMs")
    suspend fun pruneOlderThan(cutoffMs: Long)

    @Query("SELECT COUNT(*) FROM seen_packets")
    suspend fun count(): Int
}
