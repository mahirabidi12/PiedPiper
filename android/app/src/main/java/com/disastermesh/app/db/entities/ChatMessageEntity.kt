package com.disastermesh.app.db.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.disastermesh.app.model.ChatMessage
import com.disastermesh.app.model.Role

@Entity(
    tableName = "mesh_chat_messages",
    indices = [Index("room_id"), Index("created_at")]
)
data class ChatMessageEntity(
    @PrimaryKey
    val id: String,

    @ColumnInfo(name = "room_id")         val roomId: String,
    @ColumnInfo(name = "sender_node_id")  val senderNodeId: String,
    @ColumnInfo(name = "sender_name")     val senderName: String,
    @ColumnInfo(name = "sender_role")     val senderRole: String,
    val text: String,
    val ttl: Int = 8,
    @ColumnInfo(name = "hop_count")       val hopCount: Int = 0,
    @ColumnInfo(name = "created_at")      val createdAt: Long
) {
    fun toDomain() = ChatMessage(
        id           = id,
        roomId       = roomId,
        senderNodeId = senderNodeId,
        senderName   = senderName,
        senderRole   = Role.fromString(senderRole),
        text         = text,
        ttl          = ttl,
        hopCount     = hopCount,
        createdAt    = createdAt
    )

    companion object {
        fun fromDomain(m: ChatMessage) = ChatMessageEntity(
            id           = m.id,
            roomId       = m.roomId,
            senderNodeId = m.senderNodeId,
            senderName   = m.senderName,
            senderRole   = m.senderRole.name,
            text         = m.text,
            ttl          = m.ttl,
            hopCount     = m.hopCount,
            createdAt    = m.createdAt
        )
    }
}
