package com.disastermesh

import org.json.JSONObject
import java.util.UUID

data class Message(
    val id: String = UUID.randomUUID().toString(),
    val senderId: String,
    val senderName: String,
    val text: String,
    val timestamp: Long = System.currentTimeMillis()
) {
    fun toJson(): String = JSONObject().apply {
        put("id", id)
        put("senderId", senderId)
        put("senderName", senderName)
        put("text", text)
        put("timestamp", timestamp)
    }.toString()

    companion object {
        fun fromJson(json: String): Message {
            val obj = JSONObject(json)
            return Message(
                id = obj.getString("id"),
                senderId = obj.getString("senderId"),
                senderName = obj.getString("senderName"),
                text = obj.getString("text"),
                timestamp = obj.getLong("timestamp")
            )
        }
    }
}
