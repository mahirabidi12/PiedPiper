package com.disastermesh.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.disastermesh.models.Priority
import com.disastermesh.models.Signal
import com.disastermesh.models.SignalCategory
import com.disastermesh.models.SignalStatus

@Entity(tableName = "signals")
data class SignalEntity(
    @PrimaryKey val id: String,
    val senderId: String,
    val senderName: String,
    val rawMessage: String,
    val category: String,
    val priority: String,
    val tags: String,           // stored as comma-separated string
    val summary: String,
    val peopleCount: Int?,
    val latitude: Double?,
    val longitude: Double?,
    val locationText: String?,
    val status: String,
    val timestamp: Long
) {
    fun toSignal() = Signal(
        id           = id,
        senderId     = senderId,
        senderName   = senderName,
        rawMessage   = rawMessage,
        category     = SignalCategory.fromString(category),
        priority     = Priority.fromString(priority),
        tags         = if (tags.isBlank()) emptyList() else tags.split(","),
        summary      = summary,
        peopleCount  = peopleCount,
        latitude     = latitude,
        longitude    = longitude,
        locationText = locationText,
        status       = SignalStatus.valueOf(status),
        timestamp    = timestamp
    )

    companion object {
        fun fromSignal(s: Signal) = SignalEntity(
            id           = s.id,
            senderId     = s.senderId,
            senderName   = s.senderName,
            rawMessage   = s.rawMessage,
            category     = s.category.name,
            priority     = s.priority.name,
            tags         = s.tags.joinToString(","),
            summary      = s.summary,
            peopleCount  = s.peopleCount,
            latitude     = s.latitude,
            longitude    = s.longitude,
            locationText = s.locationText,
            status       = s.status.name,
            timestamp    = s.timestamp
        )
    }
}
