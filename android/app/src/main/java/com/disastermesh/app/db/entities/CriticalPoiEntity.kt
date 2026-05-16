package com.disastermesh.app.db.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Offline critical infrastructure POI row.
 *
 * Required schema columns from product spec:
 * - id, name, amenity_type, latitude, longitude, is_verified
 *
 * Additional columns keep dynamic mesh status updates local-first and cheap:
 * - operational_status, updated_at
 */
@Entity(
    tableName = "critical_pois",
    indices = [
        Index("amenity_type"),
        Index(value = ["latitude", "longitude"]),
        Index("updated_at")
    ]
)
data class CriticalPoiEntity(
    @PrimaryKey
    val id: String,

    val name: String,

    @ColumnInfo(name = "amenity_type")
    val amenityType: String,

    val latitude: Double,
    val longitude: Double,

    @ColumnInfo(name = "is_verified", defaultValue = "0")
    val isVerified: Boolean = false,

    @ColumnInfo(name = "operational_status", defaultValue = "OPERATIONAL")
    val operationalStatus: String = CriticalPoiStatus.OPERATIONAL,

    @ColumnInfo(name = "updated_at", defaultValue = "0")
    val updatedAt: Long = 0L
)

object CriticalPoiAmenity {
    const val HOSPITAL = "hospital"
    const val CLINIC = "clinic"
    const val PHARMACY = "pharmacy"
    const val BLOOD_BANK = "blood_bank"
    const val FIRE_STATION = "fire_station"
    const val POLICE = "police"

    val allowed: Set<String> = setOf(
        HOSPITAL,
        CLINIC,
        PHARMACY,
        BLOOD_BANK,
        FIRE_STATION,
        POLICE
    )
}

object CriticalPoiStatus {
    const val OPERATIONAL = "OPERATIONAL"
    const val FLOODED = "FLOODED"
    const val OUT_OF_MEDICAL_SUPPLIES = "OUT_OF_MEDICAL_SUPPLIES"
    const val TEMP_TRIAGE_CAMP = "TEMP_TRIAGE_CAMP"

    val all: List<String> = listOf(
        OPERATIONAL,
        FLOODED,
        OUT_OF_MEDICAL_SUPPLIES,
        TEMP_TRIAGE_CAMP
    )
}
