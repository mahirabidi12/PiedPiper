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
import com.disastermesh.app.model.Signal
import com.disastermesh.app.model.SignalPriority
import com.disastermesh.app.model.SignalStatus
import com.disastermesh.app.ui.AppViewModel
import com.disastermesh.app.ui.MainActivity
import com.disastermesh.app.ui.sheet.AssignTicketBottomSheet
import com.disastermesh.app.ui.sheet.BroadcastBottomSheet
import com.disastermesh.app.ui.inventory.InventoryFragment
import com.disastermesh.app.ui.sheet.SignalDetailBottomSheet
import com.disastermesh.app.ui.zone.ZoneDetailFragment
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class AuthorityHomeFragment : Fragment() {

    private var _binding: FragmentHomeAuthorityBinding? = null
    private val binding get() = _binding!!

    private enum class AuthFilter { ALL, CRITICAL, ACTIVE, RESOLVED }

    private val appViewModel: AppViewModel by activityViewModels()
    private var showingZones = true
    private var activeFilter = AuthFilter.ALL
    private var allSignalsCache: List<Signal> = emptyList()
    private var closedSignalsCache: List<Signal> = emptyList()

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

    private val signalAdapter = SignalAdapter(
        onCancel = { signal ->
            val svc = (requireActivity() as MainActivity).meshService ?: return@SignalAdapter
            AssignTicketBottomSheet.newInstance(signal) { volunteerIds, volunteerNames, inventoryJson, instructions ->
                svc.assignSignalToVolunteers(
                    signalId       = signal.id,
                    volunteerIds   = volunteerIds,
                    volunteerNames = volunteerNames,
                    inventoryJson  = inventoryJson,
                    instructions   = instructions
                )
            }.show(childFragmentManager, "assign_ticket_resolution")
        },
        onClick = { signal ->
        val svc = (requireActivity() as MainActivity).meshService ?: return@SignalAdapter

        when (signal.status) {
            SignalStatus.NEW, SignalStatus.ACKNOWLEDGED, SignalStatus.QUEUED -> {
                AssignTicketBottomSheet.newInstance(signal) { volunteerIds, volunteerNames, inventoryJson, instructions ->
                    svc.assignSignalToVolunteers(
                        signalId       = signal.id,
                        volunteerIds   = volunteerIds,
                        volunteerNames = volunteerNames,
                        inventoryJson  = inventoryJson,
                        instructions   = instructions
                    )
                }.show(childFragmentManager, "assign_ticket")
            }

            SignalStatus.ASSIGNED, SignalStatus.ACCEPTED,
            SignalStatus.WAITING_FOR_INVENTORY, SignalStatus.ON_HOLD,
            SignalStatus.IN_PROGRESS -> {
                SignalDetailBottomSheet.newInstance(
                    signal = signal,
                    onAction = { s: Signal, action: SignalDetailBottomSheet.TicketAction ->
                        when (action) {
                            SignalDetailBottomSheet.TicketAction.RESOLVE     -> svc.resolveTicket(s.id)
                            SignalDetailBottomSheet.TicketAction.CANCEL      -> svc.cancelTicket(s.id)
                            SignalDetailBottomSheet.TicketAction.FAIL        -> svc.failTicket(s.id)
                            SignalDetailBottomSheet.TicketAction.ACKNOWLEDGE -> svc.updateSignalStatus(s.id, SignalStatus.ACKNOWLEDGED)
                            SignalDetailBottomSheet.TicketAction.IN_ROUTE    -> svc.updateSignalStatus(s.id, SignalStatus.IN_PROGRESS)
                            else -> {}
                        }
                    }
                ).show(childFragmentManager, "ticket_detail")
            }

            SignalStatus.RESOLVED, SignalStatus.EXPIRED,
            SignalStatus.REJECTED, SignalStatus.CANCELLED, SignalStatus.FAILED -> {
                SignalDetailBottomSheet.newInstance(
                    signal = signal,
                    onAction = { _: Signal, _: SignalDetailBottomSheet.TicketAction -> }
                ).show(childFragmentManager, "signal_detail")
            }
        }
    })

    private val closedSignalAdapter = SignalAdapter { signal ->
        SignalDetailBottomSheet.newInstance(
            signal = signal,
            onAction = { _: Signal, _: SignalDetailBottomSheet.TicketAction -> }
        ).show(childFragmentManager, "signal_detail_closed")
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
        binding.rvClosedSignals.layoutManager = LinearLayoutManager(requireContext())
        binding.rvClosedSignals.adapter = closedSignalAdapter

        setZonesTab()

        binding.tabZones.setOnClickListener      { setZonesTab() }
        binding.tabAllSignals.setOnClickListener { setAllSignalsTab() }

        binding.statTotal.setOnClickListener    { setSignalFilter(AuthFilter.ALL) }
        binding.statCritical.setOnClickListener { setSignalFilter(AuthFilter.CRITICAL) }
        binding.statActive.setOnClickListener   { setSignalFilter(AuthFilter.ACTIVE) }
        binding.statResolved.setOnClickListener { setSignalFilter(AuthFilter.RESOLVED) }

        binding.btnBroadcast.setOnClickListener {
            BroadcastBottomSheet.newInstance { message ->
                (requireActivity() as MainActivity).meshService?.sendBroadcast(message)
            }.show(childFragmentManager, "broadcast")
        }

        binding.btnSafeZone.setOnClickListener {
            requireActivity().supportFragmentManager.beginTransaction()
                .replace(R.id.fragmentContainer, com.disastermesh.app.ui.map.MapFragment.newInstance(dropMode = true))
                .commit()
            (requireActivity() as MainActivity).let { it.binding.bottomNav.selectedItemId = R.id.nav_map }
        }

        binding.btnResourcePlan.setOnClickListener {
            requireActivity().supportFragmentManager.beginTransaction()
                .replace(R.id.fragmentContainer, InventoryFragment())
                .addToBackStack("inventory")
                .commit()
        }

        observeSignals()
    }

    private fun setZonesTab() {
        showingZones = true
        binding.rvSignals.adapter = zoneAdapter
        binding.sectionClosed.visibility = View.GONE
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
        applySignalFilter()
    }

    private fun setSignalFilter(filter: AuthFilter) {
        if (showingZones) setAllSignalsTab()   // auto-switch to all signals tab
        activeFilter = filter
        applySignalFilter()
        updateFilterHighlight()
    }

    private fun applySignalFilter() {
        val filtered = when (activeFilter) {
            AuthFilter.ALL      -> allSignalsCache
            AuthFilter.CRITICAL -> allSignalsCache.filter { it.priority == SignalPriority.CRITICAL }
            AuthFilter.ACTIVE   -> allSignalsCache.filter { !it.status.isTerminal }
            AuthFilter.RESOLVED -> allSignalsCache.filter { it.status == SignalStatus.RESOLVED }
        }
        val active = filtered.filter { !it.status.isTerminal }
        val closed = filtered.filter { it.status.isTerminal }
        signalAdapter.submitList(active)
        closedSignalAdapter.submitList(closed)
        binding.sectionClosed.visibility = if (closed.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun updateFilterHighlight() {
        val on  = requireContext().getColor(R.color.authority_tint)
        val off = android.graphics.Color.TRANSPARENT
        binding.statTotal.setBackgroundColor(    if (activeFilter == AuthFilter.ALL)      on else off)
        binding.statCritical.setBackgroundColor( if (activeFilter == AuthFilter.CRITICAL) on else off)
        binding.statActive.setBackgroundColor(   if (activeFilter == AuthFilter.ACTIVE)   on else off)
        binding.statResolved.setBackgroundColor( if (activeFilter == AuthFilter.RESOLVED) on else off)
    }

    private fun observeSignals() {
        viewLifecycleOwner.lifecycleScope.launch {
            appViewModel.signals.collectLatest { entities ->
                val signals = entities.map { it.toDomain() }

                val activeStatuses = setOf(
                    SignalStatus.NEW, SignalStatus.ACKNOWLEDGED,
                    SignalStatus.ASSIGNED, SignalStatus.ACCEPTED,
                    SignalStatus.IN_PROGRESS, SignalStatus.WAITING_FOR_INVENTORY,
                    SignalStatus.ON_HOLD
                )

                binding.tvStatTotal.text        = signals.size.toString()
                binding.tvStatCritical.text     = signals.count { it.priority == SignalPriority.CRITICAL }.toString()
                binding.tvStatInProgress.text   = signals.count { it.status in activeStatuses }.toString()
                binding.tvStatResolvedAuth.text = signals.count { it.status == SignalStatus.RESOLVED }.toString()

                allSignalsCache   = signals
                closedSignalsCache = signals.filter { it.status.isTerminal }

                // Cluster ALL signals so zone detail can show history;
                // only display zones that still have at least one active signal.
                val allClusters     = LocationClusterer.cluster(signals)
                val displayClusters = allClusters.filter { c -> c.signals.any { !it.status.isTerminal } }

                binding.tvNoZones.visibility = if (displayClusters.isEmpty()) View.VISIBLE else View.GONE

                if (showingZones) {
                    zoneAdapter.submitList(displayClusters)
                } else {
                    applySignalFilter()
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
