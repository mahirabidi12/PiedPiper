package com.disastermesh.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.disastermesh.Message
import com.disastermesh.Role

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey val id: String,
    val senderId: String,
    val senderName: String,
    val senderRole: String,
    val text: String,
    val targetRole: String,
    val timestamp: Long
) {
    fun toMessage() = Message(
        id         = id,
        senderId   = senderId,
        senderName = senderName,
        senderRole = senderRole,
        text       = text,
        targetRole = targetRole,
        timestamp  = timestamp
    )

    companion object {
        fun fromMessage(m: Message) = MessageEntity(
            id         = m.id,
            senderId   = m.senderId,
            senderName = m.senderName,
            senderRole = m.senderRole,
            text       = m.text,
            targetRole = m.targetRole,
            timestamp  = m.timestamp
        )
    }
}
