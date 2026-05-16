package com.disastermesh.app.ui

import android.Manifest
import android.content.ComponentName
import android.util.Log
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.activity.viewModels
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.disastermesh.app.R
import com.disastermesh.app.core.UserSession
import com.disastermesh.app.databinding.ActivityMainBinding
import com.disastermesh.app.mesh.MeshService
import com.disastermesh.app.model.Role
import com.disastermesh.app.ui.comms.ChatFragment
import com.disastermesh.app.ui.home.CivilianHomeFragment
import com.disastermesh.app.ui.home.VolunteerHomeFragment
import com.disastermesh.app.ui.home.AuthorityHomeFragment
import com.disastermesh.app.ui.login.LoginFragment
import com.disastermesh.app.ui.ai.AiChatFragment
import com.disastermesh.app.ui.map.MapFragment
import com.disastermesh.app.ui.node.NodeFragment
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    internal lateinit var binding: ActivityMainBinding

    val appViewModel: AppViewModel by viewModels()

    // Bound MeshService reference (null until bound)
    var meshService: MeshService? = null
        private set

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            meshService = (binder as MeshService.MeshBinder).getService()
            observeMeshState()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            // OS killed the service unexpectedly — clear reference and schedule a rebind
            meshService = null
            Log.w("MainActivity", "MeshService disconnected — rebinding in 2 s")
            binding.root.postDelayed({ rebindMeshService() }, 2_000L)
        }
    }

    private fun rebindMeshService() {
        if (meshService != null || isFinishing || isDestroyed) return
        val intent = Intent(this, MeshService::class.java)
        ContextCompat.startForegroundService(this, intent)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    // ── Permissions ───────────────────────────────────────────────────────────

    private val requiredPermissions: Array<String> get() = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 31) {
            add(Manifest.permission.BLUETOOTH_ADVERTISE)
            add(Manifest.permission.BLUETOOTH_CONNECT)
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            add(Manifest.permission.BLUETOOTH)
            add(Manifest.permission.BLUETOOTH_ADMIN)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        if (allGranted) startMeshService()
        // If denied, mesh won't start but app still loads (degraded)
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupBottomNav()

        if (savedInstanceState == null) {
            if (UserSession.isLoggedIn(this)) {
                showMainUI()
                requestPermissionsAndStartMesh()
            } else {
                showLogin()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unbindService(serviceConnection) } catch (_: Exception) {}
    }

    // ── Navigation ────────────────────────────────────────────────────────────

    fun showLogin() {
        binding.topBar.visibility = View.GONE
        binding.topBarBorder.visibility = View.GONE
        binding.bottomNav.visibility = View.GONE
        binding.bottomNavBorder.visibility = View.GONE
        replaceFragment(LoginFragment(), addToBackStack = false)
    }

    fun showMainUI() {
        val session = UserSession.get(this) ?: return
        updateRoleBadge(session.role)
        binding.topBar.visibility = View.VISIBLE
        binding.topBarBorder.visibility = View.VISIBLE
        binding.bottomNav.visibility = View.VISIBLE
        binding.bottomNavBorder.visibility = View.VISIBLE
        applyRoleNavRestrictions(session.role)
        navigateHome(session.role)
    }

    private fun applyRoleNavRestrictions(role: Role) {
        // All roles get all 5 tabs — civilians need MAP for camp directions
        // and NODE for logout / role switching
    }

    private fun navigateHome(role: Role) {
        val fragment: Fragment = when (role) {
            Role.CIVILIAN   -> CivilianHomeFragment()
            Role.VOLUNTEER  -> VolunteerHomeFragment()
            Role.AUTHORITY  -> AuthorityHomeFragment()
        }
        replaceFragment(fragment, addToBackStack = false)
        binding.bottomNav.selectedItemId = R.id.nav_home
    }

    private fun setupBottomNav() {
        binding.bottomNav.setOnItemSelectedListener { item ->
            val session = UserSession.get(this)
            val fragment: Fragment = when (item.itemId) {
                R.id.nav_home  -> when (session?.role) {
                    Role.CIVILIAN   -> CivilianHomeFragment()
                    Role.VOLUNTEER  -> VolunteerHomeFragment()
                    Role.AUTHORITY  -> AuthorityHomeFragment()
                    null            -> CivilianHomeFragment()
                }
                R.id.nav_comms -> ChatFragment()
                R.id.nav_map   -> MapFragment.newInstance()
                R.id.nav_ai    -> AiChatFragment()
                R.id.nav_node  -> NodeFragment()
                else           -> return@setOnItemSelectedListener false
            }
            replaceFragment(fragment, addToBackStack = false)
            true
        }

        // Update labels based on role
        UserSession.get(this)?.role?.let { role ->
            binding.bottomNav.menu.findItem(R.id.nav_home)?.title = when (role) {
                Role.CIVILIAN  -> getString(R.string.nav_home)
                Role.VOLUNTEER -> getString(R.string.nav_tasks)
                Role.AUTHORITY -> getString(R.string.nav_cmd)
            }
        }
    }

    private fun replaceFragment(fragment: Fragment, addToBackStack: Boolean = true) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, fragment)
            .apply { if (addToBackStack) addToBackStack(null) }
            .commit()
    }

    // ── Mesh ──────────────────────────────────────────────────────────────────

    fun requestPermissionsAndStartMesh() {
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) startMeshService() else permissionLauncher.launch(missing.toTypedArray())
    }

    private fun startMeshService() {
        val intent = Intent(this, MeshService::class.java)
        ContextCompat.startForegroundService(this, intent)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun observeMeshState() {
        val svc = meshService ?: return
        lifecycleScope.launch {
            svc.peerCount.collectLatest { count ->
                binding.tvPeerCount.text = if (count > 0)
                    getString(R.string.label_peers_connected, count)
                else
                    getString(R.string.label_mesh_offline)
                val dotColor = if (count > 0) R.color.mesh_online else R.color.mesh_searching
                binding.meshDot.setBackgroundColor(getColor(dotColor))
                binding.tvPeerCount.setTextColor(getColor(dotColor))
            }
        }
    }

    // ── Top bar helpers ───────────────────────────────────────────────────────

    private fun updateRoleBadge(role: Role) {
        binding.tvRoleBadge.text = role.badge
        val color = when (role) {
            Role.CIVILIAN  -> getColor(R.color.civilian)
            Role.VOLUNTEER -> getColor(R.color.volunteer)
            Role.AUTHORITY -> getColor(R.color.authority)
        }
        binding.tvRoleBadge.setTextColor(color)
    }
}
