package com.disastermesh.app.model

/** Pure domain model — no Android or Room dependencies. */
data class Signal(
    val id: String,
    val senderNodeId: String,
    val senderName: String,
    val senderRole: Role,
    val category: SignalCategory,
    val priority: SignalPriority,
    val message: String,
    val peopleCount: Int?,
    val latitude: Double?,
    val longitude: Double?,
    val manualLocation: String?,
    val status: SignalStatus,
    val ttl: Int = 8,
    val hopCount: Int = 0,
    val createdAt: Long,
    val updatedAt: Long,
    val aiSummary: String? = null,
    val aiClassified: Boolean = false,
    val assignedVolunteerId: String? = null,
    val assignedVolunteerName: String? = null,
    /** JSON map of inventory key → quantity deducted, e.g. {"water":2,"medkit":1} */
    val inventoryAllocated: String? = null
)

enum class SignalCategory(val icon: String, val label: String) {
    MEDICAL      ("✚", "MEDICAL"),
    RESCUE       ("⬆", "RESCUE"),
    RESOURCE     ("◆", "RESOURCE"),
    SAFETY       ("▲", "SAFETY"),
    INFO         ("●", "INFO"),
    UNCLASSIFIED ("?", "PROCESSING"); // pending Gemma classification

    companion object {
        fun fromString(v: String) = entries.firstOrNull { it.name == v } ?: UNCLASSIFIED
    }
}

enum class SignalPriority {
    CRITICAL, HIGH, NORMAL, LOW;

    companion object {
        fun fromString(v: String) = entries.firstOrNull { it.name == v } ?: NORMAL
    }
}

enum class SignalStatus {
    NEW, ACKNOWLEDGED, IN_PROGRESS, RESOLVED, EXPIRED,
    /** Created while mesh was offline — will broadcast on reconnect. */
    QUEUED;

    companion object {
        fun fromString(v: String) = entries.firstOrNull { it.name == v } ?: NEW
    }
}
