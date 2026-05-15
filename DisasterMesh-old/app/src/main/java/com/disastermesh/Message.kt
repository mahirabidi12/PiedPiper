package com.disastermesh

import org.json.JSONObject
import java.util.UUID

object MessageType {
    const val CHAT = "CHAT"
    const val SIGNAL = "SIGNAL"
}

data class Message(
    val id: String = UUID.randomUUID().toString(),
    val senderId: String,
    val senderName: String,
    val senderRole: String = Role.USER.name,
    val text: String,
    val targetRole: String = AppConstants.TARGET_ALL,
    val messageType: String = MessageType.CHAT,
    val locationText: String? = null,       // manual location description
    val latitude: Double? = null,           // GPS
    val longitude: Double? = null,          // GPS
    val timestamp: Long = System.currentTimeMillis()
) {
    fun toJson(): String = JSONObject().apply {
        put("id", id)
        put("senderId", senderId)
        put("senderName", senderName)
        put("senderRole", senderRole)
        put("text", text)
        put("targetRole", targetRole)
        put("messageType", messageType)
        locationText?.let { put("locationText", it) }
        latitude?.let { put("latitude", it) }
        longitude?.let { put("longitude", it) }
        put("timestamp", timestamp)
    }.toString()

    companion object {
        fun fromJson(json: String): Message {
            val obj = JSONObject(json)
            return Message(
                id           = obj.getString("id"),
                senderId     = obj.getString("senderId"),
                senderName   = obj.getString("senderName"),
                senderRole   = obj.optString("senderRole", Role.USER.name),
                text         = obj.getString("text"),
                targetRole   = obj.optString("targetRole", AppConstants.TARGET_ALL),
                messageType  = obj.optString("messageType", MessageType.CHAT),
                locationText = if (obj.has("locationText")) obj.getString("locationText") else null,
                latitude     = if (obj.has("latitude")) obj.getDouble("latitude") else null,
                longitude    = if (obj.has("longitude")) obj.getDouble("longitude") else null,
                timestamp    = obj.getLong("timestamp")
            )
        }
    }

    fun isVisibleTo(myRole: Role): Boolean = when (targetRole) {
        AppConstants.TARGET_VOLUNTEER -> myRole == Role.VOLUNTEER || myRole == Role.AUTHORITY
        AppConstants.TARGET_AUTHORITY -> myRole == Role.AUTHORITY
        else                          -> true
    }

    fun role(): Role = Role.fromName(senderRole)
}
