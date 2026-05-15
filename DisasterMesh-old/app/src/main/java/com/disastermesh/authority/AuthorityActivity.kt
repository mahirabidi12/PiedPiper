package com.disastermesh.authority

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.disastermesh.MeshManager
import com.disastermesh.R
import com.disastermesh.Role
import com.disastermesh.UserSession
import com.disastermesh.ai.GemmaClient
import com.disastermesh.ai.SignalClassifier
import com.disastermesh.db.AppDatabase
import com.disastermesh.db.SignalEntity
import com.disastermesh.map.LocationClusterer
import com.disastermesh.models.AreaCluster
import com.disastermesh.models.Priority
import com.disastermesh.models.Signal
import com.disastermesh.models.SignalStatus
import com.disastermesh.ui.BottomNavHelper
import com.disastermesh.ui.NavItem
import com.disastermesh.ui.shell.ProfileShellActivity

class AuthorityActivity : AppCompatActivity() {

    private lateinit var session: UserSession
    private lateinit var meshManager: MeshManager
    private lateinit var db: AppDatabase

    private lateinit var tvPeerStatus: TextView
    private lateinit var tvGemmaStatus: TextView
    private lateinit var tvSignalCount: TextView
    private lateinit var tvStatTotal: TextView
    private lateinit var tvStatCritical: TextView
    private lateinit var tvStatInProgress: TextView
    private lateinit var tvStatResolved: TextView
    private lateinit var btnSettings: TextView
    private lateinit var rvDashboard: RecyclerView
    private lateinit var dashboardAdapter: DashboardAdapter

    companion object {
        const val EXTRA_SIGNAL_IDS = "signal_ids"
        const val EXTRA_AREA_LABEL = "area_label"
        private const val REQUEST_PERMISSIONS = 3001
    }

    private val requiredPermissions: Array<String> by lazy {
        buildList {
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.R) {
                add(Manifest.permission.BLUETOOTH)
                add(Manifest.permission.BLUETOOTH_ADMIN)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
                add(Manifest.permission.BLUETOOTH_ADVERTISE)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
            add(Manifest.permission.ACCESS_WIFI_STATE)
            add(Manifest.permission.CHANGE_WIFI_STATE)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }.toTypedArray()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_authority)

        session = UserSession(this)
        db = AppDatabase.getInstance(this)

        bindViews()
        setupDashboard()
        setupMesh()
        setupPrototypeActionShells()
        warmUpGemma()
        setupHeaderActions()
        BottomNavHelper.bind(this, NavItem.HOME)
        checkAndRequestPermissions()
    }

    private fun bindViews() {
        tvPeerStatus = findViewById(R.id.tvAuthPeerStatus)
        tvGemmaStatus = findViewById(R.id.tvGemmaStatus)
        tvSignalCount = findViewById(R.id.tvSignalCount)
        tvStatTotal = findViewById(R.id.tvStatTotal)
        tvStatCritical = findViewById(R.id.tvStatCritical)
        tvStatInProgress = findViewById(R.id.tvStatInProgress)
        tvStatResolved = findViewById(R.id.tvStatResolved)
        btnSettings = findViewById(R.id.btnAuthSettings)
        rvDashboard = findViewById(R.id.rvDashboard)
        findViewById<TextView>(R.id.tvAuthName).text = session.name
    }

    private fun setupDashboard() {
        dashboardAdapter = DashboardAdapter { cluster -> openAreaDetail(cluster) }
        rvDashboard.layoutManager = LinearLayoutManager(this)
        rvDashboard.adapter = dashboardAdapter
        refreshDashboard()
    }

    private fun setupMesh() {
        meshManager = MeshManager(
            context = this,
            deviceName = session.name,
            onMessageReceived = { message ->
                if (message.messageType == "SIGNAL" && message.senderRole == Role.USER.name) {
                    classifyAndStore(message)
                }
            },
            onPeersChanged = { count, _ ->
                runOnUiThread {
                    tvPeerStatus.text = "MESH ${count.toString().padStart(2, '0')}"
                    tvPeerStatus.setTextColor(
                        if (count > 0) Color.parseColor("#3FB950")
                        else Color.parseColor("#A1A1AA")
                    )
                }
            }
        )
    }

    private fun setupPrototypeActionShells() {
        findViewById<Button>(R.id.btnAuthoritySafeZoneShell).setOnClickListener {
            Toast.makeText(this, "Safe-zone shell is ready for fragment wiring.", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btnAuthorityPlanShell).setOnClickListener {
            Toast.makeText(this, "Resource-plan shell is ready for fragment wiring.", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btnAuthorityBroadcastShell).setOnClickListener {
            Toast.makeText(this, "Broadcast shell is ready for fragment wiring.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupHeaderActions() {
        btnSettings.setOnClickListener {
            startActivity(Intent(this, ProfileShellActivity::class.java))
        }
    }

    private fun warmUpGemma() {
        tvGemmaStatus.text = "AI status loading"
        tvGemmaStatus.setTextColor(Color.parseColor("#FFA033"))
        GemmaClient.warmUp(this) { status ->
            tvGemmaStatus.text = when (status) {
                GemmaClient.Status.READY -> "AI ready / Gemma 3n E2B"
                GemmaClient.Status.LOADING -> "AI loading model"
                GemmaClient.Status.ABSENT -> "AI model missing / keyword fallback active"
                GemmaClient.Status.ERROR -> "AI error / keyword fallback active"
            }
            tvGemmaStatus.setTextColor(
                if (status == GemmaClient.Status.READY) Color.parseColor("#3FB950")
                else Color.parseColor("#FFA033")
            )
        }
    }

    private fun classifyAndStore(message: com.disastermesh.Message) {
        SignalClassifier.classify(
            rawMessage = message.text,
            onResult = { result ->
                val signal = Signal(
                    senderId = message.senderId,
                    senderName = message.senderName,
                    rawMessage = message.text,
                    category = result.category,
                    priority = result.priority,
                    tags = result.tags,
                    summary = result.summary,
                    peopleCount = result.peopleCount,
                    latitude = message.latitude,
                    longitude = message.longitude,
                    locationText = message.locationText
                )
                db.signalDao().insert(SignalEntity.fromSignal(signal))
                runOnUiThread { refreshDashboard() }
            },
            onError = { /* keyword fallback already applied in SignalClassifier */ }
        )
    }

    private fun refreshDashboard() {
        val allSignals = db.signalDao().getAll().map { it.toSignal() }
        val activeSignals = allSignals.filter { it.status != SignalStatus.RESOLVED }
        val clusters = LocationClusterer.cluster(activeSignals)

        val criticalCount = activeSignals.count { it.priority == Priority.CRITICAL }
        val inProgressCount = activeSignals.count {
            it.status == SignalStatus.ACKNOWLEDGED || it.status == SignalStatus.IN_PROGRESS
        }
        val resolvedCount = allSignals.count { it.status == SignalStatus.RESOLVED }

        tvSignalCount.text = "${activeSignals.size} active signal${if (activeSignals.size != 1) "s" else ""}"
        tvStatTotal.text = "TOTAL\n${allSignals.size}"
        tvStatCritical.text = "CRITICAL\n$criticalCount"
        tvStatInProgress.text = "IN PROGRESS\n$inProgressCount"
        tvStatResolved.text = "RESOLVED\n$resolvedCount"
        dashboardAdapter.submitList(clusters)
    }

    private fun openAreaDetail(cluster: AreaCluster) {
        val ids = cluster.signals.map { it.id }
        startActivity(
            Intent(this, AreaDetailActivity::class.java).apply {
                putExtra(EXTRA_AREA_LABEL, cluster.areaLabel)
                putStringArrayListExtra(EXTRA_SIGNAL_IDS, ArrayList(ids))
            }
        )
    }

    private fun checkAndRequestPermissions() {
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            meshManager.start()
        } else {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQUEST_PERMISSIONS)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            meshManager.start()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshDashboard()
    }

    override fun onDestroy() {
        super.onDestroy()
        meshManager.stop()
    }
}
