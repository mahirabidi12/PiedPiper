package com.disastermesh.app.model

data class AreaCluster(
    val areaLabel: String,
    val centerLat: Double?,
    val centerLon: Double?,
    val signals: List<Signal>,
    val medicalCount: Int,
    val rescueCount: Int,
    val resourceCount: Int,
    val safetyCount: Int,
    val otherCount: Int,
    val criticalCount: Int,
    val latestTimestamp: Long
) {
    val totalCount: Int get() = signals.size
    val openCount: Int get() = signals.count {
        it.status != SignalStatus.RESOLVED && it.status != SignalStatus.EXPIRED
    }
    val affectedPeopleCount: Int get() = signals.mapNotNull { it.peopleCount }.sum()
}
