package com.disastermesh.app.mesh

import android.app.Application
import android.util.Log
import com.disastermesh.app.ai.SignalProcessor
import com.disastermesh.app.db.AppDatabase
import com.disastermesh.app.db.entities.*
import com.disastermesh.app.model.*
import java.util.UUID
import kotlinx.coroutines.*
import org.json.JSONObject

/**
 * Bounded gossip router — sits between [MeshManager] (raw bytes) and the app.
 *
 * On every received packet:
 *  1. Drop if already in seen_packets  → dedup
 *  2. Drop if hopCount >= ttl          → TTL expired
 *  3. Mark seen, persist payload (last-write-wins), notify UI
 *  4. Increment hopCount, relay to all peers except sender
 *
 * On new peer connect:
 *  - Send store-and-forward: all un-expired records from DB (peer's dedup drops dupes)
 *
 * Default TTL = 8 hops.
 */
class GossipRouter(
    private val db: AppDatabase,
    private val meshManager: MeshManager,
    private val localNodeId: String,
    private val localName: String,
    private val localRole: String,
    private val scope: CoroutineScope,
    private val application: Application,
    private val onSignalReceived: (Signal) -> Unit,
    private val onChatReceived: (ChatMessage) -> Unit,
    private val onPeerUpdated: (Peer) -> Unit,
    private val onCriticalPoiUpdated: (CriticalPoiEntity) -> Unit,
    private val onTicketAssigned: ((Signal) -> Unit)? = null,
    private val onPacketCommitted: (() -> Unit)? = null
) : MeshManager.MeshCallbacks {

    companion object {
        private const val TAG              = "GossipRouter"
        private const val DEFAULT_TTL      = 8
        private const val SEEN_PRUNE_MS    = 24 * 60 * 60 * 1000L
        private const val SYNC_WINDOW_MS   = 6 * 60 * 60 * 1000L
        private const val MAX_SYNC_SIGNALS = 60
        private const val SYNC_DELAY_MS    = 20L
    }

    // ── MeshManager.MeshCallbacks ─────────────────────────────────────────────

    override fun onRawPacketReceived(fromEndpointId: String, bytes: ByteArray) {
        scope.launch(Dispatchers.IO) {
            val json = String(bytes, Charsets.UTF_8)
            try {
                val packet = MeshPacket.fromJson(json)
                routeIncoming(packet, fromEndpointId)
            } catch (e: Exception) {
                Log.e(TAG, "Malformed packet from $fromEndpointId: ${e.message}")
            }
        }
    }

    override fun onPeerConnected(endpointId: String, endpointName: String) {
        scope.launch(Dispatchers.IO) {
            Log.d(TAG, "Peer connected: $endpointId ($endpointName)")
            sendHello(endpointId)
            sendInventorySync(endpointId)
            sendStoreAndForward(endpointId)
            flushQueuedSignals()
        }
    }

    override fun onPeerDisconnected(endpointId: String) {
        scope.launch(Dispatchers.IO) {
            Log.d(TAG, "Peer disconnected: $endpointId")
            db.peerDao().markLost(endpointId)
            db.syncLogDao().insert(SyncLogEntity(
                message   = "Peer disconnected: $endpointId",
                createdAt = System.currentTimeMillis()
            ))
        }
    }

    // ── Core routing ──────────────────────────────────────────────────────────

    private suspend fun routeIncoming(packet: MeshPacket, fromEndpointId: String) {
        if (db.seenPacketDao().isSeen(packet.id)) {
            Log.d(TAG, "Duplicate dropped: ${packet.id}")
            return
        }
        if (packet.hopCount >= packet.ttl) {
            Log.d(TAG, "TTL expired: ${packet.id} hop=${packet.hopCount} ttl=${packet.ttl}")
            return
        }

        db.seenPacketDao().markSeen(
            SeenPacketEntity(packet.id, packet.type.name, packet.originNodeId, System.currentTimeMillis())
        )

        refreshPeerPresence(packet, fromEndpointId)
        persistAndNotify(packet, fromEndpointId)
        withContext(Dispatchers.Main) { onPacketCommitted?.invoke() }

        val relay = packet.copy(hopCount = packet.hopCount + 1)
        val bytes = relay.toJson().toByteArray(Charsets.UTF_8)
        meshManager.broadcast(bytes, excludeEndpointId = fromEndpointId)

        db.seenPacketDao().pruneOlderThan(System.currentTimeMillis() - SEEN_PRUNE_MS)
    }

    private suspend fun refreshPeerPresence(packet: MeshPacket, fromEndpointId: String) {
        if (packet.originNodeId == localNodeId) return
        val now = System.currentTimeMillis()
        val existing = db.peerDao().getByNodeId(packet.originNodeId)
        val peer = PeerEntity(
            nodeId = packet.originNodeId,
            name = packet.originName,
            role = packet.originRole,
            endpointId = fromEndpointId,
            connectionState = "CONNECTED",
            firstSeen = existing?.firstSeen ?: now,
            lastSeen = now
        )
        db.peerDao().upsert(peer)
        withContext(Dispatchers.Main) {
            onPeerUpdated(peer.toDomain())
        }
    }

    private suspend fun persistAndNotify(packet: MeshPacket, fromEndpointId: String) {
        when (packet.type) {
            MeshPacket.PacketType.HELLO             -> handleHello(packet, fromEndpointId)
            MeshPacket.PacketType.SIGNAL            -> handleSignal(packet)
            MeshPacket.PacketType.SIGNAL_UPDATE     -> handleSignalUpdate(packet)
            MeshPacket.PacketType.TICKET_ASSIGNMENT -> handleTicketAssignment(packet)
            MeshPacket.PacketType.CHAT              -> handleChat(packet)
            MeshPacket.PacketType.DM                -> handleDm(packet)
            MeshPacket.PacketType.INVENTORY_UPDATE  -> handleInventoryUpdate(packet)
            MeshPacket.PacketType.INVENTORY_SYNC    -> handleInventorySync(packet)
            MeshPacket.PacketType.CRITICAL_POI_UPDATE -> handleCriticalPoiUpdate(packet)
            MeshPacket.PacketType.SAFE_ZONE_UPDATE  -> handleSafeZoneUpdate(packet)
        }
    }

    // ── Packet handlers ───────────────────────────────────────────────────────

    private suspend fun handleHello(packet: MeshPacket, fromEndpointId: String) {
        val now = System.currentTimeMillis()
        val existing = db.peerDao().getByNodeId(packet.originNodeId)
        val peer = PeerEntity(
            nodeId          = packet.originNodeId,
            name            = packet.originName,
            role            = packet.originRole,
            endpointId      = fromEndpointId,
            connectionState = "CONNECTED",
            firstSeen       = existing?.firstSeen ?: now,
            lastSeen        = now
        )
        db.peerDao().upsert(peer)
        withContext(Dispatchers.Main) {
            onPeerUpdated(peer.toDomain())
        }
        db.syncLogDao().insert(SyncLogEntity(
            message   = "HELLO from ${packet.originName} [${packet.originRole}]",
            createdAt = now
        ))
    }

    private suspend fun handleSignal(packet: MeshPacket) {
        val p = JSONObject(packet.payload)
        val now = System.currentTimeMillis()
        val entity = SignalEntity(
            id               = p.getString("signalId"),
            senderNodeId     = packet.originNodeId,
            senderName       = packet.originName,
            senderRole       = packet.originRole,
            category         = p.optString("category", "INFO"),
            priority         = p.optString("priority", "NORMAL"),
            message          = p.getString("message"),
            peopleCount      = if (p.has("peopleCount")) p.getInt("peopleCount") else null,
            latitude         = if (p.has("latitude")) p.getDouble("latitude") else null,
            longitude        = if (p.has("longitude")) p.getDouble("longitude") else null,
            manualLocation   = null,
            status           = p.optString("status", "NEW"),
            ttl              = packet.ttl,
            hopCount         = packet.hopCount,
            createdAt        = packet.sentAt,
            updatedAt        = now,
            instructions     = p.optString("instructions", null),
            volunteerIds     = p.optString("volunteerIds", null),
            volunteerNames   = p.optString("volunteerNames", null),
            inventoryAllocated = p.optString("inventoryAllocated", null)
        )
        db.signalDao().upsert(entity)
        withContext(Dispatchers.Main) {
            onSignalReceived(entity.toDomain())
        }
        db.syncLogDao().insert(SyncLogEntity(
            message   = "Signal [${entity.category}] from ${packet.originName}",
            createdAt = now
        ))
        SignalProcessor.enqueue(application, entity.id)
    }

    private suspend fun handleSignalUpdate(packet: MeshPacket) {
        val p         = JSONObject(packet.payload)
        val signalId  = p.getString("signalId")
        val status    = p.getString("status")
        val updatedAt = p.optLong("updatedAt", System.currentTimeMillis())

        val existing = db.signalDao().getById(signalId)
        if (existing != null && updatedAt < existing.updatedAt) {
            Log.d(TAG, "SIGNAL_UPDATE stale — dropping (incoming=$updatedAt existing=${existing.updatedAt})")
            return
        }

        db.signalDao().updateStatus(signalId, status, updatedAt)

        val actorNodeId = p.optString("actorNodeId", packet.originNodeId)
        db.auditLogDao().insert(
            AuditLogEntity(
                id          = packet.id,
                entityType  = "TICKET",
                entityId    = signalId,
                entityLabel = "Signal $signalId",
                action      = "STATUS_CHANGE",
                actorNodeId = actorNodeId,
                actorName   = packet.originName,
                detail      = "Status → $status",
                createdAt   = updatedAt
            )
        )
        db.syncLogDao().insert(SyncLogEntity(
            message   = "Ticket [$status] by ${packet.originName}",
            createdAt = updatedAt
        ))
    }

    private suspend fun handleTicketAssignment(packet: MeshPacket) {
        val p          = JSONObject(packet.payload)
        val signalId   = p.getString("signalId")
        val updatedAt  = p.optLong("updatedAt", System.currentTimeMillis())

        val existing = db.signalDao().getById(signalId)
        if (existing != null && updatedAt < existing.updatedAt) {
            Log.d(TAG, "TICKET_ASSIGNMENT stale — dropping")
            return
        }

        val volunteerIdsJson    = p.optString("volunteerIds", "[]")
        val volunteerNamesJson  = p.optString("volunteerNames", "[]")
        val primaryVolunteerId  = p.optString("primaryVolunteerId", "")
        val primaryVolunteerName = p.optString("primaryVolunteerName", "")
        val inventoryJson       = p.optString("inventoryJson", "{}")
        val instructions        = p.optString("instructions", "")
        val status              = p.optString("status", "ASSIGNED")

        db.signalDao().updateAssignment(
            id                   = signalId,
            primaryVolunteerId   = primaryVolunteerId,
            primaryVolunteerName = primaryVolunteerName,
            volunteerIdsJson     = volunteerIdsJson,
            volunteerNamesJson   = volunteerNamesJson,
            inventoryJson        = inventoryJson,
            instructions         = instructions,
            status               = status,
            now                  = updatedAt
        )

        db.auditLogDao().insert(
            AuditLogEntity(
                id          = packet.id,
                entityType  = "TICKET",
                entityId    = signalId,
                entityLabel = "Signal $signalId",
                action      = "ASSIGNED",
                actorNodeId = packet.originNodeId,
                actorName   = packet.originName,
                detail      = "Assigned to $volunteerNamesJson with inventory $inventoryJson",
                createdAt   = updatedAt
            )
        )
        db.syncLogDao().insert(SyncLogEntity(
            message   = "Ticket assigned by ${packet.originName}",
            createdAt = updatedAt
        ))

        // Notify this volunteer if they're one of the assignees
        val updated = db.signalDao().getById(signalId)
        if (updated != null && volunteerIdsJson.contains("\"$localNodeId\"")) {
            withContext(Dispatchers.Main) {
                onTicketAssigned?.invoke(updated.toDomain())
            }
        }
    }

    private suspend fun handleChat(packet: MeshPacket) {
        val p      = JSONObject(packet.payload)
        val roomId = p.getString("roomId")
        val text   = p.getString("text")
        val sender = packet.originNodeId

        if (db.chatMessageDao().existsByContent(sender, roomId, text)) {
            Log.d(TAG, "Content duplicate dropped: from=$sender room=$roomId")
            return
        }

        val entity = ChatMessageEntity(
            id           = packet.id,
            roomId       = roomId,
            senderNodeId = sender,
            senderName   = packet.originName,
            senderRole   = packet.originRole,
            text         = text,
            ttl          = packet.ttl,
            hopCount     = packet.hopCount,
            createdAt    = packet.sentAt
        )
        val isNew = db.chatMessageDao().insertIfNew(entity)
        if (isNew != -1L) {
            withContext(Dispatchers.Main) {
                onChatReceived(entity.toDomain())
            }
        }
    }

    private suspend fun handleDm(packet: MeshPacket) {
        val p = JSONObject(packet.payload)
        val recipientNodeId = p.getString("recipientNodeId")
        if (recipientNodeId != localNodeId && packet.originNodeId != localNodeId) return
        val threadId = dmThreadId(packet.originNodeId, recipientNodeId)
        val entity = DirectMessageEntity(
            id              = packet.id,
            threadId        = threadId,
            senderNodeId    = packet.originNodeId,
            senderName      = packet.originName,
            recipientNodeId = recipientNodeId,
            text            = p.getString("text"),
            createdAt       = packet.sentAt
        )
        db.directMessageDao().insertIfNew(entity)
    }

    private suspend fun handleCriticalPoiUpdate(packet: MeshPacket) {
        val p = JSONObject(packet.payload)
        val entity = CriticalPoiEntity(
            id = p.getString("poiId"),
            name = p.optString("name", "Unnamed"),
            amenityType = p.getString("amenityType"),
            latitude = p.getDouble("latitude"),
            longitude = p.getDouble("longitude"),
            isVerified = p.optBoolean("isVerified", false),
            operationalStatus = p.optString("status", "OPERATIONAL"),
            updatedAt = p.optLong("updatedAt", packet.sentAt)
        )
        db.criticalPoiDao().upsertIfNewer(entity)
        db.syncLogDao().insert(
            SyncLogEntity(
                message = "Critical POI [${entity.operationalStatus}] ${entity.name} by ${packet.originName}",
                createdAt = System.currentTimeMillis()
            )
        )
        withContext(Dispatchers.Main) {
            onCriticalPoiUpdated(entity)
        }
    }

    private suspend fun handleSafeZoneUpdate(packet: MeshPacket) {
        val p = JSONObject(packet.payload)
        val entity = SafeZoneEntity(
            id = p.getString("zoneId"),
            name = p.optString("name", "Safe Zone"),
            latitude = p.getDouble("latitude"),
            longitude = p.getDouble("longitude"),
            radiusMeters = p.optInt("radiusMeters", 150),
            createdAt = p.optLong("createdAt", packet.sentAt)
        )
        db.safeZoneDao().upsertIfNewer(entity)
        db.syncLogDao().insert(
            SyncLogEntity(
                message = "Safe Zone synced: ${entity.name} (${entity.radiusMeters}m)",
                createdAt = System.currentTimeMillis()
            )
        )
    }

    // ── Outgoing helpers ──────────────────────────────────────────────────────

    private fun buildPacket(type: MeshPacket.PacketType, payload: String) = MeshPacket(
        id           = UUID.randomUUID().toString(),
        type         = type,
        ttl          = DEFAULT_TTL,
        hopCount     = 0,
        originNodeId = localNodeId,
        originRole   = localRole,
        originName   = localName,
        sentAt       = System.currentTimeMillis(),
        payload      = payload
    )

    private fun buildCriticalPoiPacket(
        poiId: String,
        name: String,
        amenityType: String,
        latitude: Double,
        longitude: Double,
        status: String,
        isVerified: Boolean,
        updatedAt: Long
    ) = MeshPacket(
        id = "poi-$poiId-$updatedAt",
        type = MeshPacket.PacketType.CRITICAL_POI_UPDATE,
        ttl = DEFAULT_TTL,
        hopCount = 0,
        originNodeId = localNodeId,
        originRole = localRole,
        originName = localName,
        sentAt = updatedAt,
        payload = MeshPacket.criticalPoiUpdatePayload(
            poiId = poiId,
            name = name,
            amenityType = amenityType,
            latitude = latitude,
            longitude = longitude,
            status = status,
            isVerified = isVerified,
            updatedAt = updatedAt
        )
    )

    /** Broadcast a CHAT message and also persist locally. */
    fun sendChat(roomId: String, text: String) {
        val packet = buildPacket(MeshPacket.PacketType.CHAT, MeshPacket.chatPayload(roomId, text))
        scope.launch(Dispatchers.IO) {
            val entity = ChatMessageEntity(
                id           = packet.id,
                roomId       = roomId,
                senderNodeId = localNodeId,
                senderName   = localName,
                senderRole   = localRole,
                text         = text,
                ttl          = packet.ttl,
                hopCount     = 0,
                createdAt    = packet.sentAt
            )
            db.chatMessageDao().insertIfNew(entity)
            db.seenPacketDao().markSeen(
                SeenPacketEntity(packet.id, packet.type.name, localNodeId, packet.sentAt)
            )
            meshManager.broadcast(packet.toJson().toByteArray(Charsets.UTF_8))
            Log.d(TAG, "Sent CHAT → $roomId")
        }
    }

    fun sendSignal(signal: Signal) {
        val packet = buildPacket(
            MeshPacket.PacketType.SIGNAL,
            MeshPacket.signalPayload(
                signalId           = signal.id,
                category           = signal.category.name,
                priority           = signal.priority.name,
                message            = signal.message,
                peopleCount        = signal.peopleCount,
                latitude           = signal.latitude,
                longitude          = signal.longitude,
                status             = signal.status.name,
                instructions       = signal.instructions,
                volunteerIds       = signal.volunteerIds,
                volunteerNames     = signal.volunteerNames,
                inventoryAllocated = signal.inventoryAllocated
            )
        )
        scope.launch(Dispatchers.IO) {
            val entity = SignalEntity.fromDomain(signal)
            db.signalDao().upsert(entity)
            db.seenPacketDao().markSeen(
                SeenPacketEntity(packet.id, packet.type.name, localNodeId, packet.sentAt)
            )
            db.syncLogDao().insert(SyncLogEntity(
                message   = "Signal sent [${signal.category.name}]",
                createdAt = packet.sentAt
            ))
            meshManager.broadcast(packet.toJson().toByteArray(Charsets.UTF_8))
            Log.d(TAG, "Sent SIGNAL: ${signal.id}")
        }
    }

    /** Simple status update (accept / reject / in-progress / resolve / cancel). */
    fun sendSignalUpdate(signalId: String, newStatus: String) {
        val now    = System.currentTimeMillis()
        val packet = buildPacket(
            MeshPacket.PacketType.SIGNAL_UPDATE,
            MeshPacket.signalUpdatePayload(signalId, newStatus, now, localNodeId)
        )
        scope.launch(Dispatchers.IO) {
            db.signalDao().updateStatus(signalId, newStatus, now)
            db.seenPacketDao().markSeen(
                SeenPacketEntity(packet.id, packet.type.name, localNodeId, packet.sentAt)
            )
            db.auditLogDao().insert(
                AuditLogEntity(
                    id          = packet.id,
                    entityType  = "TICKET",
                    entityId    = signalId,
                    entityLabel = "Signal $signalId",
                    action      = "STATUS_CHANGE",
                    actorNodeId = localNodeId,
                    actorName   = localName,
                    detail      = "Status → $newStatus",
                    createdAt   = now
                )
            )
            db.syncLogDao().insert(SyncLogEntity(
                message   = "Ticket [$newStatus] sent",
                createdAt = now
            ))
            meshManager.broadcast(packet.toJson().toByteArray(Charsets.UTF_8))
            Log.d(TAG, "Sent SIGNAL_UPDATE: $signalId → $newStatus")
        }
    }

    fun sendCriticalPoiUpdate(
        poiId: String,
        name: String,
        amenityType: String,
        latitude: Double,
        longitude: Double,
        status: String,
        isVerified: Boolean
    ) {
        val now = System.currentTimeMillis()
        val packet = buildCriticalPoiPacket(
            poiId = poiId,
            name = name,
            amenityType = amenityType,
            latitude = latitude,
            longitude = longitude,
            status = status,
            isVerified = isVerified,
            updatedAt = now
        )
        val entity = CriticalPoiEntity(
            id = poiId,
            name = name,
            amenityType = amenityType,
            latitude = latitude,
            longitude = longitude,
            isVerified = isVerified,
            operationalStatus = status,
            updatedAt = now
        )

        scope.launch(Dispatchers.IO) {
            db.criticalPoiDao().upsertIfNewer(entity)
            db.seenPacketDao().markSeen(
                SeenPacketEntity(packet.id, packet.type.name, localNodeId, packet.sentAt)
            )
            db.syncLogDao().insert(
                SyncLogEntity(
                    message = "Critical POI update sent: ${entity.name} -> ${entity.operationalStatus}",
                    createdAt = now
                )
            )
            withContext(Dispatchers.Main) {
                onCriticalPoiUpdated(entity)
            }
            meshManager.broadcast(packet.toJson().toByteArray(Charsets.UTF_8))
            Log.d(TAG, "Sent CRITICAL_POI_UPDATE: $poiId -> $status")
        }
    }

    fun sendSafeZoneUpdate(
        zoneId: String,
        name: String,
        latitude: Double,
        longitude: Double,
        radiusMeters: Int,
        createdAt: Long = System.currentTimeMillis()
    ) {
        val packet = MeshPacket(
            id = "safezone-$zoneId-$createdAt",
            type = MeshPacket.PacketType.SAFE_ZONE_UPDATE,
            ttl = DEFAULT_TTL,
            hopCount = 0,
            originNodeId = localNodeId,
            originRole = localRole,
            originName = localName,
            sentAt = createdAt,
            payload = MeshPacket.safeZoneUpdatePayload(
                zoneId = zoneId,
                name = name,
                latitude = latitude,
                longitude = longitude,
                radiusMeters = radiusMeters,
                createdAt = createdAt
            )
        )
        val zone = SafeZoneEntity(
            id = zoneId,
            name = name,
            latitude = latitude,
            longitude = longitude,
            radiusMeters = radiusMeters,
            createdAt = createdAt
        )

        scope.launch(Dispatchers.IO) {
            db.safeZoneDao().upsertIfNewer(zone)
            db.seenPacketDao().markSeen(
                SeenPacketEntity(packet.id, packet.type.name, localNodeId, packet.sentAt)
            )
            db.syncLogDao().insert(
                SyncLogEntity(
                    message = "Safe Zone update sent: $name (${radiusMeters}m)",
                    createdAt = createdAt
                )
            )
            meshManager.broadcast(packet.toJson().toByteArray(Charsets.UTF_8))
            Log.d(TAG, "Sent SAFE_ZONE_UPDATE: $zoneId")
        }
    }

    /**
     * Full ticket assignment — broadcast by authority when assigning volunteers.
     * Receivers update the complete assignment state (volunteers, inventory, instructions).
     */
    fun sendTicketAssignment(
        signalId: String,
        volunteerIdsJson: String,
        volunteerNamesJson: String,
        primaryVolunteerId: String,
        primaryVolunteerName: String,
        inventoryJson: String,
        instructions: String,
        status: String
    ) {
        val now    = System.currentTimeMillis()
        val packet = buildPacket(
            MeshPacket.PacketType.TICKET_ASSIGNMENT,
            MeshPacket.ticketAssignmentPayload(
                signalId             = signalId,
                volunteerIdsJson     = volunteerIdsJson,
                volunteerNamesJson   = volunteerNamesJson,
                primaryVolunteerId   = primaryVolunteerId,
                primaryVolunteerName = primaryVolunteerName,
                inventoryJson        = inventoryJson,
                instructions         = instructions,
                status               = status,
                updatedAt            = now
            )
        )
        scope.launch(Dispatchers.IO) {
            db.signalDao().updateAssignment(
                id                   = signalId,
                primaryVolunteerId   = primaryVolunteerId,
                primaryVolunteerName = primaryVolunteerName,
                volunteerIdsJson     = volunteerIdsJson,
                volunteerNamesJson   = volunteerNamesJson,
                inventoryJson        = inventoryJson,
                instructions         = instructions,
                status               = status,
                now                  = now
            )
            db.seenPacketDao().markSeen(
                SeenPacketEntity(packet.id, packet.type.name, localNodeId, now)
            )
            db.auditLogDao().insert(
                AuditLogEntity(
                    id          = packet.id,
                    entityType  = "TICKET",
                    entityId    = signalId,
                    entityLabel = "Signal $signalId",
                    action      = "ASSIGNED",
                    actorNodeId = localNodeId,
                    actorName   = localName,
                    detail      = "Assigned to $volunteerNamesJson with inventory $inventoryJson",
                    createdAt   = now
                )
            )
            db.syncLogDao().insert(SyncLogEntity(
                message   = "Ticket assigned to $volunteerNamesJson",
                createdAt = now
            ))
            // Broadcast after all DB writes complete — remote peers call getById()
            // in handleTicketAssignment for notification; they need a consistent record.
            meshManager.broadcast(packet.toJson().toByteArray(Charsets.UTF_8))
            Log.d(TAG, "Sent TICKET_ASSIGNMENT: $signalId → volunteers=$volunteerIdsJson")
        }
    }

    // ── Store-and-forward ─────────────────────────────────────────────────────

    private suspend fun flushQueuedSignals() {
        val queued = db.signalDao().getQueuedByNode(localNodeId)
        if (queued.isEmpty()) return
        Log.d(TAG, "Flushing ${queued.size} queued signal(s) as NEW")
        queued.forEach { entity ->
            val now     = System.currentTimeMillis()
            val updated = entity.copy(status = "NEW", updatedAt = now)
            db.signalDao().upsert(updated)
            val packet = buildPacket(
                MeshPacket.PacketType.SIGNAL,
                MeshPacket.signalPayload(
                    signalId    = entity.id,
                    category    = entity.category,
                    priority    = entity.priority,
                    message     = entity.message,
                    peopleCount = entity.peopleCount,
                    latitude    = entity.latitude,
                    longitude   = entity.longitude,
                    status      = "NEW"
                )
            )
            db.seenPacketDao().markSeen(
                SeenPacketEntity(packet.id, packet.type.name, localNodeId, packet.sentAt)
            )
            meshManager.broadcast(packet.toJson().toByteArray(Charsets.UTF_8))
        }
    }

    private suspend fun sendStoreAndForward(toEndpointId: String) {
        val signals  = db.signalDao().getAllForSync()
        val sinceMs  = System.currentTimeMillis() - SYNC_WINDOW_MS
        val messages = db.chatMessageDao().getRecentForSync(sinceMs)
        val poiUpdates = db.criticalPoiDao().getUpdatedSince(sinceMs)
        val safeZones = db.safeZoneDao().getAllForSync()
        val total    = signals.size + messages.size + poiUpdates.size + safeZones.size
        if (total == 0) return

        Log.d(TAG, "Store-and-forward: $total records → $toEndpointId")

        signals.take(MAX_SYNC_SIGNALS).forEach { s ->
            if (s.status == "QUEUED") return@forEach
            val packet = MeshPacket(
                id           = s.id,
                type         = MeshPacket.PacketType.SIGNAL,
                ttl          = DEFAULT_TTL,
                hopCount     = 0,
                originNodeId = s.senderNodeId,
                originRole   = s.senderRole,
                originName   = s.senderName,
                sentAt       = s.createdAt,
                payload      = MeshPacket.signalPayload(
                    signalId           = s.id,
                    category           = s.category,
                    priority           = s.priority,
                    message            = s.message,
                    peopleCount        = s.peopleCount,
                    latitude           = s.latitude,
                    longitude          = s.longitude,
                    status             = s.status,
                    instructions       = s.instructions,
                    volunteerIds       = s.volunteerIds,
                    volunteerNames     = s.volunteerNames,
                    inventoryAllocated = s.inventoryAllocated
                )
            )
            meshManager.sendTo(toEndpointId, packet.toJson().toByteArray(Charsets.UTF_8))
            delay(SYNC_DELAY_MS)
        }

        messages.forEach { m ->
            val packet = MeshPacket(
                id           = m.id,
                type         = MeshPacket.PacketType.CHAT,
                ttl          = DEFAULT_TTL,
                hopCount     = 0,
                originNodeId = m.senderNodeId,
                originRole   = m.senderRole,
                originName   = m.senderName,
                sentAt       = m.createdAt,
                payload      = MeshPacket.chatPayload(m.roomId, m.text)
            )
            meshManager.sendTo(toEndpointId, packet.toJson().toByteArray(Charsets.UTF_8))
        }

        poiUpdates.forEach { poi ->
            val packet = MeshPacket(
                id = "poi-${poi.id}-${poi.updatedAt}",
                type = MeshPacket.PacketType.CRITICAL_POI_UPDATE,
                ttl = DEFAULT_TTL,
                hopCount = 0,
                originNodeId = localNodeId,
                originRole = localRole,
                originName = localName,
                sentAt = poi.updatedAt,
                payload = MeshPacket.criticalPoiUpdatePayload(
                    poiId = poi.id,
                    name = poi.name,
                    amenityType = poi.amenityType,
                    latitude = poi.latitude,
                    longitude = poi.longitude,
                    status = poi.operationalStatus,
                    isVerified = poi.isVerified,
                    updatedAt = poi.updatedAt
                )
            )
            meshManager.sendTo(toEndpointId, packet.toJson().toByteArray(Charsets.UTF_8))
        }

        safeZones.forEach { zone ->
            val packet = MeshPacket(
                id = "safezone-${zone.id}-${zone.createdAt}",
                type = MeshPacket.PacketType.SAFE_ZONE_UPDATE,
                ttl = DEFAULT_TTL,
                hopCount = 0,
                originNodeId = localNodeId,
                originRole = localRole,
                originName = localName,
                sentAt = zone.createdAt,
                payload = MeshPacket.safeZoneUpdatePayload(
                    zoneId = zone.id,
                    name = zone.name,
                    latitude = zone.latitude,
                    longitude = zone.longitude,
                    radiusMeters = zone.radiusMeters,
                    createdAt = zone.createdAt
                )
            )
            meshManager.sendTo(toEndpointId, packet.toJson().toByteArray(Charsets.UTF_8))
        }
    }

    private fun sendHello(toEndpointId: String) {
        val packet = buildPacket(
            MeshPacket.PacketType.HELLO,
            MeshPacket.helloPayload(hasAi = false, batteryPct = 100)
        )
        meshManager.sendTo(toEndpointId, packet.toJson().toByteArray(Charsets.UTF_8))
    }

    fun sendDm(recipientNodeId: String, text: String) {
        val packet = buildPacket(MeshPacket.PacketType.DM, MeshPacket.dmPayload(recipientNodeId, text))
        scope.launch(Dispatchers.IO) {
            val peer = db.peerDao().getByNodeId(recipientNodeId)
            val endpointId = peer?.endpointId
            if (peer == null || peer.connectionState != "CONNECTED" || endpointId == null) {
                Log.w(TAG, "DM dropped — $recipientNodeId has no direct endpoint.")
                return@launch
            }

            val threadId = dmThreadId(localNodeId, recipientNodeId)
            val entity = DirectMessageEntity(
                id              = packet.id,
                threadId        = threadId,
                senderNodeId    = localNodeId,
                senderName      = localName,
                recipientNodeId = recipientNodeId,
                text            = text,
                createdAt       = packet.sentAt
            )
            db.directMessageDao().insertIfNew(entity)
            db.seenPacketDao().markSeen(
                SeenPacketEntity(packet.id, packet.type.name, localNodeId, packet.sentAt)
            )

            meshManager.sendTo(endpointId, packet.toJson().toByteArray(Charsets.UTF_8))
            Log.d(TAG, "DM sent directly to ${peer.name} via $endpointId")
        }
    }

    // ── Inventory handlers ────────────────────────────────────────────────────

    private suspend fun handleInventoryUpdate(packet: MeshPacket) {
        val p            = JSONObject(packet.payload)
        val key          = p.getString("key")
        val incomingTs   = p.getLong("updatedAt")
        val action       = p.optString("action", "UPDATE")
        val delta        = p.optInt("delta", 0)
        val now          = System.currentTimeMillis()

        val existing = db.inventoryDao().getByKey(key)

        // ADJUST packets carry a signed delta rather than an absolute count.
        // Applying the delta on each node independently means partition/merge
        // converges correctly — seen_packets dedup guarantees each packet runs once.
        // ADD and DELETE use the absolute value from the packet (idempotent by nature).
        val newCount: Int = when (action) {
            "ADJUST" -> maxOf(0, (existing?.count ?: 0) + delta)
            "DELETE" -> 0
            else -> {
                // Last-write-wins for non-delta ops only
                if (existing != null && existing.updatedAt > incomingTs) {
                    Log.d(TAG, "Inventory LWW skip: local newer for $key")
                    return
                }
                p.getInt("count")
            }
        }

        val entity = com.disastermesh.app.db.entities.InventoryEntity(
            key           = key,
            label         = p.getString("label"),
            unit          = p.getString("unit"),
            count         = newCount,
            updatedAt     = incomingTs,
            updatedBy     = p.getString("updatedBy"),
            updatedByName = p.getString("updatedByName"),
            isDeleted     = p.optBoolean("isDeleted", false)
        )
        db.inventoryDao().upsert(entity)

        val detail = when (action) {
            "ADD"    -> "Added ${entity.count} ${entity.unit}"
            "ADJUST" -> if (delta >= 0) "+$delta ${entity.unit}" else "$delta ${entity.unit}"
            "DELETE" -> "Removed from inventory"
            else     -> "Updated"
        }
        db.auditLogDao().insert(
            AuditLogEntity(
                id          = packet.id,
                entityType  = "INVENTORY",
                entityId    = key,
                entityLabel = entity.label,
                action      = action,
                actorNodeId = packet.originNodeId,
                actorName   = packet.originName,
                detail      = detail,
                createdAt   = now
            )
        )
        db.syncLogDao().insert(
            SyncLogEntity(
                message   = "Inventory [$action] ${entity.label} by ${packet.originName}",
                createdAt = now
            )
        )
    }

    private suspend fun handleInventorySync(packet: MeshPacket) {
        val p    = JSONObject(packet.payload)
        val arr  = p.getJSONArray("items")
        val now  = System.currentTimeMillis()
        repeat(arr.length()) { i ->
            val item = arr.getJSONObject(i)
            val entity = com.disastermesh.app.db.entities.InventoryEntity(
                key           = item.getString("key"),
                label         = item.getString("label"),
                unit          = item.getString("unit"),
                count         = item.getInt("count"),
                updatedAt     = item.getLong("updatedAt"),
                updatedBy     = item.getString("updatedBy"),
                updatedByName = item.getString("updatedByName"),
                isDeleted     = item.optBoolean("isDeleted", false)
            )
            db.inventoryDao().upsertIfNewer(entity)
        }
        if (arr.length() > 0) {
            db.syncLogDao().insert(
                SyncLogEntity(
                    message   = "Inventory sync from ${packet.originName}: ${arr.length()} items",
                    createdAt = now
                )
            )
        }
    }

    fun sendInventoryUpdate(
        key: String, label: String, unit: String, count: Int,
        action: String, delta: Int, isDeleted: Boolean = false
    ) {
        val now    = System.currentTimeMillis()
        val packet = buildPacket(
            MeshPacket.PacketType.INVENTORY_UPDATE,
            MeshPacket.inventoryUpdatePayload(
                key           = key,
                label         = label,
                unit          = unit,
                count         = count,
                updatedAt     = now,
                updatedBy     = localNodeId,
                updatedByName = localName,
                action        = action,
                delta         = delta,
                isDeleted     = isDeleted
            )
        )
        scope.launch(Dispatchers.IO) {
            db.seenPacketDao().markSeen(
                SeenPacketEntity(packet.id, packet.type.name, localNodeId, now)
            )
            db.auditLogDao().insert(
                AuditLogEntity(
                    id          = packet.id,
                    entityType  = "INVENTORY",
                    entityId    = key,
                    entityLabel = label,
                    action      = action,
                    actorNodeId = localNodeId,
                    actorName   = localName,
                    detail      = when (action) {
                        "ADD"    -> "Added $count $unit"
                        "ADJUST" -> if (delta >= 0) "+$delta $unit" else "$delta $unit"
                        "DELETE" -> "Removed from inventory"
                        else     -> "Updated"
                    },
                    createdAt   = now
                )
            )
        }
        meshManager.broadcast(packet.toJson().toByteArray(Charsets.UTF_8))
        Log.d(TAG, "Sent INVENTORY_UPDATE: $key [$action] delta=$delta")
    }

    private suspend fun sendInventorySync(toEndpointId: String) {
        val items = db.inventoryDao().getAllForSync()
        if (items.isEmpty()) return
        val snapshots = items.map { e ->
            MeshPacket.InventorySnapshot(
                key           = e.key,
                label         = e.label,
                unit          = e.unit,
                count         = e.count,
                updatedAt     = e.updatedAt,
                updatedBy     = e.updatedBy,
                updatedByName = e.updatedByName,
                isDeleted     = e.isDeleted
            )
        }
        val packet = buildPacket(
            MeshPacket.PacketType.INVENTORY_SYNC,
            MeshPacket.inventorySyncPayload(snapshots)
        )
        db.seenPacketDao().markSeen(
            SeenPacketEntity(packet.id, packet.type.name, localNodeId, packet.sentAt)
        )
        meshManager.sendTo(toEndpointId, packet.toJson().toByteArray(Charsets.UTF_8))
        Log.d(TAG, "Sent INVENTORY_SYNC to $toEndpointId: ${snapshots.size} items")
    }

    private fun dmThreadId(a: String, b: String) =
        "dm-${listOf(a, b).sorted().joinToString("-")}"
}
