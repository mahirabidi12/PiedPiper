package com.disastermesh.app.db.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.disastermesh.app.model.*

@Entity(
    tableName = "signals",
    indices = [
        Index("status"),
        Index("category"),
        Index("updated_at"),
        Index("volunteer_ids")
    ]
)
data class SignalEntity(
    @PrimaryKey
    val id: String,

    @ColumnInfo(name = "sender_node_id")  val senderNodeId: String,
    @ColumnInfo(name = "sender_name")     val senderName: String,
    @ColumnInfo(name = "sender_role")     val senderRole: String,

    val category: String,
    val priority: String,
    val message: String,

    @ColumnInfo(name = "people_count")    val peopleCount: Int?,
    val latitude: Double?,
    val longitude: Double?,
    @ColumnInfo(name = "manual_location") val manualLocation: String?,

    val status: String,
    val ttl: Int = 8,
    @ColumnInfo(name = "hop_count")       val hopCount: Int = 0,

    @ColumnInfo(name = "created_at")      val createdAt: Long,
    @ColumnInfo(name = "updated_at")      val updatedAt: Long,

    @ColumnInfo(name = "ai_summary")              val aiSummary: String? = null,
    @ColumnInfo(name = "ai_classified")           val aiClassified: Boolean = false,

    // Legacy single-volunteer fields — kept for backward compat; set to first entry in volunteerIds.
    @ColumnInfo(name = "assigned_volunteer_id")   val assignedVolunteerId: String? = null,
    @ColumnInfo(name = "assigned_volunteer_name") val assignedVolunteerName: String? = null,

    @ColumnInfo(name = "inventory_allocated")     val inventoryAllocated: String? = null,

    // v6 additions
    @ColumnInfo(name = "instructions")   val instructions: String? = null,
    @ColumnInfo(name = "volunteer_ids")  val volunteerIds: String? = null,
    @ColumnInfo(name = "volunteer_names") val volunteerNames: String? = null
) {
    fun toDomain() = Signal(
        id                    = id,
        senderNodeId          = senderNodeId,
        senderName            = senderName,
        senderRole            = Role.fromString(senderRole),
        category              = SignalCategory.fromString(category),
        priority              = SignalPriority.fromString(priority),
        message               = message,
        peopleCount           = peopleCount,
        latitude              = latitude,
        longitude             = longitude,
        manualLocation        = manualLocation,
        status                = SignalStatus.fromString(status),
        ttl                   = ttl,
        hopCount              = hopCount,
        createdAt             = createdAt,
        updatedAt             = updatedAt,
        aiSummary             = aiSummary,
        aiClassified          = aiClassified,
        assignedVolunteerId   = assignedVolunteerId,
        assignedVolunteerName = assignedVolunteerName,
        inventoryAllocated    = inventoryAllocated,
        instructions          = instructions,
        volunteerIds          = volunteerIds,
        volunteerNames        = volunteerNames
    )

    companion object {
        fun fromDomain(s: Signal) = SignalEntity(
            id                    = s.id,
            senderNodeId          = s.senderNodeId,
            senderName            = s.senderName,
            senderRole            = s.senderRole.name,
            category              = s.category.name,
            priority              = s.priority.name,
            message               = s.message,
            peopleCount           = s.peopleCount,
            latitude              = s.latitude,
            longitude             = s.longitude,
            manualLocation        = s.manualLocation,
            status                = s.status.name,
            ttl                   = s.ttl,
            hopCount              = s.hopCount,
            createdAt             = s.createdAt,
            updatedAt             = s.updatedAt,
            aiSummary             = s.aiSummary,
            aiClassified          = s.aiClassified,
            assignedVolunteerId   = s.assignedVolunteerId,
            assignedVolunteerName = s.assignedVolunteerName,
            inventoryAllocated    = s.inventoryAllocated,
            instructions          = s.instructions,
            volunteerIds          = s.volunteerIds,
            volunteerNames        = s.volunteerNames
        )
    }
}
