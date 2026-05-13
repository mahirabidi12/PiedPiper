package com.disastermesh

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.disastermesh.adapter.MessageAdapter

class MainActivity : AppCompatActivity() {

    private lateinit var meshManager: MeshManager
    private lateinit var messageAdapter: MessageAdapter

    private lateinit var tvDeviceName: TextView
    private lateinit var tvPeerStatus: TextView
    private lateinit var rvMessages: RecyclerView
    private lateinit var etMessage: EditText
    private lateinit var btnSend: Button

    // Each device gets a random name so you can tell them apart during testing
    private val deviceName = "Phone-${(1000..9999).random()}"

    companion object {
        private const val REQUEST_PERMISSIONS = 1001
    }

    // All permissions needed by Nearby Connections, split by API level
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
        setContentView(R.layout.activity_main)

        bindViews()
        setupRecyclerView()
        setupMeshManager()
        setupSendButton()
        checkAndRequestPermissions()
    }

    private fun bindViews() {
        tvDeviceName = findViewById(R.id.tvDeviceName)
        tvPeerStatus = findViewById(R.id.tvPeerStatus)
        rvMessages = findViewById(R.id.rvMessages)
        etMessage = findViewById(R.id.etMessage)
        btnSend = findViewById(R.id.btnSend)

        tvDeviceName.text = deviceName
    }

    private fun setupRecyclerView() {
        messageAdapter = MessageAdapter(localDeviceId = deviceName)
        rvMessages.apply {
            layoutManager = LinearLayoutManager(this@MainActivity).apply {
                stackFromEnd = false
            }
            adapter = messageAdapter
        }
    }

    private fun setupMeshManager() {
        meshManager = MeshManager(
            context = this,
            deviceName = deviceName,
            onMessageReceived = { message ->
                runOnUiThread {
                    messageAdapter.addMessage(message)
                    rvMessages.scrollToPosition(0)
                }
            },
            onPeersChanged = { count, names ->
                runOnUiThread {
                    tvPeerStatus.text = when (count) {
                        0 -> "● Searching for peers..."
                        1 -> "● 1 peer connected: ${names.firstOrNull() ?: ""}"
                        else -> "● $count peers connected"
                    }
                }
            }
        )
    }

    private fun setupSendButton() {
        btnSend.setOnClickListener { sendMessage() }

        // Also send when user taps "Done" / "Send" on the keyboard
        etMessage.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendMessage()
                true
            } else false
        }
    }

    private fun sendMessage() {
        val text = etMessage.text.toString().trim()
        if (text.isEmpty()) return
        meshManager.sendMessage(text)
        etMessage.text.clear()
    }

    // ── Permissions ───────────────────────────────────────────────────────────

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
                Toast.makeText(
                    this,
                    "All permissions are required for peer-to-peer mesh networking",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        meshManager.stop()
    }
}
