package com.disastermesh.app.ui.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.disastermesh.app.R
import com.disastermesh.app.adapter.AreaClusterAdapter
import com.disastermesh.app.adapter.SignalAdapter
import com.disastermesh.app.databinding.FragmentHomeAuthorityBinding
import com.disastermesh.app.map.LocationClusterer
import com.disastermesh.app.model.SignalPriority
import com.disastermesh.app.model.SignalStatus
import com.disastermesh.app.ui.AppViewModel
import com.disastermesh.app.ui.MainActivity
import com.disastermesh.app.ui.sheet.AssignTicketBottomSheet
import com.disastermesh.app.ui.sheet.BroadcastBottomSheet
import com.disastermesh.app.ui.sheet.InventoryBottomSheet
import com.disastermesh.app.ui.sheet.ResolveTicketBottomSheet
import com.disastermesh.app.ui.sheet.SignalDetailBottomSheet
import com.disastermesh.app.ui.zone.ZoneDetailFragment
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class AuthorityHomeFragment : Fragment() {

    private var _binding: FragmentHomeAuthorityBinding? = null
    private val binding get() = _binding!!

    private val appViewModel: AppViewModel by activityViewModels()
    private var showingZones = true

    private val zoneAdapter = AreaClusterAdapter { cluster ->
        val fragment = ZoneDetailFragment.newInstance(
            areaLabel = cluster.areaLabel,
            signalIds = ArrayList(cluster.signals.map { it.id })
        )
        requireActivity().supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, fragment)
            .addToBackStack("zone_detail")
            .commit()
    }

    private val signalAdapter = SignalAdapter { signal ->
        when (signal.status) {
            SignalStatus.NEW, SignalStatus.ACKNOWLEDGED -> {
                AssignTicketBottomSheet.newInstance(signal, appViewModel.inventory.value) { volunteerId, volunteerName, inventoryJson ->
                    val svc = (requireActivity() as MainActivity).meshService ?: return@newInstance
                    // Deduct inventory
                    val items = org.json.JSONObject(inventoryJson)
                    items.keys().forEach { key ->
                        appViewModel.adjustInventory(key, -items.getInt(key))
                    }
                    svc.assignSignal(signal.id, volunteerId, volunteerName, inventoryJson)
                }.show(childFragmentManager, "assign_ticket")
            }
            SignalStatus.IN_PROGRESS -> {
                ResolveTicketBottomSheet.newInstance(signal) { resolved ->
                    val svc = (requireActivity() as MainActivity).meshService ?: return@newInstance
                    if (resolved) {
                        svc.updateSignalStatus(signal.id, SignalStatus.RESOLVED)
                    } else {
                        svc.clearSignalAssignment(signal.id, signal.inventoryAllocated)
                    }
                }.show(childFragmentManager, "resolve_ticket")
            }
            else -> {
                SignalDetailBottomSheet.newInstance(signal) { s, newStatus ->
                    (requireActivity() as MainActivity).meshService?.updateSignalStatus(s.id, newStatus)
                }.show(childFragmentManager, "signal_detail")
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeAuthorityBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.rvSignals.layoutManager = LinearLayoutManager(requireContext())
        setZonesTab()

        binding.tabZones.setOnClickListener      { setZonesTab() }
        binding.tabAllSignals.setOnClickListener { setAllSignalsTab() }

        binding.btnBroadcast.setOnClickListener {
            BroadcastBottomSheet.newInstance { message ->
                (requireActivity() as MainActivity).meshService?.sendBroadcast(message)
            }.show(childFragmentManager, "broadcast")
        }

        binding.btnSafeZone.setOnClickListener {
            // Navigate to map tab with drop mode pre-enabled
            requireActivity().supportFragmentManager.beginTransaction()
                .replace(R.id.fragmentContainer, com.disastermesh.app.ui.map.MapFragment.newInstance(dropMode = true))
                .commit()
            (requireActivity() as MainActivity).let { it.binding.bottomNav.selectedItemId = R.id.nav_map }
        }

        binding.btnResourcePlan.setOnClickListener {
            InventoryBottomSheet.newInstance(canEdit = true) { key, delta ->
                appViewModel.adjustInventory(key, delta)
            }.show(childFragmentManager, "inventory")
        }

        observeSignals()
    }

    private fun setZonesTab() {
        showingZones = true
        binding.rvSignals.adapter = zoneAdapter
        binding.tabZones.setTextColor(requireContext().getColor(R.color.authority))
        binding.tabZones.setBackgroundColor(requireContext().getColor(R.color.authority_tint))
        binding.tabAllSignals.setTextColor(requireContext().getColor(R.color.text_muted))
        binding.tabAllSignals.setBackgroundColor(requireContext().getColor(R.color.ink_800))
    }

    private fun setAllSignalsTab() {
        showingZones = false
        binding.rvSignals.adapter = signalAdapter
        binding.tabAllSignals.setTextColor(requireContext().getColor(R.color.authority))
        binding.tabAllSignals.setBackgroundColor(requireContext().getColor(R.color.authority_tint))
        binding.tabZones.setTextColor(requireContext().getColor(R.color.text_muted))
        binding.tabZones.setBackgroundColor(requireContext().getColor(R.color.ink_800))
    }

    private fun observeSignals() {
        viewLifecycleOwner.lifecycleScope.launch {
            appViewModel.signals.collectLatest { entities ->
                val signals = entities.map { it.toDomain() }

                binding.tvStatTotal.text        = signals.size.toString()
                binding.tvStatCritical.text     = signals.count { it.priority == SignalPriority.CRITICAL }.toString()
                binding.tvStatInProgress.text   = signals.count {
                    it.status == SignalStatus.IN_PROGRESS || it.status == SignalStatus.ACKNOWLEDGED
                }.toString()
                binding.tvStatResolvedAuth.text = signals.count { it.status == SignalStatus.RESOLVED }.toString()

                val active   = signals.filter { it.status != SignalStatus.RESOLVED && it.status != SignalStatus.EXPIRED }
                val clusters = LocationClusterer.cluster(active)

                binding.tvNoZones.visibility = if (clusters.isEmpty()) View.VISIBLE else View.GONE
                binding.rvSignals.visibility = if (clusters.isEmpty()) View.GONE    else View.VISIBLE

                if (showingZones) zoneAdapter.submitList(clusters)
                signalAdapter.submitList(signals)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
