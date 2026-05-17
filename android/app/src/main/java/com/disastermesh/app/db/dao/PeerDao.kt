package com.disastermesh.app.db.dao

import androidx.room.*
import com.disastermesh.app.db.entities.PeerEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PeerDao {

    @Query("SELECT * FROM peers ORDER BY last_seen DESC")
    fun observeAll(): Flow<List<PeerEntity>>

    @Query("SELECT * FROM peers WHERE connection_state = 'CONNECTED'")
    fun observeConnected(): Flow<List<PeerEntity>>

    @Query("SELECT * FROM peers WHERE role = :role AND last_seen >= :since ORDER BY last_seen DESC")
    fun observeRecentlySeenByRole(role: String, since: Long): Flow<List<PeerEntity>>

    @Query("SELECT * FROM peers WHERE node_id = :nodeId LIMIT 1")
    suspend fun getByNodeId(nodeId: String): PeerEntity?

    @Query("SELECT * FROM peers WHERE endpoint_id = :endpointId LIMIT 1")
    suspend fun getByEndpointId(endpointId: String): PeerEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: PeerEntity)

    @Query("UPDATE peers SET connection_state = 'LOST', endpoint_id = NULL WHERE endpoint_id = :endpointId")
    suspend fun markLost(endpointId: String)

    @Query("UPDATE peers SET connection_state = 'LOST', endpoint_id = NULL WHERE connection_state = 'CONNECTED'")
    suspend fun markAllLost()
}
