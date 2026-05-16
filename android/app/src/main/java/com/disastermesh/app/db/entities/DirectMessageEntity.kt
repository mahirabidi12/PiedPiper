package com.disastermesh.app.db.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "direct_messages",
    indices = [Index("thread_id"), Index("created_at")]
)
data class DirectMessageEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "thread_id")          val threadId: String,
    @ColumnInfo(name = "sender_node_id")     val senderNodeId: String,
    @ColumnInfo(name = "sender_name")        val senderName: String,
    @ColumnInfo(name = "recipient_node_id")  val recipientNodeId: String,
    val text: String,
    @ColumnInfo(name = "created_at")         val createdAt: Long
)
