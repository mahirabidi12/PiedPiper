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
import com.disastermesh.app.adapter.SignalAdapter
import com.disastermesh.app.core.NodeIdentity
import com.disastermesh.app.databinding.FragmentHomeVolunteerBinding
import com.disastermesh.app.model.Signal
import com.disastermesh.app.model.SignalStatus
import com.disastermesh.app.ui.AppViewModel
import com.disastermesh.app.ui.MainActivity
import com.disastermesh.app.ui.inventory.InventoryFragment
import com.disastermesh.app.ui.sheet.SignalDetailBottomSheet
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class VolunteerHomeFragment : Fragment() {

    private var _binding: FragmentHomeVolunteerBinding? = null
    private val binding get() = _binding!!

    private enum class VolFilter { ALL, ACTIVE, IN_PROGRESS, RESOLVED }

    private val appViewModel: AppViewModel by activityViewModels()
    private var activeFilter = VolFilter.ALL
    private var allAssignedCache: List<Signal> = emptyList()

    private val signalAdapter = SignalAdapter { signal ->
        val svc = (requireActivity() as MainActivity).meshService
        SignalDetailBottomSheet.newInstance(
            signal = signal,
            onAction = { s: Signal, action: SignalDetailBottomSheet.TicketAction ->
                when (action) {
                    SignalDetailBottomSheet.TicketAction.ACCEPT     -> svc?.acceptTicket(s.id)
                    SignalDetailBottomSheet.TicketAction.REJECT     -> svc?.rejectTicket(s.id)
                    SignalDetailBottomSheet.TicketAction.START_WORK -> svc?.startTicket(s.id)
                    SignalDetailBottomSheet.TicketAction.RESOLVE    -> svc?.resolveTicket(s.id)
                    SignalDetailBottomSheet.TicketAction.FAIL       -> svc?.failTicket(s.id)
                    SignalDetailBottomSheet.TicketAction.CANCEL     -> svc?.cancelTicket(s.id)
                    else -> {}
                }
            }
        ).show(childFragmentManager, "signal_detail")
    }

    private val closedSignalAdapter = SignalAdapter { signal ->
        SignalDetailBottomSheet.newInstance(
            signal = signal,
            onAction = { _: Signal, _: SignalDetailBottomSheet.TicketAction -> }
        ).show(childFragmentManager, "signal_detail_closed")
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeVolunteerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.rvSignals.layoutManager = LinearLayoutManager(requireContext())
        binding.rvSignals.adapter = signalAdapter

        binding.rvClosedSignals.layoutManager = LinearLayoutManager(requireContext())
        binding.rvClosedSignals.adapter = closedSignalAdapter

        binding.statActive.setOnClickListener     { setFilter(VolFilter.ACTIVE) }
        binding.statInProgress.setOnClickListener { setFilter(VolFilter.IN_PROGRESS) }
        binding.statResolved.setOnClickListener   { setFilter(VolFilter.RESOLVED) }

        binding.btnInventory.setOnClickListener {
            requireActivity().supportFragmentManager.beginTransaction()
                .replace(R.id.fragmentContainer, InventoryFragment())
                .addToBackStack("inventory")
                .commit()
        }

        observeAssignedSignals()
    }

    private fun setFilter(filter: VolFilter) {
        activeFilter = filter
        applyFilter()
        updateFilterHighlight()
    }

    private fun applyFilter() {
        val all    = allAssignedCache
        val active = all.filter { !it.status.isTerminal }
        val closed = all.filter { it.status.isTerminal }

        val showActive: List<Signal>
        val showClosed: List<Signal>

        when (activeFilter) {
            VolFilter.ALL -> {
                showActive = active
                showClosed = closed
            }
            VolFilter.ACTIVE -> {
                showActive = active.filter {
                    it.status == SignalStatus.ASSIGNED || it.status == SignalStatus.ACCEPTED
                }
                showClosed = emptyList()
            }
            VolFilter.IN_PROGRESS -> {
                showActive = active.filter { it.status == SignalStatus.IN_PROGRESS }
                showClosed = emptyList()
            }
            VolFilter.RESOLVED -> {
                showActive = emptyList()
                showClosed = closed.filter { it.status == SignalStatus.RESOLVED }
            }
        }

        signalAdapter.submitList(showActive)
        binding.tvNoTasks.visibility = if (showActive.isEmpty() && showClosed.isEmpty()) View.VISIBLE else View.GONE
        binding.rvSignals.visibility = if (showActive.isEmpty()) View.GONE else View.VISIBLE

        closedSignalAdapter.submitList(showClosed)
        binding.sectionClosed.visibility = if (showClosed.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun updateFilterHighlight() {
        val on  = requireContext().getColor(R.color.volunteer_tint)
        val off = android.graphics.Color.TRANSPARENT
        binding.statActive.setBackgroundColor(     if (activeFilter == VolFilter.ACTIVE)      on else off)
        binding.statInProgress.setBackgroundColor( if (activeFilter == VolFilter.IN_PROGRESS) on else off)
        binding.statResolved.setBackgroundColor(   if (activeFilter == VolFilter.RESOLVED)    on else off)
    }

    private fun observeAssignedSignals() {
        val localNodeId = NodeIdentity.get(requireContext())

        viewLifecycleOwner.lifecycleScope.launch {
            appViewModel.assignedSignals(localNodeId).collectLatest { entities ->
                val signals = entities.map { it.toDomain() }
                allAssignedCache = signals

                val active = signals.filter { !it.status.isTerminal }
                val closed = signals.filter { it.status.isTerminal }

                binding.tvStatActive.text   = active.count {
                    it.status == SignalStatus.ASSIGNED || it.status == SignalStatus.ACCEPTED
                }.toString()
                binding.tvStatNearby.text   = active.count { it.status == SignalStatus.IN_PROGRESS }.toString()
                binding.tvStatResolved.text = closed.count { it.status == SignalStatus.RESOLVED }.toString()

                applyFilter()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
