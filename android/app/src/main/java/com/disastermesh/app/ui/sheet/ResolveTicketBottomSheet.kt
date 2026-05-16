package com.disastermesh.app.ui.sheet

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.disastermesh.app.R
import com.disastermesh.app.databinding.BottomSheetResolveTicketBinding
import com.disastermesh.app.model.Signal
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import org.json.JSONObject

class ResolveTicketBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetResolveTicketBinding? = null
    private val binding get() = _binding!!

    private var signal: Signal? = null
    /** true = RESOLVED, false = UNRESOLVABLE (refunds inventory) */
    private var onDecision: ((Boolean) -> Unit)? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = BottomSheetResolveTicketBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val s = signal ?: return

        binding.tvResolveCategory.text = "${s.category.icon} ${s.category.label} · ${s.priority.name}"
        binding.tvResolveMessage.text  = s.message.take(120)

        if (s.assignedVolunteerName != null) {
            binding.tvAssignedVol.text     = s.assignedVolunteerName
            binding.containerAssigned.visibility = View.VISIBLE
        }

        // Build inventory consumed list
        val inv = s.inventoryAllocated
        if (!inv.isNullOrBlank()) {
            try {
                val json = JSONObject(inv)
                val sb   = StringBuilder()
                json.keys().forEach { key -> sb.append("$key: ${json.getInt(key)}\n") }
                binding.tvInventoryConsumed.text = sb.toString().trimEnd()
                binding.containerInventoryConsumed.visibility = View.VISIBLE
            } catch (_: Exception) {}
        }

        binding.btnClose.setOnClickListener { dismiss() }
        binding.btnConfirmResolve.setOnClickListener { onDecision?.invoke(true);  dismiss() }
        binding.btnMarkUnresolvable.setOnClickListener { onDecision?.invoke(false); dismiss() }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        fun newInstance(signal: Signal, onDecision: (Boolean) -> Unit) =
            ResolveTicketBottomSheet().apply {
                this.signal     = signal
                this.onDecision = onDecision
            }
    }
}
