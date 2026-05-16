package com.disastermesh.app.map

import com.disastermesh.app.model.*
import kotlin.math.*

object LocationClusterer {

    private const val CLUSTER_RADIUS_METRES = 500.0

    fun cluster(signals: List<Signal>): List<AreaCluster> {
        if (signals.isEmpty()) return emptyList()

        val withGps    = signals.filter { it.latitude != null && it.longitude != null }
        val withoutGps = signals.filter { it.latitude == null }

        val clusters = mutableListOf<AreaCluster>()
        val assigned = mutableSetOf<String>()

        // GPS-based: greedy 500 m radius
        for (anchor in withGps) {
            if (anchor.id in assigned) continue
            val group = mutableListOf(anchor)
            assigned.add(anchor.id)
            for (other in withGps) {
                if (other.id in assigned) continue
                if (distanceMetres(
                        anchor.latitude!!, anchor.longitude!!,
                        other.latitude!!, other.longitude!!
                    ) <= CLUSTER_RADIUS_METRES
                ) {
                    group.add(other)
                    assigned.add(other.id)
                }
            }
            clusters.add(buildCluster(group))
        }

        // Non-GPS: group by manual location text
        withoutGps
            .groupBy { it.manualLocation?.trim()?.lowercase() ?: "" }
            .forEach { (_, group) -> clusters.add(buildCluster(group)) }

        return clusters.sortedWith(
            compareByDescending<AreaCluster> { it.criticalCount }
                .thenByDescending { it.totalCount }
        )
    }

    private fun buildCluster(signals: List<Signal>): AreaCluster {
        val lats = signals.mapNotNull { it.latitude }
        val lons = signals.mapNotNull { it.longitude }
        val centerLat = if (lats.isNotEmpty()) lats.average() else null
        val centerLon = if (lons.isNotEmpty()) lons.average() else null

        val label = signals.firstOrNull { !it.manualLocation.isNullOrBlank() }?.manualLocation
            ?: centerLat?.let { "%.4f, %.4f".format(it, centerLon) }
            ?: "Unknown Location"

        val sorted = signals.sortedWith(compareByDescending {
            when (it.priority) {
                SignalPriority.CRITICAL -> 3
                SignalPriority.HIGH     -> 2
                SignalPriority.NORMAL   -> 1
                SignalPriority.LOW      -> 0
            }
        })

        return AreaCluster(
            areaLabel     = label,
            centerLat     = centerLat,
            centerLon     = centerLon,
            signals       = sorted,
            medicalCount  = signals.count { it.category == SignalCategory.MEDICAL },
            rescueCount   = signals.count { it.category == SignalCategory.RESCUE },
            resourceCount = signals.count { it.category == SignalCategory.RESOURCE },
            safetyCount   = signals.count { it.category == SignalCategory.SAFETY },
            otherCount    = signals.count {
                it.category == SignalCategory.INFO || it.category == SignalCategory.UNCLASSIFIED
            },
            criticalCount   = signals.count { it.priority == SignalPriority.CRITICAL },
            latestTimestamp = signals.maxOf { it.createdAt }
        )
    }

    private fun distanceMetres(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R    = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a    = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return R * 2 * atan2(sqrt(a), sqrt(1 - a))
    }
}
