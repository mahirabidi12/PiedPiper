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
import com.disastermesh.R
import com.disastermesh.RoleSetupActivity
import com.disastermesh.UserSession
import com.disastermesh.ai.GemmaClient

/**
 * Profile shell for node settings and actions.
 */
class ProfileShellFragment : Fragment(R.layout.fragment_profile_shell) {

	private lateinit var session: UserSession

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)
		session = UserSession(requireContext())

		val nodeId = ensureNodeId()
		view.findViewById<TextView>(R.id.tvProfileName).text = session.name
		view.findViewById<TextView>(R.id.tvProfileRole).text = session.role.displayName.uppercase()
		view.findViewById<TextView>(R.id.tvProfileNodeId).text = "NODE\n${nodeId.take(9)}"
		view.findViewById<TextView>(R.id.tvProfilePeerCount).text = "PEERS\n--"
		view.findViewById<TextView>(R.id.tvProfileBattery).text = "BATT\n${batteryPercent()}%"

		val aiStatus = view.findViewById<TextView>(R.id.tvProfileAiStatus)
		GemmaClient.warmUp(requireContext()) { status ->
			aiStatus.text = when (status) {
				GemmaClient.Status.READY -> "AI ready / Gemma 3n E2B"
				GemmaClient.Status.LOADING -> "AI loading model"
				GemmaClient.Status.ABSENT -> "AI model missing"
				GemmaClient.Status.ERROR -> "AI error"
			}
		}

		view.findViewById<TextView>(R.id.btnProfileSwitchRole).setOnClickListener {
			session.clear()
			startActivity(Intent(requireContext(), RoleSetupActivity::class.java))
			requireActivity().finish()
		}

		view.findViewById<TextView>(R.id.btnProfileManageAi).setOnClickListener {
			startActivity(Intent(requireContext(), AssistantActivity::class.java))
		}

		view.findViewById<TextView>(R.id.btnProfileSyncLog).setOnClickListener {
			Toast.makeText(requireContext(), "Sync log shell ready.", Toast.LENGTH_SHORT).show()
		}

		view.findViewById<TextView>(R.id.btnProfileReset).setOnClickListener {
			session.clear()
			startActivity(Intent(requireContext(), RoleSetupActivity::class.java))
			requireActivity().finish()
		}
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
