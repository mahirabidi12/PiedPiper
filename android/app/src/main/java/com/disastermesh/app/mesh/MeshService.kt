package com.disastermesh.app.mesh

import android.app.*
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.disastermesh.app.R
import com.disastermesh.app.core.NodeIdentity
import com.disastermesh.app.core.UserSession
import com.disastermesh.app.db.AppDatabase
import com.disastermesh.app.model.*
import com.disastermesh.app.notification.SignalNotificationManager
import com.disastermesh.app.ui.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject

/**
 * Foreground Service that keeps the mesh alive when the app is backgrounded.
 *
 * Lifecycle:
 *  - Started via startForegroundService() from DisasterMeshApp or MainActivity
 *  - Binds to Activities/Fragments via [MeshBinder] for direct API access
 *  - Posts a persistent notification showing peer count
 *  - Stops cleanly when the user explicitly leaves the mesh
 *
 * UI observes mesh state via the exposed [StateFlow]s.
 */
class MeshService : Service() {

    // ── Binder ────────────────────────────────────────────────────────────────

    inner class MeshBinder : Binder() {
        fun getService(): MeshService = this@MeshService
    }

    private val binder = MeshBinder()
    override fun onBind(intent: Intent): IBinder = binder

    // ── Internal state ────────────────────────────────────────────────────────

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var db: AppDatabase
    private lateinit var meshManager: MeshManager
    private lateinit var gossipRouter: GossipRouter

    // ── Observable state (UI subscribes via collect) ──────────────────────────

    private val _peerCount = MutableStateFlow(0)
    val peerCount: StateFlow<Int> = _peerCount.asStateFlow()

    private val _meshStatus = MutableStateFlow(MeshStatus.OFFLINE)
    val meshStatus: StateFlow<MeshStatus> = _meshStatus.asStateFlow()

    /** Emits each new Signal as it arrives over the mesh. */
    private val _incomingSignals = MutableSharedFlow<Signal>(replay = 0)
    val incomingSignals: SharedFlow<Signal> = _incomingSignals.asSharedFlow()

    /** Emits each new ChatMessage as it arrives over the mesh. */
    private val _incomingChat = MutableSharedFlow<ChatMessage>(replay = 0)
    val incomingChat: SharedFlow<ChatMessage> = _incomingChat.asSharedFlow()

    /** Room-aware: observe all messages for a room from DB (includes history). */
    fun observeRoom(roomId: String) = db.chatMessageDao().observeRoom(roomId)
    fun observeSignals() = db.signalDao().observeAll()
    fun observeMySignals(nodeId: String) = db.signalDao().observeByNode(nodeId)
    fun observePeers() = db.peerDao().observeConnected()
    fun observeAllPeers() = db.peerDao().observeAll()
    fun observeSyncLog() = db.syncLogDao().observeRecent()

    enum class MeshStatus { OFFLINE, SEARCHING, ONLINE, BT_OFF }

    companion object {
        private const val TAG = "MeshService"
        private const val NOTIF_CHANNEL = "mesh_channel"
        private const val NOTIF_ID = 1001
    }

    // Guard: onStartCommand may fire more than once (OS restart, rebind).
    // Only initialize the mesh once per service instance.
    private var started = false

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_OFF -> {
                    Log.w(TAG, "Bluetooth off — pausing mesh")
                    _meshStatus.value = MeshStatus.BT_OFF
                    _peerCount.value  = 0
                    if (::meshManager.isInitialized) meshManager.stop()
                }
                BluetoothAdapter.STATE_ON -> {
                    Log.d(TAG, "Bluetooth on — restarting mesh")
                    _meshStatus.value = MeshStatus.SEARCHING
                    val session = UserSession.get(applicationContext)
                    if (::meshManager.isInitialized && session != null) {
                        meshManager.start()
                    }
                }
            }
        }
    }

    // ── Service lifecycle ─────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        db = AppDatabase.getInstance(applicationContext)
        createNotificationChannel()
        registerReceiver(bluetoothReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        // One-time cleanup of content-duplicate chat rows left behind by the
        // previous buggy store-and-forward (each forwarded copy had a fresh
        // UUID so the PrimaryKey couldn't catch them). Keeps the earliest row
        // per (sender, room, text). Safe on every start — a clean DB returns 0.
        serviceScope.launch(Dispatchers.IO) {
            val removed = db.chatMessageDao().purgeContentDuplicates()
            if (removed > 0) Log.i(TAG, "Purged $removed duplicate chat rows on startup")
        }
        // Reclaim any leftover .part / .tmp / cacheDir copies of the model
        // binary from interrupted downloads — fixes the ~750 MB ghost cache.
        com.disastermesh.app.ai.ModelDownloader.clearTempDownloads(this)
        Log.d(TAG, "MeshService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(
            this,
            NOTIF_ID,
            buildNotification(0),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            } else {
                0
            }
        )

        if (started) {
            Log.d(TAG, "MeshService already running — ignoring duplicate start")
            return START_STICKY
        }

        val session = UserSession.get(applicationContext)
        if (session == null) {
            Log.w(TAG, "No session — mesh not started")
            return START_NOT_STICKY
        }

        started = true
        val nodeId = NodeIdentity.get(applicationContext)

        meshManager = MeshManager(
            context      = applicationContext,
            localNodeId  = nodeId,
            localName    = session.name,
            localRole    = session.role.name,
            callbacks    = buildCallbacks()
        )

        gossipRouter = GossipRouter(
            db               = db,
            meshManager      = meshManager,
            localNodeId      = nodeId,
            localName        = session.name,
            localRole        = session.role.name,
            scope            = serviceScope,
            application      = application,
            onSignalReceived = { signal ->
                serviceScope.launch { _incomingSignals.emit(signal) }
                if (signal.priority == SignalPriority.CRITICAL ||
                    signal.priority == SignalPriority.HIGH) {
                    SignalNotificationManager.notify(applicationContext, signal)
                }
            },
            onChatReceived   = { msg ->
                serviceScope.launch { _incomingChat.emit(msg) }
            },
            onPeerUpdated    = { /* peer list updated in DB; UI observes via Flow */ }
        )

        meshManager.start()
        _meshStatus.value = MeshStatus.SEARCHING
        Log.d(TAG, "Mesh started as ${session.name} [${session.role}]")
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        started = false
        unregisterReceiver(bluetoothReceiver)
        if (::meshManager.isInitialized) meshManager.stop()
        serviceScope.cancel()
        Log.d(TAG, "MeshService destroyed")
    }

    // ── Public API ────────────────────────────────────────────────────────────

    fun sendChat(roomId: String, text: String) {
        if (::gossipRouter.isInitialized) gossipRouter.sendChat(roomId, text)
    }

    fun sendSignal(signal: Signal) {
        if (::gossipRouter.isInitialized) gossipRouter.sendSignal(signal)
    }

    fun updateSignalStatus(signalId: String, newStatus: SignalStatus) {
        if (::gossipRouter.isInitialized) gossipRouter.sendSignalUpdate(signalId, newStatus.name)
    }

    fun sendBroadcast(message: String) {
        sendChat("all", "[BROADCAST] $message")
    }

    fun sendDm(recipientNodeId: String, text: String) {
        if (::gossipRouter.isInitialized) gossipRouter.sendDm(recipientNodeId, text)
    }

    fun observeDmThread(peerId: String): kotlinx.coroutines.flow.Flow<List<com.disastermesh.app.db.entities.DirectMessageEntity>> {
        val threadId = dmThreadId(NodeIdentity.get(applicationContext), peerId)
        return db.directMessageDao().observeThread(threadId)
    }

    /** Assign a volunteer + inventory to a signal and broadcast the status change. */
    fun assignSignal(signalId: String, volunteerId: String, volunteerName: String, inventoryJson: String) {
        serviceScope.launch(Dispatchers.IO) {
            db.signalDao().updateAssignment(signalId, volunteerId, volunteerName, inventoryJson, SignalStatus.IN_PROGRESS.name)
        }
        if (::gossipRouter.isInitialized) gossipRouter.sendSignalUpdate(signalId, SignalStatus.IN_PROGRESS.name)
    }

    /** Refund allocated inventory and mark signal unresolvable/expired. */
    fun clearSignalAssignment(signalId: String, inventoryJson: String?) {
        serviceScope.launch(Dispatchers.IO) {
            if (!inventoryJson.isNullOrBlank()) {
                val items = org.json.JSONObject(inventoryJson)
                items.keys().forEach { key ->
                    db.inventoryDao().adjustCount(key, items.getInt(key))
                }
            }
            db.signalDao().clearAssignment(signalId, SignalStatus.EXPIRED.name)
        }
        if (::gossipRouter.isInitialized) gossipRouter.sendSignalUpdate(signalId, SignalStatus.EXPIRED.name)
    }

    // Nearby P2P_CLUSTER can open multiple transport connections (BT + Wi-Fi Direct)
    // to the same physical device, each with a different endpointId but the same nodeId.
    // Count unique nodeIds to get the real peer count.
    private fun uniquePeerCount(): Int =
        meshManager.connectedEndpoints.values
            .map { it.split("|").firstOrNull() ?: it }
            .distinct()
            .size

    private fun dmThreadId(a: String, b: String) =
        "dm-${listOf(a, b).sorted().joinToString("-")}"

    // ── Peer callbacks → notification update ──────────────────────────────────

    private fun buildCallbacks() = object : MeshManager.MeshCallbacks {
        override fun onRawPacketReceived(fromEndpointId: String, bytes: ByteArray) {
            gossipRouter.onRawPacketReceived(fromEndpointId, bytes)
        }

        override fun onPeerConnected(endpointId: String, endpointName: String) {
            gossipRouter.onPeerConnected(endpointId, endpointName)
            val count = uniquePeerCount()
            _peerCount.value = count
            _meshStatus.value = MeshStatus.ONLINE
            updateNotification(count)
        }

        override fun onPeerDisconnected(endpointId: String) {
            gossipRouter.onPeerDisconnected(endpointId)
            val count = uniquePeerCount()
            _peerCount.value = count
            if (count == 0) _meshStatus.value = MeshStatus.SEARCHING
            updateNotification(count)
        }
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIF_CHANNEL,
                getString(R.string.notif_channel_mesh),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notif_channel_mesh_desc)
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(peerCount: Int): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pi = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, NOTIF_CHANNEL)
            .setContentTitle(getString(R.string.notif_mesh_title))
            .setContentText(getString(R.string.notif_mesh_text, peerCount))
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentIntent(pi)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification(peerCount: Int) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(peerCount))
    }
}
