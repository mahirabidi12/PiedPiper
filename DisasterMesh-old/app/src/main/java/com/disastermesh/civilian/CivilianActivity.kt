package com.disastermesh.civilian

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.disastermesh.AssistantActivity
import com.disastermesh.Message
import com.disastermesh.MeshManager
import com.disastermesh.R
import com.disastermesh.Role
import com.disastermesh.UserSession
import com.disastermesh.ai.GemmaClient
import com.disastermesh.ui.BottomNavHelper
import com.disastermesh.ui.NavItem
import com.disastermesh.ui.shell.ProfileShellActivity

class CivilianActivity : AppCompatActivity() {

    private lateinit var session: UserSession
    private lateinit var meshManager: MeshManager

    private lateinit var tvPeerStatus: TextView
    private lateinit var tvGpsStatus: TextView
    private lateinit var btnAiStatus: TextView
    private lateinit var btnSettings: TextView
    private lateinit var etMessage: EditText
    private lateinit var etLocation: EditText
    private lateinit var btnSend: Button
    private lateinit var btnGps: Button
    private lateinit var rvSent: RecyclerView

    private val sentAdapter = SentSignalAdapter()
    private var currentLat: Double? = null
    private var currentLon: Double? = null

    companion object {
        private const val REQUEST_PERMISSIONS = 2001
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
        setContentView(R.layout.activity_civilian)
        session = UserSession(this)
        bindViews()
        setupMesh()
        setupChips()
        setupSend()
        setupGps()
        setupAssistant()
        setupHeaderActions()
        BottomNavHelper.bind(this, NavItem.HOME)
        checkAndRequestPermissions()
    }

    private fun bindViews() {
        tvPeerStatus = findViewById(R.id.tvCivPeerStatus)
        tvGpsStatus = findViewById(R.id.tvGpsStatus)
        btnAiStatus = findViewById(R.id.btnCivAiStatus)
        btnSettings = findViewById(R.id.btnCivSettings)
        etMessage = findViewById(R.id.etCivMessage)
        etLocation = findViewById(R.id.etCivLocation)
        btnSend = findViewById(R.id.btnCivSend)
        btnGps = findViewById(R.id.btnGps)
        rvSent = findViewById(R.id.rvSentSignals)

        findViewById<TextView>(R.id.tvCivName).text = session.name
        rvSent.layoutManager = LinearLayoutManager(this)
        rvSent.adapter = sentAdapter
    }

    private fun setupMesh() {
        meshManager = MeshManager(
            context = this,
            deviceName = session.name,
            onMessageReceived = { /* civilians do not process incoming signals yet */ },
            onPeersChanged = { count, _ ->
                runOnUiThread {
                    tvPeerStatus.text = if (count == 0) "MESH 00" else "MESH ${count.toString().padStart(2, '0')}"
                    tvPeerStatus.setTextColor(
                        if (count > 0) Color.parseColor("#3FB950") else Color.parseColor("#A1A1AA")
                    )
                }
            }
        )
    }

    private fun setupChips() {
        mapOf(
            R.id.chipCivSos to "SOS - I need immediate rescue",
            R.id.chipCivMedical to "Medical emergency - ",
            R.id.chipCivRescue to "Rescue needed - ",
            R.id.chipCivResource to "Need supplies - "
        ).forEach { (id, text) ->
            findViewById<Button>(id).setOnClickListener {
                etMessage.setText(text)
                etMessage.setSelection(etMessage.text.length)
            }
        }
    }

    private fun setupSend() {
        btnSend.setOnClickListener {
            val text = etMessage.text.toString().trim()
            if (text.isEmpty()) {
                Toast.makeText(this, "Please describe the emergency", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val message = Message(
                senderId = session.name,
                senderName = session.name,
                senderRole = Role.USER.name,
                text = text,
                targetRole = "ALL",
                messageType = "SIGNAL",
                locationText = etLocation.text.toString().trim().ifEmpty { null },
                latitude = currentLat,
                longitude = currentLon
            )
            meshManager.broadcastMessage(message)
            sentAdapter.addItem("${text.take(60)}${if (text.length > 60) "..." else ""}")
            rvSent.scrollToPosition(0)
            etMessage.text.clear()
            Toast.makeText(this, "Signal sent to mesh", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupGps() {
        btnGps.setOnClickListener { captureGps() }
    }

    private fun setupAssistant() {
        findViewById<Button>(R.id.btnCivAssistant).setOnClickListener {
            startActivity(Intent(this, AssistantActivity::class.java))
        }
    }

    private fun setupHeaderActions() {
        btnAiStatus.setOnClickListener {
            startActivity(Intent(this, AssistantActivity::class.java))
        }
        btnSettings.setOnClickListener {
            startActivity(Intent(this, ProfileShellActivity::class.java))
        }

        GemmaClient.warmUp(this) { status ->
            btnAiStatus.text = when (status) {
                GemmaClient.Status.READY -> "AI READY"
                GemmaClient.Status.LOADING -> "AI LOADING"
                GemmaClient.Status.ABSENT -> "MODEL ABSENT"
                GemmaClient.Status.ERROR -> "AI ERROR"
            }
            btnAiStatus.setTextColor(
                if (status == GemmaClient.Status.READY) Color.parseColor("#3FB950")
                else Color.parseColor("#71717A")
            )
        }
    }

    private fun captureGps() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        val location = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)

        if (location != null) {
            currentLat = location.latitude
            currentLon = location.longitude
            tvGpsStatus.text = "GPS %.4f, %.4f".format(location.latitude, location.longitude)
            tvGpsStatus.setTextColor(Color.parseColor("#3FB950"))
        } else {
            tvGpsStatus.text = "No GPS fix. Describe the closest landmark below."
            tvGpsStatus.setTextColor(Color.parseColor("#F97316"))
        }
    }

    private fun checkAndRequestPermissions() {
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            meshManager.start()
            captureGps()
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
            captureGps()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        meshManager.stop()
    }
}
