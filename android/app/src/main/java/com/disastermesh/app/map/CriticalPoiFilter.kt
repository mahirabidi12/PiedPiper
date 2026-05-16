package com.disastermesh.app.map

import com.disastermesh.app.db.entities.CriticalPoiAmenity
import com.disastermesh.app.db.entities.CriticalPoiEntity

/**
 * Strict offline ingestion filter: accepts only life-safety amenities.
 */
object CriticalPoiFilter {

    fun normalizeAmenity(rawAmenity: String?): String? {
        if (rawAmenity.isNullOrBlank()) return null
        val normalized = rawAmenity.trim().lowercase()
        return if (normalized in CriticalPoiAmenity.allowed) normalized else null
    }

    fun isCriticalAmenity(rawAmenity: String?): Boolean =
        normalizeAmenity(rawAmenity) != null

    fun toEntityIfCritical(
        osmId: String,
        name: String?,
        amenityTag: String?,
        latitude: Double,
        longitude: Double,
        updatedAt: Long = System.currentTimeMillis()
    ): CriticalPoiEntity? {
        val amenity = normalizeAmenity(amenityTag) ?: return null
        val safeName = name?.trim().orEmpty().ifBlank {
            amenity.replace('_', ' ').replaceFirstChar { it.uppercase() }
        }
        return CriticalPoiEntity(
            id = osmId,
            name = safeName,
            amenityType = amenity,
            latitude = latitude,
            longitude = longitude,
            updatedAt = updatedAt
        )
    }
}
