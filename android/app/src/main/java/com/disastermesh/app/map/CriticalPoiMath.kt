package com.disastermesh.app.map

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

object CriticalPoiMath {
    private const val EARTH_RADIUS_METERS = 6_371_000.0

    /** Great-circle distance using the Haversine formula. */
    fun haversineMeters(
        startLat: Double,
        startLon: Double,
        endLat: Double,
        endLon: Double
    ): Double {
        val dLat = Math.toRadians(endLat - startLat)
        val dLon = Math.toRadians(endLon - startLon)
        val lat1 = Math.toRadians(startLat)
        val lat2 = Math.toRadians(endLat)

        val a = sin(dLat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(dLon / 2).pow(2)
        val c = 2 * asin(sqrt(a))
        return EARTH_RADIUS_METERS * c
    }

    fun formatDistance(meters: Double?): String {
        if (meters == null || meters.isNaN() || meters.isInfinite()) return "Unknown"
        return if (meters < 1_000.0) {
            "${meters.toInt()} m"
        } else {
            "%.2f km".format(meters / 1_000.0)
        }
    }
}
