package com.disastermesh.app.ui.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.disastermesh.app.adapter.SignalAdapter
import com.disastermesh.app.databinding.FragmentHomeVolunteerBinding
import com.disastermesh.app.model.SignalStatus
import com.disastermesh.app.ui.AppViewModel
import com.disastermesh.app.ui.MainActivity
import com.disastermesh.app.ui.sheet.InventoryBottomSheet
import com.disastermesh.app.ui.sheet.SignalDetailBottomSheet
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class VolunteerHomeFragment : Fragment() {

    private var _binding: FragmentHomeVolunteerBinding? = null
    private val binding get() = _binding!!

    private val appViewModel: AppViewModel by activityViewModels()

    private val signalAdapter = SignalAdapter { signal ->
        SignalDetailBottomSheet.newInstance(signal) { s, newStatus ->
            (requireActivity() as MainActivity).meshService?.updateSignalStatus(s.id, newStatus)
        }.show(childFragmentManager, "signal_detail")
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

        binding.btnInventory.setOnClickListener {
            InventoryBottomSheet.newInstance(canEdit = true) { key, delta ->
                appViewModel.adjustInventory(key, delta)
            }.show(childFragmentManager, "inventory")
        }

        observeSignals()
    }

    private fun observeSignals() {
        viewLifecycleOwner.lifecycleScope.launch {
            appViewModel.signals.collectLatest { entities ->
                val signals  = entities.map { it.toDomain() }
                val active   = signals.count { it.status == SignalStatus.NEW || it.status == SignalStatus.ACKNOWLEDGED }
                val nearby   = signals.count { it.latitude != null }
                val resolved = signals.count { it.status == SignalStatus.RESOLVED }

                binding.tvStatActive.text   = active.toString()
                binding.tvStatNearby.text   = nearby.toString()
                binding.tvStatResolved.text = resolved.toString()

                val actionable = signals.filter {
                    it.status != SignalStatus.RESOLVED && it.status != SignalStatus.EXPIRED
                }
                signalAdapter.submitList(actionable)
                binding.tvNoTasks.visibility = if (actionable.isEmpty()) View.VISIBLE else View.GONE
                binding.rvSignals.visibility = if (actionable.isEmpty()) View.GONE    else View.VISIBLE
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
