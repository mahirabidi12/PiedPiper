package com.disastermesh.models

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class SignalStatus { PENDING, ACKNOWLEDGED, IN_PROGRESS, RESOLVED }

data class Signal(
    val id: String = UUID.randomUUID().toString(),

    // Sender info
    val senderId: String,
    val senderName: String,

    // Raw text the civilian typed
    val rawMessage: String,

    // Gemma 4 classified fields
    val category: SignalCategory,
    val priority: Priority,
    val tags: List<String>,         // e.g. ["heart attack", "insulin", "elderly"]
    val summary: String,            // one-line for authority dashboard box
    val peopleCount: Int?,          // extracted from text, null if unknown

    // Location
    val latitude: Double?,
    val longitude: Double?,
    val locationText: String?,      // manual description: "near MG Road school"

    // Lifecycle
    val status: SignalStatus = SignalStatus.PENDING,
    val timestamp: Long = System.currentTimeMillis()
) {
    fun toJson(): String = JSONObject().apply {
        put("id", id)
        put("senderId", senderId)
        put("senderName", senderName)
        put("rawMessage", rawMessage)
        put("category", category.name)
        put("priority", priority.name)
        put("tags", JSONArray(tags))
        put("summary", summary)
        peopleCount?.let { put("peopleCount", it) }
        latitude?.let { put("latitude", it) }
        longitude?.let { put("longitude", it) }
        locationText?.let { put("locationText", it) }
        put("status", status.name)
        put("timestamp", timestamp)
    }.toString()

    companion object {
        fun fromJson(json: String): Signal {
            val obj = JSONObject(json)
            val tagsArray = obj.optJSONArray("tags")
            val tags = (0 until (tagsArray?.length() ?: 0)).map { tagsArray!!.getString(it) }
            return Signal(
                id           = obj.getString("id"),
                senderId     = obj.getString("senderId"),
                senderName   = obj.getString("senderName"),
                rawMessage   = obj.getString("rawMessage"),
                category     = SignalCategory.fromString(obj.getString("category")),
                priority     = Priority.fromString(obj.getString("priority")),
                tags         = tags,
                summary      = obj.getString("summary"),
                peopleCount  = if (obj.has("peopleCount")) obj.getInt("peopleCount") else null,
                latitude     = if (obj.has("latitude")) obj.getDouble("latitude") else null,
                longitude    = if (obj.has("longitude")) obj.getDouble("longitude") else null,
                locationText = if (obj.has("locationText")) obj.getString("locationText") else null,
                status       = SignalStatus.valueOf(obj.optString("status", "PENDING")),
                timestamp    = obj.getLong("timestamp")
            )
        }
    }
}
