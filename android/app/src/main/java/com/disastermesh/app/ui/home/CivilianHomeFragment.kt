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
import com.disastermesh.app.ai.GemmaClient
import com.disastermesh.app.core.NodeIdentity
import com.disastermesh.app.databinding.FragmentHomeCivilianBinding
import com.disastermesh.app.model.SignalStatus
import com.disastermesh.app.ui.AppViewModel
import com.disastermesh.app.ui.MainActivity
import com.disastermesh.app.ui.sheet.ComposeSignalBottomSheet
import com.disastermesh.app.ui.sheet.SignalDetailBottomSheet
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class CivilianHomeFragment : Fragment() {

    private var _binding: FragmentHomeCivilianBinding? = null
    private val binding get() = _binding!!

    private val appViewModel: AppViewModel by activityViewModels()

    private val queuedAdapter = SignalAdapter { signal ->
        SignalDetailBottomSheet.newInstance(
            signal = signal,
            onAction = { _: com.disastermesh.app.model.Signal, _: SignalDetailBottomSheet.TicketAction -> }
        ).show(childFragmentManager, "signal_detail_queued")
    }

    private val signalAdapter = SignalAdapter { signal ->
        SignalDetailBottomSheet.newInstance(
            signal = signal,
            onAction = { _: com.disastermesh.app.model.Signal, _: SignalDetailBottomSheet.TicketAction -> }
        ).show(childFragmentManager, "signal_detail")
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeCivilianBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.rvQueuedSignals.layoutManager = LinearLayoutManager(requireContext())
        binding.rvQueuedSignals.adapter = queuedAdapter

        binding.rvMySignals.layoutManager = LinearLayoutManager(requireContext())
        binding.rvMySignals.adapter = signalAdapter

        binding.btnRaiseSignal.setOnClickListener { openCompose(null) }
        observeAiStatus()
        observeSignals()
    }

    private fun openCompose(prefill: String?) {
        val meshConnected = (requireActivity() as MainActivity).meshService?.peerCount?.value ?: 0 > 0
        ComposeSignalBottomSheet.newInstance(prefill, meshConnected) { signal ->
            (requireActivity() as MainActivity).meshService?.sendSignal(signal)
        }.show(childFragmentManager, "compose_signal")
    }

    private fun observeAiStatus() {
        GemmaClient.warmUp(requireContext()) { status ->
            val (label, colorRes) = when (status) {
                GemmaClient.Status.READY   -> "AI · GEMMA 4 READY" to R.color.ai_ready
                GemmaClient.Status.LOADING -> "AI · LOADING…"      to R.color.ai_loading
                GemmaClient.Status.ABSENT  -> "AI · NOT LOADED"     to R.color.ai_absent
                GemmaClient.Status.ERROR   -> "AI · ERROR"          to R.color.priority_critical
            }
            if (isAdded) {
                binding.tvAiIndicator.text = label
                binding.tvAiIndicator.setTextColor(requireContext().getColor(colorRes))
            }
        }
    }

    private fun observeSignals() {
        val nodeId = NodeIdentity.get(requireContext())
        viewLifecycleOwner.lifecycleScope.launch {
            appViewModel.signals.collectLatest { entities ->
                val mine   = entities.filter { it.senderNodeId == nodeId }.map { it.toDomain() }
                val queued = mine.filter { it.status == SignalStatus.QUEUED }
                val active = mine.filter { it.status != SignalStatus.QUEUED }

                queuedAdapter.submitList(queued)
                binding.tvQueuedSection.visibility  = if (queued.isEmpty()) View.GONE else View.VISIBLE
                binding.rvQueuedSignals.visibility  = if (queued.isEmpty()) View.GONE else View.VISIBLE

                signalAdapter.submitList(active)
                binding.tvNoSignals.visibility = if (active.isEmpty()) View.VISIBLE else View.GONE
                binding.rvMySignals.visibility = if (active.isEmpty()) View.GONE   else View.VISIBLE
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
