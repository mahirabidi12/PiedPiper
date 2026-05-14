package com.disastermesh.models

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
}
