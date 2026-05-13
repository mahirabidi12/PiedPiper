package com.disastermesh

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.disastermesh.adapter.MessageAdapter

class MainActivity : AppCompatActivity() {

    private lateinit var session: UserSession
    private lateinit var meshManager: MeshManager
    private lateinit var messageAdapter: MessageAdapter

    // Header
    private lateinit var tvUserName: TextView
    private lateinit var tvRoleBadge: TextView
    private lateinit var tvPeerStatus: TextView

    // User-only controls
    private lateinit var rowChips: LinearLayout
    private lateinit var rowTarget: LinearLayout
    private lateinit var spinnerTarget: Spinner

    // Message list + input
    private lateinit var rvMessages: RecyclerView
    private lateinit var etMessage: EditText
    private lateinit var btnSend: Button

    private var currentTarget = "ALL"

    companion object {
        private const val REQUEST_PERMISSIONS = 1001
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

        session = UserSession(this)

        // Redirect to setup if not configured
        if (!session.isSetup) {
            startActivity(Intent(this, RoleSetupActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_main)
        bindViews()
        applyRoleTheme()
        setupTargetSpinner()
        setupRecyclerView()
        setupMeshManager()
        setupSendButton()
        setupQuickChips()
        checkAndRequestPermissions()
    }

    private fun bindViews() {
        tvUserName    = findViewById(R.id.tvUserName)
        tvRoleBadge   = findViewById(R.id.tvRoleBadge)
        tvPeerStatus  = findViewById(R.id.tvPeerStatus)
        rowChips      = findViewById(R.id.rowChips)
        rowTarget     = findViewById(R.id.rowTarget)
        spinnerTarget = findViewById(R.id.spinnerTarget)
        rvMessages    = findViewById(R.id.rvMessages)
        etMessage     = findViewById(R.id.etMessage)
        btnSend       = findViewById(R.id.btnSend)

        tvUserName.text  = session.name
        tvRoleBadge.text = session.role.badgeLabel
    }

    private fun applyRoleTheme() {
        val role = session.role

        // Role badge styling
        tvRoleBadge.setTextColor(Color.WHITE)
        tvRoleBadge.setBackgroundColor(role.color())

        // Show/hide user-only rows
        val isUser = role == Role.USER
        rowChips.visibility  = if (isUser) View.VISIBLE else View.GONE
        rowTarget.visibility = if (isUser) View.VISIBLE else View.GONE

        // Send button colour follows role
        btnSend.backgroundTintList =
            android.content.res.ColorStateList.valueOf(role.color())

        // Hint text for input based on role
        etMessage.hint = when (role) {
            Role.USER      -> "Describe your emergency..."
            Role.VOLUNTEER -> "Broadcast to all peers..."
            Role.AUTHORITY -> "Official message to all..."
        }
    }

    private fun setupTargetSpinner() {
        val options = arrayOf("Everyone", "Volunteers only", "Authorities only")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, options)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerTarget.adapter = adapter
        spinnerTarget.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, pos: Int, id: Long) {
                currentTarget = when (pos) {
                    1 -> "VOLUNTEER"
                    2 -> "AUTHORITY"
                    else -> "ALL"
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }
    }

    private fun setupRecyclerView() {
        messageAdapter = MessageAdapter(
            localDeviceId = session.name,
            myRole        = session.role
        )
        rvMessages.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = messageAdapter
        }
    }

    private fun setupMeshManager() {
        meshManager = MeshManager(
            context    = this,
            deviceName = session.name,
            onMessageReceived = { message ->
                runOnUiThread {
                    if (message.isVisibleTo(session.role)) {
                        messageAdapter.addMessage(message)
                        rvMessages.scrollToPosition(0)
                    }
                }
            },
            onPeersChanged = { count, _ ->
                runOnUiThread {
                    tvPeerStatus.text = when (count) {
                        0    -> "● Searching for peers..."
                        1    -> "● 1 peer connected"
                        else -> "● $count peers connected"
                    }
                    tvPeerStatus.setTextColor(
                        if (count > 0) Color.parseColor("#3FB950")
                        else Color.parseColor("#F78166")
                    )
                }
            }
        )
    }

    private fun setupSendButton() {
        btnSend.setOnClickListener { sendMessage() }
        etMessage.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) { sendMessage(); true } else false
        }
    }

    private fun setupQuickChips() {
        findViewById<Button>(R.id.chipSos).setOnClickListener {
            etMessage.setText("🚨 SOS — I need immediate rescue help")
            currentTarget = "ALL"
            spinnerTarget.setSelection(0)
        }
        findViewById<Button>(R.id.chipMedical).setOnClickListener {
            etMessage.setText("🏥 Medical emergency — ")
            etMessage.setSelection(etMessage.text.length)
            currentTarget = "VOLUNTEER"
            spinnerTarget.setSelection(1)
        }
        findViewById<Button>(R.id.chipSupply).setOnClickListener {
            etMessage.setText("📦 Need supplies — ")
            etMessage.setSelection(etMessage.text.length)
            currentTarget = "ALL"
            spinnerTarget.setSelection(0)
        }
    }

    private fun sendMessage() {
        val text = etMessage.text.toString().trim()
        if (text.isEmpty()) return
        val target = if (session.role == Role.USER) currentTarget else "ALL"
        meshManager.sendMessage(text, session.role.name, target)
        etMessage.text.clear()
    }

    // ── Permissions ───────────────────────────────────────────────────────────

    private fun checkAndRequestPermissions() {
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) meshManager.start()
        else ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQUEST_PERMISSIONS)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) meshManager.start()
            else Toast.makeText(this, "All permissions are required for mesh networking", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::meshManager.isInitialized) {
            meshManager.stop()
        }
    }
}
