package com.disastermesh.app.db.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "audit_log",
    indices = [Index("entity_type"), Index("created_at")]
)
data class AuditLogEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "entity_type")   val entityType: String,
    @ColumnInfo(name = "entity_id")     val entityId: String,
    @ColumnInfo(name = "entity_label")  val entityLabel: String,
    val action: String,                  // ADD | ADJUST | DELETE | STATUS_CHANGE | ASSIGN
    @ColumnInfo(name = "actor_node_id") val actorNodeId: String,
    @ColumnInfo(name = "actor_name")    val actorName: String,
    val detail: String,
    @ColumnInfo(name = "created_at")    val createdAt: Long
)
