package com.disastermesh.app.ui.zone

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
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
import com.disastermesh.app.ui.MainActivity
import com.disastermesh.app.ui.sheet.SignalDetailBottomSheet
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class ZoneDetailFragment : Fragment() {

    private var _binding: FragmentZoneDetailBinding? = null
    private val binding get() = _binding!!

    private var currentSignals: List<Signal> = emptyList()
    private var analysing = false

    private val signalAdapter = SignalAdapter { signal ->
        SignalDetailBottomSheet.newInstance(signal) { s, newStatus ->
            (requireActivity() as MainActivity).meshService?.updateSignalStatus(s.id, newStatus)
        }.show(childFragmentManager, "signal_detail")
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
            // TODO: resolve all open signals in this zone
        }

        observeZoneSignals(signalIds)
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

        val active = currentSignals.filter {
            it.status != SignalStatus.RESOLVED && it.status != SignalStatus.EXPIRED
        }
        if (active.isEmpty()) {
            Toast.makeText(requireContext(), "No active signals to analyse.", Toast.LENGTH_SHORT).show()
            return
        }

        setBtnState(loading = true)

        GemmaClient.generate(
            prompt            = PromptTemplates.zoneAnalysis(areaLabel, currentSignals),
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
                currentSignals = signals
                signalAdapter.submitList(signals)

                val open     = signals.count { it.status != SignalStatus.RESOLVED && it.status != SignalStatus.EXPIRED }
                val affected = signals.mapNotNull { it.peopleCount }.sum()
                val critical = signals.count { it.priority == SignalPriority.CRITICAL }

                binding.tvZoneStatTotal.text    = signals.size.toString()
                binding.tvZoneStatOpen.text     = open.toString()
                binding.tvZoneStatAffected.text = if (affected > 0) affected.toString() else "—"
                binding.tvZoneStatCritical.text = critical.toString()
                binding.tvZoneSignalCount.text  =
                    "${signals.size} SIGNAL${if (signals.size != 1) "S" else ""} IN THIS AREA"
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
