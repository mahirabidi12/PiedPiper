package com.disastermesh.authority

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
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
import com.disastermesh.models.Signal

class AuthorityActivity : AppCompatActivity() {

    private lateinit var session: UserSession
    private lateinit var meshManager: MeshManager
    private lateinit var db: AppDatabase

    private lateinit var tvPeerStatus: TextView
    private lateinit var tvGemmaStatus: TextView
    private lateinit var tvSignalCount: TextView
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
        db      = AppDatabase.getInstance(this)

        bindViews()
        setupDashboard()
        setupMesh()
        warmUpGemma()
        checkAndRequestPermissions()
    }

    private fun bindViews() {
        tvPeerStatus   = findViewById(R.id.tvAuthPeerStatus)
        tvGemmaStatus  = findViewById(R.id.tvGemmaStatus)
        tvSignalCount  = findViewById(R.id.tvSignalCount)
        rvDashboard    = findViewById(R.id.rvDashboard)
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
            context    = this,
            deviceName = session.name,
            onMessageReceived = { message ->
                if (message.messageType == "SIGNAL" && message.senderRole == Role.USER.name) {
                    classifyAndStore(message)
                }
            },
            onPeersChanged = { count, _ ->
                runOnUiThread {
                    tvPeerStatus.text = if (count == 0) "● Searching..." else "● $count connected"
                    tvPeerStatus.setTextColor(
                        if (count > 0) Color.parseColor("#3FB950") else Color.parseColor("#F78166")
                    )
                }
            }
        )
    }

    private fun warmUpGemma() {
        tvGemmaStatus.text = "🤖 Gemma: loading..."
        tvGemmaStatus.setTextColor(Color.parseColor("#F97316"))
        GemmaClient.warmUp(this) { status ->
            tvGemmaStatus.text = when (status) {
                GemmaClient.Status.READY   -> "🤖 Gemma: ready"
                GemmaClient.Status.LOADING -> "🤖 Gemma: loading..."
                GemmaClient.Status.ABSENT  -> "🤖 Gemma: model missing"
                GemmaClient.Status.ERROR   -> "🤖 Gemma: error — using keywords"
            }
            tvGemmaStatus.setTextColor(
                if (status == GemmaClient.Status.READY) Color.parseColor("#3FB950")
                else Color.parseColor("#F97316")
            )
        }
    }

    private fun classifyAndStore(message: com.disastermesh.Message) {
        SignalClassifier.classify(
            rawMessage = message.text,
            onResult   = { result ->
                val signal = Signal(
                    senderId     = message.senderId,
                    senderName   = message.senderName,
                    rawMessage   = message.text,
                    category     = result.category,
                    priority     = result.priority,
                    tags         = result.tags,
                    summary      = result.summary,
                    peopleCount  = result.peopleCount,
                    latitude     = message.latitude,
                    longitude    = message.longitude,
                    locationText = message.locationText
                )
                db.signalDao().insert(SignalEntity.fromSignal(signal))
                runOnUiThread { refreshDashboard() }
            },
            onError = { /* SignalClassifier already uses keyword fallback */ }
        )
    }

    private fun refreshDashboard() {
        val signals  = db.signalDao().getActive().map { it.toSignal() }
        val clusters = LocationClusterer.cluster(signals)
        tvSignalCount.text = "${signals.size} active signal${if (signals.size != 1) "s" else ""}"
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
        if (missing.isEmpty()) meshManager.start()
        else ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQUEST_PERMISSIONS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS && grantResults.all { it == PackageManager.PERMISSION_GRANTED })
            meshManager.start()
    }

    override fun onResume() { super.onResume(); refreshDashboard() }
    override fun onDestroy() { super.onDestroy(); meshManager.stop() }
}
