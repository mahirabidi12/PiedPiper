package com.disastermesh

import org.json.JSONObject
import java.util.UUID

data class Message(
    val id: String = UUID.randomUUID().toString(),
    val senderId: String,
    val senderName: String,
    val senderRole: String = Role.USER.name,
    val text: String,
    val targetRole: String = "ALL",
    val timestamp: Long = System.currentTimeMillis()
) {
    fun toJson(): String = JSONObject().apply {
        put("id", id)
        put("senderId", senderId)
        put("senderName", senderName)
        put("senderRole", senderRole)
        put("text", text)
        put("targetRole", targetRole)
        put("timestamp", timestamp)
    }.toString()

    companion object {
        fun fromJson(json: String): Message {
            val obj = JSONObject(json)
            return Message(
                id         = obj.getString("id"),
                senderId   = obj.getString("senderId"),
                senderName = obj.getString("senderName"),
                senderRole = obj.optString("senderRole", Role.USER.name),
                text       = obj.getString("text"),
                targetRole = obj.optString("targetRole", "ALL"),
                timestamp  = obj.getLong("timestamp")
            )
        }
    }

    fun isVisibleTo(myRole: Role): Boolean = when (targetRole) {
        "VOLUNTEER" -> myRole == Role.VOLUNTEER || myRole == Role.AUTHORITY
        "AUTHORITY" -> myRole == Role.AUTHORITY
        else        -> true
    }

    fun role(): Role = Role.fromName(senderRole)
}
