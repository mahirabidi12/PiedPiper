package com.disastermesh.app.mesh

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*

/**
 * Thin wrapper around Google Nearby Connections.
 *
 * Responsibilities:
 *  - Advertise + Discover simultaneously using P2P_CLUSTER
 *  - Auto-accept all incoming connections (open emergency mesh)
 *  - Send raw byte payloads to individual endpoints or broadcast
 *  - Notify [GossipRouter] of raw incoming bytes and peer lifecycle events
 *
 * Reconnection: advertising and discovery failures are retried with
 * exponential backoff (2 s → 4 → 8 → … capped at 32 s) so a transient
 * Bluetooth/Wi-Fi hiccup doesn't permanently kill the mesh.
 */
class MeshManager(
    private val context: Context,
    private val localNodeId: String,
    private val localName: String,
    private val localRole: String,
    private val callbacks: MeshCallbacks
) {
    interface MeshCallbacks {
        fun onRawPacketReceived(fromEndpointId: String, bytes: ByteArray)
        fun onPeerConnected(endpointId: String, endpointName: String)
        fun onPeerDisconnected(endpointId: String)
    }

    private val client = Nearby.getConnectionsClient(context)

    // endpointId → displayName  (only currently connected peers)
    // ConcurrentHashMap: Nearby callbacks write on main thread, broadcast() reads on IO thread
    private val connected = java.util.concurrent.ConcurrentHashMap<String, String>()
    val connectedEndpoints: Map<String, String> get() = connected.toMap()

    // Stores the endpoint name from onConnectionInitiated until onConnectionResult fires
    private val pendingNames = mutableMapOf<String, String>()

    // Retry machinery — one Handler per operation type
    private val retryHandler = Handler(Looper.getMainLooper())
    private var advertisingRetryDelay = RETRY_INITIAL_MS
    private var discoveryRetryDelay   = RETRY_INITIAL_MS

    // Tracks whether stop() has been called so pending retries are a no-op
    private var stopped = false

    companion object {
        private const val SERVICE_ID       = "com.disastermesh.mesh"
        private const val TAG              = "MeshManager"
        private const val RETRY_INITIAL_MS = 2_000L
        private const val RETRY_MAX_MS     = 32_000L
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    fun start(initiateConnections: Boolean = true) {
        stopped = false
        startAdvertising()
        if (initiateConnections) startDiscovery()
    }

    fun stop() {
        stopped = true
        retryHandler.removeCallbacksAndMessages(null)
        client.stopAdvertising()
        client.stopDiscovery()
        client.stopAllEndpoints()
        connected.clear()
        pendingNames.clear()
        Log.d(TAG, "Mesh stopped")
    }

    // ── Send ──────────────────────────────────────────────────────────────────

    /** Broadcast to all connected peers except [excludeEndpointId]. */
    fun broadcast(bytes: ByteArray, excludeEndpointId: String? = null) {
        val targets = connected.keys.filter { it != excludeEndpointId }
        if (targets.isEmpty()) return
        val payload = Payload.fromBytes(bytes)
        targets.forEach { endpointId ->
            client.sendPayload(endpointId, payload)
                .addOnFailureListener { e ->
                    Log.w(TAG, "Broadcast failed → $endpointId: ${e.message}")
                }
        }
    }

    /** Send to a single endpoint. */
    fun sendTo(endpointId: String, bytes: ByteArray) {
        client.sendPayload(endpointId, Payload.fromBytes(bytes))
            .addOnFailureListener { e ->
                Log.w(TAG, "Send failed → $endpointId: ${e.message}")
            }
    }

    // ── Advertising ───────────────────────────────────────────────────────────

    private fun startAdvertising() {
        if (stopped) return
        val options = AdvertisingOptions.Builder()
            .setStrategy(Strategy.P2P_CLUSTER)
            .build()
        client.startAdvertising("$localNodeId|$localRole", SERVICE_ID, connectionLifecycleCallback, options)
            .addOnSuccessListener {
                advertisingRetryDelay = RETRY_INITIAL_MS   // reset backoff on success
                Log.d(TAG, "Advertising as: $localName ($localNodeId) [$localRole]")
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Advertising failed: ${e.message} — retry in ${advertisingRetryDelay}ms")
                retryHandler.postDelayed({
                    advertisingRetryDelay = minOf(advertisingRetryDelay * 2, RETRY_MAX_MS)
                    startAdvertising()
                }, advertisingRetryDelay)
            }
    }

    // ── Discovery ─────────────────────────────────────────────────────────────

    private fun startDiscovery() {
        if (stopped) return
        val options = DiscoveryOptions.Builder()
            .setStrategy(Strategy.P2P_CLUSTER)
            .build()
        client.startDiscovery(SERVICE_ID, endpointDiscoveryCallback, options)
            .addOnSuccessListener {
                discoveryRetryDelay = RETRY_INITIAL_MS     // reset backoff on success
                Log.d(TAG, "Discovery started")
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Discovery failed: ${e.message} — retry in ${discoveryRetryDelay}ms")
                retryHandler.postDelayed({
                    discoveryRetryDelay = minOf(discoveryRetryDelay * 2, RETRY_MAX_MS)
                    startDiscovery()
                }, discoveryRetryDelay)
            }
    }

    // ── Nearby callbacks ──────────────────────────────────────────────────────

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            // info.endpointName is "$remoteNodeId|$remoteRole"
            val parts        = info.endpointName.split("|")
            val remoteNodeId = parts.getOrElse(0) { info.endpointName }
            val remoteRole   = parts.getOrElse(1) { "" }
            Log.d(TAG, "Endpoint found: $endpointId ($remoteNodeId) [$remoteRole]")

            // Skip self-discovery — Nearby sometimes returns stale cached ads from our own previous session
            if (remoteNodeId == localNodeId) {
                Log.d(TAG, "Skipping self-discovery: $endpointId")
                return
            }

            // Skip if already connected or handshake already in flight
            if (connected.containsKey(endpointId) || pendingNames.containsKey(endpointId)) {
                Log.d(TAG, "Already connected/pending $endpointId — skipping")
                return
            }

            pendingNames[endpointId] = remoteNodeId

            // Tiebreaker: lower nodeId always initiates so exactly one requestConnection()
            // is in flight between any pair — prevents STATUS_ENDPOINT_IO_ERROR (8012).
            if (localNodeId > remoteNodeId) {
                Log.d(TAG, "Tiebreaker: waiting for $remoteNodeId to initiate")
                return
            }

            client.requestConnection("$localNodeId|$localRole", endpointId, connectionLifecycleCallback)
                .addOnFailureListener { e ->
                    Log.d(TAG, "requestConnection failed → $endpointId: ${e.message}")
                    pendingNames.remove(endpointId)
                }
        }

        override fun onEndpointLost(endpointId: String) {
            Log.d(TAG, "Endpoint lost: $endpointId")
            pendingNames.remove(endpointId)
        }
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            Log.d(TAG, "Connection initiated: $endpointId (${info.endpointName})")
            // Always prefer the name from ConnectionInfo — it's the most authoritative source
            pendingNames[endpointId] = info.endpointName
            client.acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                val name = pendingNames.remove(endpointId) ?: endpointId
                connected[endpointId] = name
                Log.d(TAG, "Connected: $endpointId ($name) | total=${connected.size}")
                callbacks.onPeerConnected(endpointId, name)
            } else {
                pendingNames.remove(endpointId)
                Log.w(TAG, "Connection to $endpointId failed: ${result.status.statusMessage}")
            }
        }

        override fun onDisconnected(endpointId: String) {
            val name = connected.remove(endpointId) ?: endpointId
            pendingNames.remove(endpointId)
            Log.d(TAG, "Disconnected: $endpointId ($name) | total=${connected.size}")
            callbacks.onPeerDisconnected(endpointId)
            // Restart both advertising and discovery so both sides get fresh BLE
            // advertisements and active scans — cuts reconnection time significantly.
            if (!stopped) {
                client.stopDiscovery()
                client.stopAdvertising()
                advertisingRetryDelay = RETRY_INITIAL_MS
                discoveryRetryDelay   = RETRY_INITIAL_MS
                startAdvertising()
                startDiscovery()
            }
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            val bytes = payload.asBytes() ?: return
            callbacks.onRawPacketReceived(endpointId, bytes)
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            // Not needed for small BYTES payloads
        }
    }
}
