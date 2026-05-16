package com.disastermesh.app.ui.sheet

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import com.disastermesh.app.R
import com.disastermesh.app.databinding.BottomSheetAssignTicketBinding
import com.disastermesh.app.db.AppDatabase
import com.disastermesh.app.db.entities.InventoryEntity
import com.disastermesh.app.model.Signal
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.json.JSONObject

class AssignTicketBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetAssignTicketBinding? = null
    private val binding get() = _binding!!

    private var signal: Signal? = null
    private var inventorySnapshot: List<InventoryEntity> = emptyList()
    private var onAssign: ((volunteerId: String, volunteerName: String, inventoryJson: String) -> Unit)? = null

    // Populated dynamically from the live peer list; never hardcoded.
    private var volunteers: List<Pair<String, String>> = emptyList()
    private var selectedVolunteerIndex = 0

    private val quantities = mutableMapOf<String, Int>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = BottomSheetAssignTicketBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val s = signal ?: return

        binding.tvAssignSignalSummary.text = "${s.category.icon} ${s.category.label} · ${s.senderName}"
        binding.tvAssignMessage.text = s.message.take(80)
        binding.btnClose.setOnClickListener { dismiss() }

        observeVolunteers()
        setupVolunteerPicker()
        setupInventoryInputs()

        binding.btnConfirmAssign.setOnClickListener { doAssign() }
    }

    private fun observeVolunteers() {
        viewLifecycleOwner.lifecycleScope.launch {
            AppDatabase.getInstance(requireContext())
                .peerDao()
                .observeConnected()
                .collectLatest { peers ->
                    volunteers = peers
                        .filter { it.role.equals("VOLUNTEER", ignoreCase = true) }
                        .map { it.nodeId to it.name }
                    selectedVolunteerIndex = 0
                    updateVolunteerDisplay()
                }
        }
    }

    private fun setupVolunteerPicker() {
        updateVolunteerDisplay()
        binding.btnVolPrev.setOnClickListener {
            if (volunteers.isEmpty()) return@setOnClickListener
            selectedVolunteerIndex = (selectedVolunteerIndex - 1 + volunteers.size) % volunteers.size
            updateVolunteerDisplay()
        }
        binding.btnVolNext.setOnClickListener {
            if (volunteers.isEmpty()) return@setOnClickListener
            selectedVolunteerIndex = (selectedVolunteerIndex + 1) % volunteers.size
            updateVolunteerDisplay()
        }
    }

    private fun updateVolunteerDisplay() {
        binding.tvSelectedVolunteer.text =
            volunteers.getOrNull(selectedVolunteerIndex)?.second ?: "No volunteers online"
    }

    private fun setupInventoryInputs() {
        val inflater = LayoutInflater.from(requireContext())
        inventorySnapshot.forEach { item ->
            quantities[item.key] = 0
            val row = inflater.inflate(R.layout.item_inventory_input_row, binding.containerInventoryInputs, false)
            row.findViewById<TextView>(R.id.tvInputLabel).text = "${item.label} (${item.count} avail)"
            val etQty = row.findViewById<android.widget.EditText>(R.id.etQty)
            etQty.addTextChangedListener { text ->
                val v = text.toString().toIntOrNull() ?: 0
                quantities[item.key] = minOf(v, item.count)
                if (v > item.count) etQty.error = "Max ${item.count}"
            }
            binding.containerInventoryInputs.addView(row)
        }
    }

    private fun doAssign() {
        val (volId, volName) = volunteers.getOrNull(selectedVolunteerIndex) ?: return
        if (volId.isBlank()) return  // sentinel — no volunteers online
        val json = JSONObject()
        quantities.filter { it.value > 0 }.forEach { (k, v) -> json.put(k, v) }
        onAssign?.invoke(volId, volName, json.toString())
        dismiss()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        fun newInstance(
            signal: Signal,
            inventory: List<InventoryEntity>,
            onAssign: (String, String, String) -> Unit
        ) = AssignTicketBottomSheet().apply {
            this.signal            = signal
            this.inventorySnapshot = inventory
            this.onAssign          = onAssign
        }
    }
}
