package com.disastermesh

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.disastermesh.adapter.MessageAdapter
import com.disastermesh.ai.GemmaClient
import com.disastermesh.ui.BottomNavHelper
import com.disastermesh.ui.GemmaStatusHelper
import com.disastermesh.ui.MeshStatusHelper
import com.disastermesh.ui.NavItem
import com.disastermesh.ui.shell.MapShellActivity
import com.disastermesh.ui.shell.ProfileShellActivity

class MainActivity : AppCompatActivity() {

    private lateinit var session: UserSession
    private lateinit var meshManager: MeshManager
    private lateinit var messageAdapter: MessageAdapter

    private lateinit var tvUserName: TextView
    private lateinit var tvRoleBadge: TextView
    private lateinit var tvPeerStatus: TextView

    private lateinit var btnAiStatus: TextView
    private lateinit var btnSettings: TextView
    private lateinit var btnVolunteerMap: TextView

    private lateinit var rowChips: LinearLayout
    private lateinit var rowTarget: LinearLayout
    private lateinit var spinnerTarget: Spinner

    private lateinit var rvMessages: RecyclerView
    private lateinit var etMessage: EditText
    private lateinit var btnSend: Button

    private var currentTarget = AppConstants.TARGET_ALL

    companion object {
        private val REQUEST_PERMISSIONS = AppConstants.REQUEST_PERMISSIONS_MAIN
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

        if (!session.isSetup) {
            startActivity(Intent(this, RoleSetupActivity::class.java))
            finish()
            return
        }

        when (session.role) {
            Role.USER -> {
                startActivity(Intent(this, com.disastermesh.civilian.CivilianActivity::class.java))
                finish()
                return
            }

            Role.AUTHORITY -> {
                startActivity(Intent(this, com.disastermesh.authority.AuthorityActivity::class.java))
                finish()
                return
            }

            Role.VOLUNTEER -> {
                // Volunteer stays on this mesh-comms screen for now.
            }
        }

        setContentView(R.layout.activity_main)
        bindViews()
        applyRoleTheme()
        setupTargetSpinner()
        setupRecyclerView()
        setupMeshManager()
        setupSendButton()
        setupQuickChips()
        setupHeaderActions()
        BottomNavHelper.bind(this, NavItem.HOME)
        checkAndRequestPermissions()
    }

    private fun bindViews() {
        tvUserName = findViewById(R.id.tvUserName)
        tvRoleBadge = findViewById(R.id.tvRoleBadge)
        tvPeerStatus = findViewById(R.id.tvPeerStatus)
        rowChips = findViewById(R.id.rowChips)
        rowTarget = findViewById(R.id.rowTarget)
        spinnerTarget = findViewById(R.id.spinnerTarget)
        rvMessages = findViewById(R.id.rvMessages)
        etMessage = findViewById(R.id.etMessage)
        btnSend = findViewById(R.id.btnSend)
        btnAiStatus = findViewById(R.id.btnAiStatus)
        btnSettings = findViewById(R.id.btnSettings)
        btnVolunteerMap = findViewById(R.id.btnVolunteerMap)

        tvUserName.text = session.name
        tvRoleBadge.text = session.role.badgeLabel
    }

    private fun applyRoleTheme() {
        val role = session.role

        tvRoleBadge.setTextColor(Color.WHITE)
        tvRoleBadge.setBackgroundColor(role.color())

        val isUser = role == Role.USER
        rowChips.visibility = if (isUser) View.VISIBLE else View.GONE
        rowTarget.visibility = if (isUser) View.VISIBLE else View.GONE

        btnSend.backgroundTintList =
            android.content.res.ColorStateList.valueOf(role.color())

        etMessage.hint = getString(when (role) {
            Role.USER      -> R.string.hint_civilian
            Role.VOLUNTEER -> R.string.hint_volunteer
            Role.AUTHORITY -> R.string.hint_authority
        })
    }

    private fun setupTargetSpinner() {
        val options = arrayOf(
            getString(R.string.target_everyone),
            getString(R.string.target_volunteers),
            getString(R.string.target_authorities)
        )
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, options)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerTarget.adapter = adapter
        spinnerTarget.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, pos: Int, id: Long) {
                currentTarget = when (pos) {
                    1    -> AppConstants.TARGET_VOLUNTEER
                    2    -> AppConstants.TARGET_AUTHORITY
                    else -> AppConstants.TARGET_ALL
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>) = Unit
        }
    }

    private fun setupRecyclerView() {
        messageAdapter = MessageAdapter(
            localDeviceId = session.name,
            myRole = session.role
        )
        rvMessages.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = messageAdapter
        }
    }

    private fun setupMeshManager() {
        meshManager = MeshManager(
            context = this,
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
                runOnUiThread { MeshStatusHelper.update(this, tvPeerStatus, count) }
            }
        )
    }

    private fun setupSendButton() {
        btnSend.setOnClickListener { sendMessage() }
        etMessage.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendMessage()
                true
            } else {
                false
            }
        }
    }

    private fun setupQuickChips() {
        findViewById<Button>(R.id.chipSos).setOnClickListener {
            etMessage.setText(getString(R.string.chip_sos_volunteer))
            currentTarget = AppConstants.TARGET_ALL
            spinnerTarget.setSelection(0)
        }
        findViewById<Button>(R.id.chipMedical).setOnClickListener {
            etMessage.setText(getString(R.string.chip_medical_prefix))
            etMessage.setSelection(etMessage.text.length)
            currentTarget = AppConstants.TARGET_VOLUNTEER
            spinnerTarget.setSelection(1)
        }
        findViewById<Button>(R.id.chipSupply).setOnClickListener {
            etMessage.setText(getString(R.string.chip_supply_prefix))
            etMessage.setSelection(etMessage.text.length)
            currentTarget = AppConstants.TARGET_ALL
            spinnerTarget.setSelection(0)
        }
    }

    private fun setupHeaderActions() {
        btnAiStatus.setOnClickListener {
            startActivity(Intent(this, AssistantActivity::class.java))
        }
        btnSettings.setOnClickListener {
            startActivity(Intent(this, ProfileShellActivity::class.java))
        }
        btnVolunteerMap.setOnClickListener {
            startActivity(Intent(this, MapShellActivity::class.java))
        }

        GemmaStatusHelper.bind(this, btnAiStatus)
    }

    private fun sendMessage() {
        val text = etMessage.text.toString().trim()
        if (text.isEmpty()) return

        val target = if (session.role == Role.USER) currentTarget else AppConstants.TARGET_ALL
        meshManager.sendMessage(text, session.role.name, target)
        etMessage.text.clear()
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
        if (requestCode == REQUEST_PERMISSIONS) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                meshManager.start()
            } else {
                Toast.makeText(this, "All permissions are required for mesh networking", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::meshManager.isInitialized) {
            meshManager.stop()
        }
    }
}
