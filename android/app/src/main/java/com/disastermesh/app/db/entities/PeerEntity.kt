package com.disastermesh.app.db.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.disastermesh.app.model.Peer
import com.disastermesh.app.model.PeerState
import com.disastermesh.app.model.Role

@Entity(tableName = "peers")
data class PeerEntity(
    @PrimaryKey
    @ColumnInfo(name = "node_id")          val nodeId: String,
    val name: String,
    val role: String,
    @ColumnInfo(name = "endpoint_id")      val endpointId: String?,
    @ColumnInfo(name = "connection_state") val connectionState: String,
    @ColumnInfo(name = "first_seen")       val firstSeen: Long,
    @ColumnInfo(name = "last_seen")        val lastSeen: Long
) {
    fun toDomain() = Peer(
        nodeId          = nodeId,
        name            = name,
        role            = Role.fromString(role),
        endpointId      = endpointId,
        connectionState = PeerState.valueOf(connectionState),
        firstSeen       = firstSeen,
        lastSeen        = lastSeen
    )

    companion object {
        fun fromDomain(p: Peer) = PeerEntity(
            nodeId          = p.nodeId,
            name            = p.name,
            role            = p.role.name,
            endpointId      = p.endpointId,
            connectionState = p.connectionState.name,
            firstSeen       = p.firstSeen,
            lastSeen        = p.lastSeen
        )
    }
}
