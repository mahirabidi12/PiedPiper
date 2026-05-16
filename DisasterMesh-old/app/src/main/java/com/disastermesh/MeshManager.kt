package com.disastermesh

import android.content.Context
import android.util.Log
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*

class MeshManager(
    private val context: Context,
    private val localNodeId: String,
    private val localRole: String,
    private val deviceName: String,
    private val onMessageReceived: (Message) -> Unit,
    private val onPeersChanged: (peerCount: Int, peerNames: List<String>) -> Unit
) {
    private val client = Nearby.getConnectionsClient(context)
    private val connectedEndpoints = mutableMapOf<String, String>() // endpointId -> endpointName
    private val repository = MessageRepository(context)

    companion object {
        private val SERVICE_ID = AppConstants.MESH_SERVICE_ID
        private const val TAG = "DisasterMesh"
    }

    // ── Public API ────────────────────────────────────────────────────────────

    fun start(initiateConnections: Boolean = true) {
        startAdvertising()
        if (initiateConnections) startDiscovery()
    }

    fun stop() {
        client.stopAdvertising()
        client.stopDiscovery()
        client.stopAllEndpoints()
        connectedEndpoints.clear()
        onPeersChanged(0, emptyList())
        Log.d(TAG, "Mesh stopped")
    }

    fun sendMessage(text: String, senderRole: String = Role.USER.name, targetRole: String = AppConstants.TARGET_ALL) {
        val message = Message(
            senderId   = deviceName,
            senderName = deviceName,
            senderRole = senderRole,
            text       = text,
            targetRole = targetRole
        )
        // Mark as seen so we don't echo it back to ourselves if it bounces
        if (repository.add(message)) {
            onMessageReceived(message)
            broadcast(message, excludeEndpoint = null)
            Log.d(TAG, "Sent message: ${message.id}")
        }
    }

    // ── Networking ────────────────────────────────────────────────────────────

    private fun broadcast(message: Message, excludeEndpoint: String?) {
        val targets = connectedEndpoints.keys.filter { it != excludeEndpoint }
        if (targets.isEmpty()) {
            Log.d(TAG, "No peers to broadcast to")
            return
        }
        val payload = Payload.fromBytes(message.toJson().toByteArray(Charsets.UTF_8))
        targets.forEach { endpointId ->
            client.sendPayload(endpointId, payload)
                .addOnSuccessListener {
                    Log.d(TAG, "Forwarded ${message.id} → $endpointId")
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Forward failed → $endpointId: ${e.message}")
                }
        }
    }

    private fun startAdvertising() {
        val options = AdvertisingOptions.Builder()
            .setStrategy(Strategy.P2P_CLUSTER)
            .build()

        client.startAdvertising("$localNodeId|$localRole", SERVICE_ID, connectionLifecycleCallback, options)
            .addOnSuccessListener {
                Log.d(TAG, "Advertising as: $deviceName ($localNodeId) [$localRole]")
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Advertising failed: ${e.message}")
            }
    }

    private fun startDiscovery() {
        val options = DiscoveryOptions.Builder()
            .setStrategy(Strategy.P2P_CLUSTER)
            .build()

        client.startDiscovery(SERVICE_ID, endpointDiscoveryCallback, options)
            .addOnSuccessListener {
                Log.d(TAG, "Discovery started")
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Discovery failed: ${e.message}")
            }
    }

    // ── Callbacks ─────────────────────────────────────────────────────────────

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            val parts        = info.endpointName.split("|")
            val remoteNodeId = parts.getOrElse(0) { info.endpointName }
            val remoteRole   = parts.getOrElse(1) { "" }
            Log.d(TAG, "Found peer: $endpointId ($remoteNodeId) [$remoteRole]")

            // Tiebreaker: only apply against other discoverers (not Authority).
            // Authority never calls requestConnection(), so skipping against it would
            // leave nobody initiating — they'd never connect.
            if (remoteRole != Role.AUTHORITY.name && localNodeId > remoteNodeId) {
                Log.d(TAG, "Tiebreaker: waiting for $remoteNodeId to initiate")
                return
            }
            client.requestConnection("$localNodeId|$localRole", endpointId, connectionLifecycleCallback)
                .addOnFailureListener { e ->
                    Log.d(TAG, "Request connection note: ${e.message}")
                }
        }

        override fun onEndpointLost(endpointId: String) {
            Log.d(TAG, "Lost sight of peer: $endpointId")
        }
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            Log.d(TAG, "Connection initiated: $endpointId (${info.endpointName})")
            // Always accept — no manual auth needed for local mesh
            client.acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                connectedEndpoints[endpointId] = connectedEndpoints[endpointId] ?: endpointId
                Log.d(TAG, "Connected: $endpointId | Total peers: ${connectedEndpoints.size}")
                notifyPeers()
                syncHistoryTo(endpointId)
            } else {
                Log.e(TAG, "Connection to $endpointId failed: ${result.status.statusMessage}")
            }
        }

        override fun onDisconnected(endpointId: String) {
            val name = connectedEndpoints.remove(endpointId) ?: endpointId
            Log.d(TAG, "Disconnected: $name | Total peers: ${connectedEndpoints.size}")
            notifyPeers()
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            val bytes = payload.asBytes() ?: return
            val json = String(bytes, Charsets.UTF_8)

            try {
                val message = Message.fromJson(json)
                val isNew = repository.add(message)
                if (isNew) {
                    Log.d(TAG, "New message from $endpointId: ${message.id}")
                    onMessageReceived(message)
                    broadcast(message, excludeEndpoint = endpointId)
                } else {
                    Log.d(TAG, "Duplicate dropped: ${message.id}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse payload: ${e.message}")
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            // Not needed for small text payloads
        }
    }

    // Broadcast a fully-formed Message (used by Civilian to send signal packets)
    fun broadcastMessage(message: Message) {
        if (repository.add(message)) {
            onMessageReceived(message)
            broadcast(message, excludeEndpoint = null)
            Log.d(TAG, "Broadcast message: ${message.id} type=${message.messageType}")
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    // Send full message history to a newly connected peer so they get everything
    // that happened before they joined. Their gossip dedup drops what they already have.
    private fun syncHistoryTo(endpointId: String) {
        val history = repository.getAllForSync()
        if (history.isEmpty()) return
        Log.d(TAG, "Syncing ${history.size} stored messages to $endpointId")
        history.forEach { message ->
            val payload = Payload.fromBytes(message.toJson().toByteArray(Charsets.UTF_8))
            client.sendPayload(endpointId, payload)
        }
    }

    private fun notifyPeers() {
        val count = connectedEndpoints.size
        MeshState.update(count)
        onPeersChanged(count, connectedEndpoints.values.toList())
    }
}
