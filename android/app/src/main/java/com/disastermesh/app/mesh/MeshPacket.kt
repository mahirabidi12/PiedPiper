package com.disastermesh.app.mesh

import org.json.JSONObject

/**
 * Unified wire envelope for every message on the mesh.
 *
 * JSON layout:
 * {
 *   "id":           "<UUID>",
 *   "type":         "HELLO|SIGNAL|SIGNAL_UPDATE|CHAT|DM|INVENTORY_UPDATE|INVENTORY_SYNC|CRITICAL_POI_UPDATE|SAFE_ZONE_UPDATE",
 *   "ttl":          8,
 *   "hopCount":     0,
 *   "originNodeId": "<UUID>",
 *   "originRole":   "CIVILIAN|VOLUNTEER|AUTHORITY",
 *   "originName":   "Rahul",
 *   "sentAt":       1715600000000,
 *   "payload":      "{ ... }"   <- type-specific JSON string
 * }
 *
 * GossipRouter owns the TTL/hopCount logic; this class is pure data.
 */
data class MeshPacket(
    val id: String,
    val type: PacketType,
    val ttl: Int,
    val hopCount: Int,
    val originNodeId: String,
    val originRole: String,
    val originName: String,
    val sentAt: Long,
    val payload: String          // JSON string; structure determined by type
) {

    enum class PacketType {
        HELLO,
        SIGNAL,
        SIGNAL_UPDATE,
        CHAT,
        DM,
        INVENTORY_UPDATE,
        INVENTORY_SYNC,
        CRITICAL_POI_UPDATE,
        SAFE_ZONE_UPDATE
    }

    /** Lightweight snapshot of an inventory item for sync payloads. */
    data class InventorySnapshot(
        val key: String,
        val label: String,
        val unit: String,
        val count: Int,
        val updatedAt: Long,
        val updatedBy: String,
        val updatedByName: String,
        val isDeleted: Boolean
    )

    fun toJson(): String = JSONObject().apply {
        put("id", id)
        put("type", type.name)
        put("ttl", ttl)
        put("hopCount", hopCount)
        put("originNodeId", originNodeId)
        put("originRole", originRole)
        put("originName", originName)
        put("sentAt", sentAt)
        put("payload", payload)
    }.toString()

    companion object {
        fun fromJson(json: String): MeshPacket {
            val o = JSONObject(json)
            return MeshPacket(
                id           = o.getString("id"),
                type         = PacketType.valueOf(o.getString("type")),
                ttl          = o.optInt("ttl", 8),
                hopCount     = o.optInt("hopCount", 0),
                originNodeId = o.getString("originNodeId"),
                originRole   = o.getString("originRole"),
                originName   = o.getString("originName"),
                sentAt       = o.getLong("sentAt"),
                payload      = o.getString("payload")
            )
        }

        // ── Payload builders ───────────────────────────────────────────

        fun helloPayload(hasAi: Boolean, batteryPct: Int): String =
            JSONObject().apply {
                put("hasAi", hasAi)
                put("batteryPct", batteryPct)
            }.toString()

        fun signalPayload(
            signalId: String, category: String, priority: String,
            message: String, peopleCount: Int?, latitude: Double?,
            longitude: Double?, status: String
        ): String = JSONObject().apply {
            put("signalId", signalId)
            put("category", category)
            put("priority", priority)
            put("message", message)
            if (peopleCount != null) put("peopleCount", peopleCount)
            if (latitude != null)   put("latitude", latitude)
            if (longitude != null)  put("longitude", longitude)
            put("status", status)
        }.toString()

        fun chatPayload(roomId: String, text: String): String =
            JSONObject().apply {
                put("roomId", roomId)
                put("text", text)
            }.toString()

        fun signalUpdatePayload(signalId: String, status: String): String =
            JSONObject().apply {
                put("signalId", signalId)
                put("status", status)
            }.toString()

        fun dmPayload(recipientNodeId: String, text: String): String =
            JSONObject().apply {
                put("recipientNodeId", recipientNodeId)
                put("text", text)
            }.toString()

        fun inventoryUpdatePayload(
            key: String, label: String, unit: String, count: Int,
            updatedAt: Long, updatedBy: String, updatedByName: String,
            action: String, delta: Int, isDeleted: Boolean
        ): String = JSONObject().apply {
            put("key", key); put("label", label); put("unit", unit)
            put("count", count); put("updatedAt", updatedAt)
            put("updatedBy", updatedBy); put("updatedByName", updatedByName)
            put("action", action); put("delta", delta); put("isDeleted", isDeleted)
        }.toString()

        fun inventorySyncPayload(items: List<InventorySnapshot>): String {
            val arr = org.json.JSONArray()
            items.forEach { item ->
                arr.put(JSONObject().apply {
                    put("key", item.key); put("label", item.label); put("unit", item.unit)
                    put("count", item.count); put("updatedAt", item.updatedAt)
                    put("updatedBy", item.updatedBy); put("updatedByName", item.updatedByName)
                    put("isDeleted", item.isDeleted)
                })
            }
            return JSONObject().apply { put("items", arr) }.toString()
        }

        fun criticalPoiUpdatePayload(
            poiId: String,
            name: String,
            amenityType: String,
            latitude: Double,
            longitude: Double,
            status: String,
            isVerified: Boolean,
            updatedAt: Long
        ): String = JSONObject().apply {
            put("poiId", poiId)
            put("name", name)
            put("amenityType", amenityType)
            put("latitude", latitude)
            put("longitude", longitude)
            put("status", status)
            put("isVerified", isVerified)
            put("updatedAt", updatedAt)
        }.toString()

        fun safeZoneUpdatePayload(
            zoneId: String,
            name: String,
            latitude: Double,
            longitude: Double,
            radiusMeters: Int,
            createdAt: Long
        ): String = JSONObject().apply {
            put("zoneId", zoneId)
            put("name", name)
            put("latitude", latitude)
            put("longitude", longitude)
            put("radiusMeters", radiusMeters)
            put("createdAt", createdAt)
        }.toString()
    }
}
