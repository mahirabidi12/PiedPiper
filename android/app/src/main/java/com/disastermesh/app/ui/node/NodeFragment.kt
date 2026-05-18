package com.disastermesh.app.ui.node

import android.content.Context
import android.os.BatteryManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.disastermesh.app.R
import com.disastermesh.app.adapter.PeerAdapter
import com.disastermesh.app.core.NodeIdentity
import com.disastermesh.app.core.UserSession
import com.disastermesh.app.databinding.FragmentNodeBinding
import com.disastermesh.app.db.AppDatabase
import com.disastermesh.app.db.entities.SyncLogEntity
import com.disastermesh.app.mesh.MeshService
import com.disastermesh.app.model.PeerState
import com.disastermesh.app.model.Role
import com.disastermesh.app.ui.MainActivity
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class NodeFragment : Fragment() {

    private var _binding: FragmentNodeBinding? = null
    private val binding get() = _binding!!

    private lateinit var peerAdapter: PeerAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentNodeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.root.background = com.disastermesh.app.ui.GridBackgroundDrawable(requireContext())
        setupIdentityCard()
        setupPeersList()
        setupActions()
        startObserving()
    }

    // ── Identity card ─────────────────────────────────────────────────────────

    private fun setupIdentityCard() {
        val ctx     = requireContext()
        val session = UserSession.get(ctx)
        val nodeId  = NodeIdentity.get(ctx)

        val roleColor = roleColor(session?.role)
        val roleTint  = roleTint(session?.role)

        binding.tvNodeRoleBadge.text = session?.role?.badge ?: "?"
        binding.tvNodeRoleBadge.setTextColor(roleColor)
        binding.tvNodeRoleBadge.setBackgroundColor(roleTint)

        binding.tvNodeUserName.text = session?.name ?: "Unknown"

        binding.tvNodeRoleLabel.text = "[${session?.role?.badge ?: "?"}]  ${session?.role?.displayName?.uppercase() ?: "UNKNOWN"}"
        binding.tvNodeRoleLabel.setTextColor(roleColor)

        // Short node ID (first 8 chars)
        binding.tvNodeId.text = nodeId.take(8)

        // Battery
        val bm   = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val batt = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        binding.tvNodeBattery.text = "$batt%"
        val battColor = when {
            batt > 60 -> ctx.getColor(R.color.mesh_online)
            batt > 25 -> ctx.getColor(R.color.mesh_searching)
            else      -> ctx.getColor(R.color.priority_critical)
        }
        binding.tvNodeBattery.setTextColor(battColor)
    }

    // ── Peers RecyclerView ────────────────────────────────────────────────────

    private fun setupPeersList() {
        peerAdapter = PeerAdapter()
        binding.rvPeers.layoutManager = LinearLayoutManager(requireContext())
        binding.rvPeers.adapter = peerAdapter
        binding.rvPeers.isNestedScrollingEnabled = false
    }

    // ── Actions ───────────────────────────────────────────────────────────────

    private fun setupActions() {
        binding.btnLeaveMesh.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("Leave Mesh")
                .setMessage("This clears your session and stops the mesh network. Continue?")
                .setPositiveButton("Leave") { _, _ ->
                    (requireActivity() as MainActivity).stopMeshSession()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    // ── Observations ──────────────────────────────────────────────────────────

    private fun startObserving() {
        val db  = AppDatabase.getInstance(requireContext())
        val svc = (requireActivity() as MainActivity).meshService

        // DB-backed: always observed regardless of service bound state.
        // Peers show connected first, then historical (dimmed in adapter).
        viewLifecycleOwner.lifecycleScope.launch {
            db.peerDao().observeAll().collectLatest { entities ->
                val connected = entities.map { it.toDomain() }
                    .filter { it.connectionState == PeerState.CONNECTED }
                    .sortedByDescending { it.lastSeen }
                peerAdapter.submitList(connected)
                val count = connected.size
                binding.tvPeersHeader.text = "CONNECTED PEERS [${count.toString().padStart(2, '0')}]"
                binding.tvNodePeerCount.text = count.toString()
                binding.tvNoPeers.visibility = if (connected.isEmpty()) View.VISIBLE else View.GONE
                binding.rvPeers.visibility   = if (connected.isEmpty()) View.GONE   else View.VISIBLE
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            db.syncLogDao().observeRecent().collectLatest { logs ->
                showSyncLogEntries(logs.take(8))
            }
        }

        // Service-backed: mesh status + gossip router label.
        // If service isn't bound yet, show OFFLINE and wait — the user can
        // navigate away and back once the service connects.
        if (svc == null) {
            applyMeshStatus(MeshService.MeshStatus.OFFLINE, 0)
            return
        }

        viewLifecycleOwner.lifecycleScope.launch {
            svc.meshStatus.collectLatest { status ->
                applyMeshStatus(status, svc.peerCount.value)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            svc.peerCount.collectLatest { count ->
                binding.tvGossipValue.text = "$count peers · ttl 8"
            }
        }
    }

    // ── UI helpers ────────────────────────────────────────────────────────────

    private fun applyMeshStatus(status: MeshService.MeshStatus, peerCount: Int) {
        val ctx = requireContext()
        val (dotColor, label, transportText) = when (status) {
            MeshService.MeshStatus.ONLINE    -> Triple(R.color.mesh_online,       "ONLINE",         "Nearby · P2P_CLUSTER")
            MeshService.MeshStatus.SEARCHING -> Triple(R.color.mesh_searching,    "SEARCHING",      "Nearby · P2P_CLUSTER")
            MeshService.MeshStatus.BT_OFF    -> Triple(R.color.priority_critical, "BLUETOOTH OFF",  "Enable Bluetooth to join mesh")
            MeshService.MeshStatus.OFFLINE   -> Triple(R.color.mesh_offline,      "OFFLINE",        "—")
        }
        val color = ctx.getColor(dotColor)

        binding.nodeStatusDot.setBackgroundColor(color)
        binding.tvMeshStatusLabel.text = label
        binding.tvMeshStatusLabel.setTextColor(color)
        binding.dotTransport.setBackgroundColor(color)
        binding.dotGossip.setBackgroundColor(color)
        binding.tvTransportValue.text = transportText

        if (status != MeshService.MeshStatus.OFFLINE) {
            binding.tvGossipValue.text = "$peerCount peers · ttl 8"
        }
    }

    private fun showSyncLogEntries(entries: List<SyncLogEntity>) {
        val container = binding.containerSyncLog
        container.removeAllViews()

        if (entries.isEmpty()) {
            container.addView(makeSyncLogTextView("No events yet", R.color.text_dim))
            return
        }

        entries.forEach { entry ->
            val levelColorRes = when (entry.level) {
                "WARN"  -> R.color.mesh_searching
                "ERROR" -> R.color.priority_critical
                else    -> R.color.text_muted
            }
            val age  = ageText(entry.createdAt)
            val line = "[${entry.level}] ${entry.message}  ·  $age"
            container.addView(makeSyncLogTextView(line, levelColorRes))
        }
    }

    private fun makeSyncLogTextView(text: String, colorRes: Int): TextView {
        return TextView(requireContext()).apply {
            this.text = text
            setTextColor(requireContext().getColor(colorRes))
            textSize   = 9.5f
            typeface   = android.graphics.Typeface.MONOSPACE
            val pad    = (4 * resources.displayMetrics.density).toInt()
            setPadding(0, pad, 0, pad)
        }
    }

    private fun ageText(epochMs: Long): String {
        val diff = System.currentTimeMillis() - epochMs
        return when {
            diff < TimeUnit.MINUTES.toMillis(1) -> "just now"
            diff < TimeUnit.HOURS.toMillis(1)   -> "${TimeUnit.MILLISECONDS.toMinutes(diff)}m ago"
            diff < TimeUnit.DAYS.toMillis(1)    -> "${TimeUnit.MILLISECONDS.toHours(diff)}h ago"
            else                                -> "${TimeUnit.MILLISECONDS.toDays(diff)}d ago"
        }
    }

    // ── Role helpers ──────────────────────────────────────────────────────────

    private fun roleColor(role: Role?): Int {
        val ctx = requireContext()
        return when (role) {
            Role.CIVILIAN  -> ctx.getColor(R.color.civilian)
            Role.VOLUNTEER -> ctx.getColor(R.color.volunteer)
            Role.AUTHORITY -> ctx.getColor(R.color.authority)
            null           -> ctx.getColor(R.color.civilian)
        }
    }

    private fun roleTint(role: Role?): Int {
        val ctx = requireContext()
        return when (role) {
            Role.CIVILIAN  -> ctx.getColor(R.color.civilian_tint)
            Role.VOLUNTEER -> ctx.getColor(R.color.volunteer_tint)
            Role.AUTHORITY -> ctx.getColor(R.color.authority_tint)
            null           -> ctx.getColor(R.color.civilian_tint)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
