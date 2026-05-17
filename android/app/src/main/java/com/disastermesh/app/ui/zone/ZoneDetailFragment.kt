package com.disastermesh.app.ui.zone

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.disastermesh.app.R
import com.disastermesh.app.adapter.SignalAdapter
import com.disastermesh.app.ai.GemmaClient
import com.disastermesh.app.ai.PromptTemplates
import com.disastermesh.app.databinding.FragmentZoneDetailBinding
import com.disastermesh.app.db.AppDatabase
import com.disastermesh.app.model.Signal
import com.disastermesh.app.model.SignalPriority
import com.disastermesh.app.model.SignalStatus
import com.disastermesh.app.ui.AppViewModel
import com.disastermesh.app.ui.MainActivity
import com.disastermesh.app.ui.sheet.AssignTicketBottomSheet
import com.disastermesh.app.ui.sheet.AssignZoneBottomSheet
import com.disastermesh.app.ui.sheet.SignalDetailBottomSheet
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class ZoneDetailFragment : Fragment() {

    private enum class ZoneFilter { ALL, OPEN, AFFECTED, CRITICAL, FAILED }

    private var _binding: FragmentZoneDetailBinding? = null
    private val binding get() = _binding!!

    private val appViewModel: AppViewModel by activityViewModels()

    private var allZoneSignals: List<Signal> = emptyList()
    private var currentFilter = ZoneFilter.ALL
    private var analysing = false

    private val signalAdapter = SignalAdapter { signal ->
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
                ).show(childFragmentManager, "signal_detail")
            }
            SignalStatus.RESOLVED, SignalStatus.EXPIRED,
            SignalStatus.REJECTED, SignalStatus.CANCELLED, SignalStatus.FAILED -> {
                SignalDetailBottomSheet.newInstance(
                    signal = signal,
                    onAction = { _: Signal, _: SignalDetailBottomSheet.TicketAction -> }
                ).show(childFragmentManager, "signal_detail_terminal")
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentZoneDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val areaLabel = arguments?.getString(ARG_AREA_LABEL) ?: "Area"
        val signalIds = arguments?.getStringArrayList(ARG_SIGNAL_IDS) ?: arrayListOf()

        binding.tvZoneDetailLabel.text = areaLabel
        binding.rvZoneSignals.layoutManager = LinearLayoutManager(requireContext())
        binding.rvZoneSignals.adapter = signalAdapter

        binding.btnZoneBack.setOnClickListener {
            requireActivity().supportFragmentManager.popBackStack()
        }

        binding.btnGemmaZone.setOnClickListener {
            if (analysing) return@setOnClickListener
            runZoneAnalysis(areaLabel)
        }

        binding.btnResolveZone.setOnClickListener {
            val svc = (requireActivity() as MainActivity).meshService
            if (svc == null) {
                Toast.makeText(requireContext(), "Mesh service not ready yet.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val openSignals = allZoneSignals.filter { !it.status.isTerminal }
            if (openSignals.isEmpty()) {
                Toast.makeText(requireContext(), "No open tickets in this zone.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            AssignZoneBottomSheet.newInstance(areaLabel, openSignals) { assignments ->
                svc.assignZoneSignalsToVolunteers(assignments)
            }.show(childFragmentManager, "assign_zone")
        }

        // Stat tile click → filter
        binding.statTotal.setOnClickListener    { setFilter(ZoneFilter.ALL) }
        binding.statOpen.setOnClickListener     { setFilter(ZoneFilter.OPEN) }
        binding.statAffected.setOnClickListener { setFilter(ZoneFilter.AFFECTED) }
        binding.statCritical.setOnClickListener { setFilter(ZoneFilter.CRITICAL) }
        binding.statFailed.setOnClickListener   { setFilter(ZoneFilter.FAILED) }

        observeZoneSignals(signalIds)
    }

    private fun setFilter(filter: ZoneFilter) {
        currentFilter = filter
        applyFilter()
        updateFilterHighlight()
    }

    private fun applyFilter() {
        val filtered = when (currentFilter) {
            ZoneFilter.ALL      -> allZoneSignals
            ZoneFilter.OPEN     -> allZoneSignals.filter { !it.status.isTerminal }
            ZoneFilter.AFFECTED -> allZoneSignals.filter { (it.peopleCount ?: 0) > 0 }
            ZoneFilter.CRITICAL -> allZoneSignals.filter { it.priority == SignalPriority.CRITICAL }
            ZoneFilter.FAILED   -> allZoneSignals.filter {
                it.status == SignalStatus.FAILED ||
                it.status == SignalStatus.REJECTED ||
                it.status == SignalStatus.CANCELLED
            }
        }
        signalAdapter.submitList(filtered)
        val label = when (currentFilter) {
            ZoneFilter.ALL      -> "${filtered.size} SIGNAL${if (filtered.size != 1) "S" else ""} IN THIS AREA"
            ZoneFilter.OPEN     -> "${filtered.size} OPEN SIGNAL${if (filtered.size != 1) "S" else ""}"
            ZoneFilter.AFFECTED -> "${filtered.size} SIGNAL${if (filtered.size != 1) "S" else ""} WITH AFFECTED PEOPLE"
            ZoneFilter.CRITICAL -> "${filtered.size} CRITICAL SIGNAL${if (filtered.size != 1) "S" else ""}"
            ZoneFilter.FAILED   -> "${filtered.size} FAILED/CANCELLED SIGNAL${if (filtered.size != 1) "S" else ""}"
        }
        binding.tvZoneSignalCount.text = label
    }

    private fun updateFilterHighlight() {
        val activeColor   = requireContext().getColor(R.color.authority_tint)
        val inactiveColor = Color.TRANSPARENT
        binding.statTotal.setBackgroundColor(    if (currentFilter == ZoneFilter.ALL)      activeColor else inactiveColor)
        binding.statOpen.setBackgroundColor(     if (currentFilter == ZoneFilter.OPEN)     activeColor else inactiveColor)
        binding.statAffected.setBackgroundColor( if (currentFilter == ZoneFilter.AFFECTED) activeColor else inactiveColor)
        binding.statCritical.setBackgroundColor( if (currentFilter == ZoneFilter.CRITICAL) activeColor else inactiveColor)
        binding.statFailed.setBackgroundColor(   if (currentFilter == ZoneFilter.FAILED)   activeColor else inactiveColor)
    }

    private fun runZoneAnalysis(areaLabel: String) {
        if (GemmaClient.status != GemmaClient.Status.READY) {
            Toast.makeText(
                requireContext(),
                "AI model not loaded — open the AI tab to load it first.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val active = allZoneSignals.filter { !it.status.isTerminal }
        if (active.isEmpty()) {
            Toast.makeText(requireContext(), "No active signals to analyse.", Toast.LENGTH_SHORT).show()
            return
        }

        setBtnState(loading = true)

        GemmaClient.generate(
            prompt            = PromptTemplates.zoneAnalysis(areaLabel, allZoneSignals),
            systemInstruction = PromptTemplates.ZONE_ANALYSIS_SYSTEM_INSTRUCTION,
            onResult          = { result ->
                if (!isAdded || _binding == null) return@generate
                setBtnState(loading = false)
                ZoneAnalysisBottomSheet.newInstance(areaLabel, result)
                    .show(childFragmentManager, "zone_analysis")
            },
            onError           = { err ->
                if (!isAdded || _binding == null) return@generate
                setBtnState(loading = false)
                Toast.makeText(requireContext(), "Analysis failed: $err", Toast.LENGTH_LONG).show()
            }
        )
    }

    private fun setBtnState(loading: Boolean) {
        analysing = loading
        binding.btnGemmaZone.isClickable = !loading
        binding.tvGemmaZoneBtnLabel.text = if (loading) "ANALYSING…" else "GEMMA ZONE ANALYSIS"
        val dotColor = requireContext().getColor(
            if (loading) R.color.ai_loading else R.color.ai_ready
        )
        binding.aiDotZone.setBackgroundColor(dotColor)
        binding.tvGemmaZoneBtnLabel.setTextColor(dotColor)
    }

    private fun observeZoneSignals(signalIds: List<String>) {
        val db = AppDatabase.getInstance(requireContext())
        viewLifecycleOwner.lifecycleScope.launch {
            db.signalDao().observeAll().map { entities ->
                entities
                    .filter { it.id in signalIds }
                    .map { it.toDomain() }
                    .sortedWith(compareByDescending {
                        when (it.priority) {
                            SignalPriority.CRITICAL -> 3
                            SignalPriority.HIGH     -> 2
                            SignalPriority.NORMAL   -> 1
                            SignalPriority.LOW      -> 0
                        }
                    })
            }.collectLatest { signals ->
                allZoneSignals = signals

                val open     = signals.count { !it.status.isTerminal }
                val affected = signals.mapNotNull { it.peopleCount }.sum()
                val critical = signals.count { it.priority == SignalPriority.CRITICAL }
                val failed   = signals.count {
                    it.status == SignalStatus.FAILED ||
                    it.status == SignalStatus.REJECTED ||
                    it.status == SignalStatus.CANCELLED
                }

                binding.tvZoneStatTotal.text    = signals.size.toString()
                binding.tvZoneStatOpen.text     = open.toString()
                binding.tvZoneStatAffected.text = if (affected > 0) affected.toString() else "—"
                binding.tvZoneStatCritical.text = critical.toString()
                binding.tvZoneStatFailed.text   = failed.toString()

                applyFilter()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val ARG_AREA_LABEL = "area_label"
        private const val ARG_SIGNAL_IDS = "signal_ids"

        fun newInstance(areaLabel: String, signalIds: ArrayList<String>) =
            ZoneDetailFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_AREA_LABEL, areaLabel)
                    putStringArrayList(ARG_SIGNAL_IDS, signalIds)
                }
            }
    }
}
