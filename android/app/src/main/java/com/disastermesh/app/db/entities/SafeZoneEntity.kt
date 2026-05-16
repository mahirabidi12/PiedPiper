package com.disastermesh.app.db.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "safe_zones",
    indices = [
        Index("created_at"),
        Index(value = ["latitude", "longitude"])
    ]
)
data class SafeZoneEntity(
    @PrimaryKey
    val id: String,

    val name: String,

    val latitude: Double,
    val longitude: Double,

    @ColumnInfo(name = "radius_meters")
    val radiusMeters: Int,

    @ColumnInfo(name = "created_at")
    val createdAt: Long
)
