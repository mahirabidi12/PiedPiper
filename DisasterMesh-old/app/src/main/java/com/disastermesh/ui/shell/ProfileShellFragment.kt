package com.disastermesh.ui.shell

import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.disastermesh.AssistantActivity
import com.disastermesh.MeshState
import com.disastermesh.R
import com.disastermesh.RoleSetupActivity
import com.disastermesh.UserSession
import com.disastermesh.ui.GemmaStatusHelper

class ProfileShellFragment : Fragment(R.layout.fragment_profile_shell) {

    private lateinit var session: UserSession
    private var peerListener: ((Int) -> Unit)? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        session = UserSession(requireContext())

        val nodeId = ensureNodeId()
        view.findViewById<TextView>(R.id.tvProfileName).text = session.name
        view.findViewById<TextView>(R.id.tvProfileRole).text = session.role.displayName.uppercase()
        view.findViewById<TextView>(R.id.tvProfileNodeId).text = getString(R.string.profile_node_format, nodeId.take(9))
        view.findViewById<TextView>(R.id.tvProfileBattery).text = getString(R.string.profile_battery_format, batteryPercent())

        val tvPeerCount = view.findViewById<TextView>(R.id.tvProfilePeerCount)
        peerListener = { count ->
            activity?.runOnUiThread {
                tvPeerCount.text = if (count > 0) "PEERS\n$count" else getString(R.string.peers_placeholder)
            }
        }
        MeshState.addListener(peerListener!!)

        GemmaStatusHelper.bind(requireContext(), view.findViewById(R.id.tvProfileAiStatus), verbose = true)

        view.findViewById<TextView>(R.id.btnProfileSwitchRole).setOnClickListener {
            session.clear()
            startActivity(Intent(requireContext(), RoleSetupActivity::class.java))
            requireActivity().finish()
        }

        view.findViewById<TextView>(R.id.btnProfileManageAi).setOnClickListener {
            startActivity(Intent(requireContext(), AssistantActivity::class.java))
        }

        view.findViewById<TextView>(R.id.btnProfileSyncLog).setOnClickListener {
            Toast.makeText(requireContext(), getString(R.string.profile_sync_log_shell), Toast.LENGTH_SHORT).show()
        }

        view.findViewById<TextView>(R.id.btnProfileReset).setOnClickListener {
            session.clear()
            startActivity(Intent(requireContext(), RoleSetupActivity::class.java))
            requireActivity().finish()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        peerListener?.let { MeshState.removeListener(it) }
        peerListener = null
    }

    private fun ensureNodeId(): String {
        val existing = session.nodeId
        if (existing.isNotBlank()) return existing
        val generated = java.util.UUID.randomUUID().toString()
        session.nodeId = generated
        return generated
    }

    private fun batteryPercent(): Int {
        val manager = requireContext().getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).coerceAtLeast(0)
    }
}
