package com.disastermesh.app.db.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Gossip deduplication table — one row per packet UUID we have already processed. */
@Entity(
    tableName = "seen_packets",
    indices = [Index("seen_at")]
)
data class SeenPacketEntity(
    @PrimaryKey
    val id: String,                               // packet UUID

    @ColumnInfo(name = "packet_type")   val packetType: String,
    @ColumnInfo(name = "origin_node_id") val originNodeId: String,
    @ColumnInfo(name = "seen_at")       val seenAt: Long
)
