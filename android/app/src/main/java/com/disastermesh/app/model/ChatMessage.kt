package com.disastermesh.app.model

/** A mesh-propagated chat message belonging to a room. */
data class ChatMessage(
    val id: String,
    val roomId: String,             // "room-all" | "room-vol" | "room-auth"
    val senderNodeId: String,
    val senderName: String,
    val senderRole: Role,
    val text: String,
    val ttl: Int = 8,
    val hopCount: Int = 0,
    val createdAt: Long
)

/** Static room definitions — access is enforced in UI, not in the mesh. */
object ChatRooms {
    data class RoomDef(
        val id: String,
        val name: String,
        val description: String,
        val accessRoles: Set<Role>
    )

    val ALL = listOf(
        RoomDef("room-all", "All Hands",     "All roles · open channel",        Role.entries.toSet()),
        RoomDef("room-vol", "Volunteer Ops", "Volunteers + Authorities only",   setOf(Role.VOLUNTEER, Role.AUTHORITY)),
        RoomDef("room-auth","Command",        "Authorities only",               setOf(Role.AUTHORITY))
    )

    fun roomsForRole(role: Role) = ALL.filter { role in it.accessRoles }
}
