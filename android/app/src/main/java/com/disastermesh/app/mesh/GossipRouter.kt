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
    private val onPeerUpdated: (Peer) -> Unit
) : MeshManager.MeshCallbacks {

    companion object {
        private const val TAG = "GossipRouter"
        private const val DEFAULT_TTL = 8
        // Prune seen_packets older than 24 h to prevent unbounded growth
        private const val SEEN_PRUNE_MS = 24 * 60 * 60 * 1000L
        // Store-and-forward window: only replay recent messages to reconnecting
        // peers. Stops a backlog of stale rows from flooding the mesh on every
        // reconnect cycle.
        private const val SYNC_WINDOW_MS = 6 * 60 * 60 * 1000L
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
            sendInventorySync(endpointId)   // inventory first — immediately visible on reconnect
            sendStoreAndForward(endpointId)
            flushQueuedSignals()   // promote any QUEUED → NEW and re-broadcast
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
        // 1. Dedup
        if (db.seenPacketDao().isSeen(packet.id)) {
            Log.d(TAG, "Duplicate dropped: ${packet.id}")
            return
        }
        // 2. TTL check
        if (packet.hopCount >= packet.ttl) {
            Log.d(TAG, "TTL expired: ${packet.id} hop=${packet.hopCount} ttl=${packet.ttl}")
            return
        }

        // 3. Mark seen
        db.seenPacketDao().markSeen(
            SeenPacketEntity(packet.id, packet.type.name, packet.originNodeId, System.currentTimeMillis())
        )

        // 4. Persist + notify UI
        persistAndNotify(packet, fromEndpointId)

        // 5. Relay (hop + 1)
        val relay = packet.copy(hopCount = packet.hopCount + 1)
        val bytes = relay.toJson().toByteArray(Charsets.UTF_8)
        meshManager.broadcast(bytes, excludeEndpointId = fromEndpointId)

        // Prune seen table occasionally
        db.seenPacketDao().pruneOlderThan(System.currentTimeMillis() - SEEN_PRUNE_MS)
    }

    private suspend fun persistAndNotify(packet: MeshPacket, fromEndpointId: String) {
        when (packet.type) {
            MeshPacket.PacketType.HELLO            -> handleHello(packet, fromEndpointId)
            MeshPacket.PacketType.SIGNAL           -> handleSignal(packet)
            MeshPacket.PacketType.SIGNAL_UPDATE    -> handleSignalUpdate(packet)
            MeshPacket.PacketType.CHAT             -> handleChat(packet)
            MeshPacket.PacketType.DM               -> handleDm(packet)
            MeshPacket.PacketType.INVENTORY_UPDATE -> handleInventoryUpdate(packet)
            MeshPacket.PacketType.INVENTORY_SYNC   -> handleInventorySync(packet)
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
            id             = p.getString("signalId"),
            senderNodeId   = packet.originNodeId,
            senderName     = packet.originName,
            senderRole     = packet.originRole,
            category       = p.optString("category", "INFO"),
            priority       = p.optString("priority", "NORMAL"),
            message        = p.getString("message"),
            peopleCount    = if (p.has("peopleCount")) p.getInt("peopleCount") else null,
            latitude       = if (p.has("latitude")) p.getDouble("latitude") else null,
            longitude      = if (p.has("longitude")) p.getDouble("longitude") else null,
            manualLocation = null,
            status         = p.optString("status", "NEW"),
            ttl            = packet.ttl,
            hopCount       = packet.hopCount,
            createdAt      = packet.sentAt,
            updatedAt      = now
        )
        db.signalDao().upsert(entity)
        withContext(Dispatchers.Main) {
            onSignalReceived(entity.toDomain())
        }
        db.syncLogDao().insert(SyncLogEntity(
            message   = "Signal [${entity.category}] from ${packet.originName}",
            createdAt = now
        ))
        // Enqueue for Gemma classification (no-op if model not ready yet)
        SignalProcessor.enqueue(application, entity.id)
    }

    private suspend fun handleSignalUpdate(packet: MeshPacket) {
        val p        = JSONObject(packet.payload)
        val signalId = p.getString("signalId")
        val status   = p.getString("status")
        db.signalDao().updateStatus(signalId, status, System.currentTimeMillis())
        db.syncLogDao().insert(SyncLogEntity(
            message   = "Signal [$status] updated by ${packet.originName}",
            createdAt = System.currentTimeMillis()
        ))
    }

    private suspend fun handleChat(packet: MeshPacket) {
        val p      = JSONObject(packet.payload)
        val roomId = p.getString("roomId")
        val text   = p.getString("text")
        val sender = packet.originNodeId

        // Safety net: drop content duplicates even when the packet id is new.
        // The primary dedup is packet.id at routeIncoming / DAO PrimaryKey, but
        // any path that ever issues a fresh id for an existing logical message
        // (as the old buggy store-and-forward did) would slip past id-based
        // dedup. This second check guarantees the UI never sees the same
        // (sender, room, text) tuple twice.
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
        // Only store if this device is the sender or the recipient
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

    /** Broadcast a CHAT message and also persist locally. */
    fun sendChat(roomId: String, text: String) {
        val packet = buildPacket(
            MeshPacket.PacketType.CHAT,
            MeshPacket.chatPayload(roomId, text)
        )
        scope.launch(Dispatchers.IO) {
            // Persist our own message
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
            // Broadcast AFTER markSeen — otherwise a peer can echo this packet
            // back via gossip relay before our seen_packets row exists, and we
            // would re-process and re-relay our own message.
            meshManager.broadcast(packet.toJson().toByteArray(Charsets.UTF_8))
            Log.d(TAG, "Sent CHAT → $roomId")
        }
    }

    /** Broadcast a SIGNAL and also persist locally. */
    fun sendSignal(signal: Signal) {
        val packet = buildPacket(
            MeshPacket.PacketType.SIGNAL,
            MeshPacket.signalPayload(
                signalId    = signal.id,
                category    = signal.category.name,
                priority    = signal.priority.name,
                message     = signal.message,
                peopleCount = signal.peopleCount,
                latitude    = signal.latitude,
                longitude   = signal.longitude,
                status      = signal.status.name
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

    /** Update a signal's status locally and broadcast a SIGNAL_UPDATE to all peers. */
    fun sendSignalUpdate(signalId: String, newStatus: String) {
        val packet = buildPacket(
            MeshPacket.PacketType.SIGNAL_UPDATE,
            MeshPacket.signalUpdatePayload(signalId, newStatus)
        )
        scope.launch(Dispatchers.IO) {
            db.signalDao().updateStatus(signalId, newStatus, System.currentTimeMillis())
            db.seenPacketDao().markSeen(
                SeenPacketEntity(packet.id, packet.type.name, localNodeId, packet.sentAt)
            )
            db.syncLogDao().insert(SyncLogEntity(
                message   = "Signal update [$newStatus] sent",
                createdAt = packet.sentAt
            ))
            meshManager.broadcast(packet.toJson().toByteArray(Charsets.UTF_8))
            Log.d(TAG, "Sent SIGNAL_UPDATE: $signalId → $newStatus")
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
        // Bounded window: replay only recent chat history. Sending the entire
        // un-expired backlog on every reconnect was the second source of the
        // flood — once a peer accumulates thousands of rows, every reconnect
        // re-sends all of them.
        val sinceMs  = System.currentTimeMillis() - SYNC_WINDOW_MS
        val messages = db.chatMessageDao().getRecentForSync(sinceMs)
        val total    = signals.size + messages.size
        if (total == 0) return

        Log.d(TAG, "Store-and-forward: $total records (chat window=${SYNC_WINDOW_MS / 3_600_000}h) → $toEndpointId")

        // CRITICAL: forwarded packets MUST reuse the original id / sender / sentAt.
        // buildPacket() mints a new UUID and overwrites the origin fields with the
        // local node — using it here makes every reconnection look like a brand-new
        // message to receiving peers, defeating dedup at the seen-packet table and
        // the chat-message DAO (both keyed on packet.id == message id). The result
        // was an unbounded multiplication of chat rows on every reconnect.
        signals.forEach { s ->
            // Never forward a QUEUED signal to peers — it hasn't been confirmed live yet.
            // flushQueuedSignals() handles promoting and broadcasting those separately.
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
                    signalId    = s.id,
                    category    = s.category,
                    priority    = s.priority,
                    message     = s.message,
                    peopleCount = s.peopleCount,
                    latitude    = s.latitude,
                    longitude   = s.longitude,
                    status      = s.status
                )
            )
            meshManager.sendTo(toEndpointId, packet.toJson().toByteArray(Charsets.UTF_8))
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
    }

    private fun sendHello(toEndpointId: String) {
        val packet = buildPacket(
            MeshPacket.PacketType.HELLO,
            MeshPacket.helloPayload(hasAi = false, batteryPct = 100)
        )
        meshManager.sendTo(toEndpointId, packet.toJson().toByteArray(Charsets.UTF_8))
    }

    fun sendDm(recipientNodeId: String, text: String) {
        val packet = buildPacket(
            MeshPacket.PacketType.DM,
            MeshPacket.dmPayload(recipientNodeId, text)
        )
        scope.launch(Dispatchers.IO) {
            // Resolve a direct, 1-hop endpoint for the recipient.
            // Multi-hop DMs are not supported: broadcasting plaintext DM content
            // to every mesh peer violates message confidentiality.
            val peer = db.peerDao().getByNodeId(recipientNodeId)
            val endpointId = peer?.endpointId
            if (peer == null || peer.connectionState != "CONNECTED" || endpointId == null) {
                Log.w(TAG, "DM dropped — $recipientNodeId has no direct endpoint. Multi-hop DMs are not supported.")
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
        val p      = JSONObject(packet.payload)
        val key    = p.getString("key")
        val now    = System.currentTimeMillis()
        val entity = com.disastermesh.app.db.entities.InventoryEntity(
            key           = key,
            label         = p.getString("label"),
            unit          = p.getString("unit"),
            count         = p.getInt("count"),
            updatedAt     = p.getLong("updatedAt"),
            updatedBy     = p.getString("updatedBy"),
            updatedByName = p.getString("updatedByName"),
            isDeleted     = p.optBoolean("isDeleted", false)
        )
        db.inventoryDao().upsertIfNewer(entity)

        val action = p.optString("action", "UPDATE")
        val delta  = p.optInt("delta", 0)
        val detail = when (action) {
            "ADD"    -> "Added ${entity.count} ${entity.unit}"
            "ADJUST" -> if (delta >= 0) "+$delta ${entity.unit}" else "$delta ${entity.unit}"
            "DELETE" -> "Removed from inventory"
            else     -> "Updated"
        }
        db.auditLogDao().insert(
            com.disastermesh.app.db.entities.AuditLogEntity(
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
            com.disastermesh.app.db.entities.SyncLogEntity(
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
                com.disastermesh.app.db.entities.SyncLogEntity(
                    message   = "Inventory sync from ${packet.originName}: ${arr.length()} items",
                    createdAt = now
                )
            )
        }
    }

    // ── Inventory outgoing ────────────────────────────────────────────────────

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
                com.disastermesh.app.db.entities.SeenPacketEntity(packet.id, packet.type.name, localNodeId, now)
            )
            db.auditLogDao().insert(
                com.disastermesh.app.db.entities.AuditLogEntity(
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
            com.disastermesh.app.db.entities.SeenPacketEntity(packet.id, packet.type.name, localNodeId, packet.sentAt)
        )
        meshManager.sendTo(toEndpointId, packet.toJson().toByteArray(Charsets.UTF_8))
        Log.d(TAG, "Sent INVENTORY_SYNC to $toEndpointId: ${snapshots.size} items")
    }

    private fun dmThreadId(a: String, b: String) =
        "dm-${listOf(a, b).sorted().joinToString("-")}"
}
