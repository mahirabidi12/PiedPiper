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
    /** Legacy single-volunteer field — kept for backward compat; equals volunteerIds[0]. */
    val assignedVolunteerId: String? = null,
    val assignedVolunteerName: String? = null,
    /** JSON map of inventory key → quantity reserved/deducted, e.g. {"water":2,"medkit":1} */
    val inventoryAllocated: String? = null,
    /** Task instructions for volunteers, e.g. "Bring 2 medkits, report to sector 4." */
    val instructions: String? = null,
    /** JSON array of volunteer node IDs, e.g. ["id1","id2"] */
    val volunteerIds: String? = null,
    /** JSON array of volunteer display names, e.g. ["Alice","Bob"] */
    val volunteerNames: String? = null
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
    /** Just submitted — no volunteer assigned yet. */
    NEW,
    /** Authority acknowledged, coordinator is aware. */
    ACKNOWLEDGED,
    /** Authority assigned volunteers, waiting for acceptance. */
    ASSIGNED,
    /** All assigned volunteers accepted the task. */
    ACCEPTED,
    /** Volunteer is actively working at the site. */
    IN_PROGRESS,
    /** Assignment made but inventory is insufficient. */
    WAITING_FOR_INVENTORY,
    /** Temporarily paused — awaiting resources or instruction. */
    ON_HOLD,
    /** Successfully completed. Inventory deduction is permanent. */
    RESOLVED,
    /** Volunteer declined the assignment. Inventory refunded. */
    REJECTED,
    /** Authority or civilian cancelled the ticket. Inventory refunded. */
    CANCELLED,
    /** Volunteer could not complete. Inventory refunded. */
    FAILED,
    /** Ticket expired without action. */
    EXPIRED,
    /** Created while offline — will broadcast on reconnect. */
    QUEUED;

    val isTerminal: Boolean
        get() = this in TERMINAL_SET

    /** True if reaching this status should refund reserved inventory back to stock. */
    val refundsInventory: Boolean
        get() = this in REFUND_SET

    companion object {
        private val TERMINAL_SET = setOf(RESOLVED, EXPIRED, REJECTED, CANCELLED, FAILED)
        private val REFUND_SET   = setOf(REJECTED, CANCELLED, FAILED)

        fun fromString(v: String) = entries.firstOrNull { it.name == v } ?: NEW
    }
}
