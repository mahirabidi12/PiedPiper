package com.disastermesh.app.ui.sheet

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
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
    private var onAssign: ((volunteerIds: List<String>, volunteerNames: List<String>, inventoryJson: String, instructions: String) -> Unit)? = null

    // nodeId → displayName for online volunteers
    private var volunteers: List<Pair<String, String>> = emptyList()
    // Checked volunteer node IDs
    private val selectedVolunteerIds = mutableSetOf<String>()

    private val quantities = mutableMapOf<String, Int>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = BottomSheetAssignTicketBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val s = signal ?: return

        binding.tvAssignSignalSummary.text = "${s.category.icon} ${s.category.label} · ${s.senderName}"
        binding.tvAssignMessage.text = s.message.take(100)
        binding.btnClose.setOnClickListener { dismiss() }

        refreshConfirmButton()
        observeVolunteers()
        observeInventory()

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
                    rebuildVolunteerCheckboxes()
                }
        }
    }

    private fun rebuildVolunteerCheckboxes() {
        val container = binding.containerVolunteers
        container.removeAllViews()

        if (volunteers.isEmpty()) {
            binding.tvNoVolunteers.visibility = View.VISIBLE
            container.visibility = View.GONE
            return
        }

        binding.tvNoVolunteers.visibility = View.GONE
        container.visibility = View.VISIBLE

        val inflater = LayoutInflater.from(requireContext())
        volunteers.forEach { (nodeId, name) ->
            val row = inflater.inflate(R.layout.item_volunteer_check_row, container, false)
            val cb = row.findViewById<CheckBox>(R.id.cbVolunteer)
            cb.text = name
            cb.isChecked = selectedVolunteerIds.contains(nodeId)
            cb.setOnCheckedChangeListener { _, checked ->
                if (checked) selectedVolunteerIds.add(nodeId)
                else selectedVolunteerIds.remove(nodeId)
                refreshConfirmButton()
            }
            container.addView(row)
        }
    }

    private fun observeInventory() {
        viewLifecycleOwner.lifecycleScope.launch {
            AppDatabase.getInstance(requireContext())
                .inventoryDao()
                .observeAll()
                .collectLatest { items ->
                    rebuildInventoryInputs(items.filter { it.count > 0 })
                }
        }
    }

    private fun rebuildInventoryInputs(items: List<InventoryEntity>) {
        binding.containerInventoryInputs.removeAllViews()
        quantities.clear()

        if (items.isEmpty()) {
            binding.tvNoInventory.visibility = View.VISIBLE
            return
        }
        binding.tvNoInventory.visibility = View.GONE

        val inflater = LayoutInflater.from(requireContext())
        items.forEach { item ->
            quantities[item.key] = 0
            val row = inflater.inflate(R.layout.item_inventory_input_row, binding.containerInventoryInputs, false)
            row.findViewById<TextView>(R.id.tvInputLabel).text = "${item.label} (${item.count} avail)"

            val etQty  = row.findViewById<android.widget.EditText>(R.id.etQty)
            val btnDec = row.findViewById<TextView>(R.id.btnDecrement)
            val btnInc = row.findViewById<TextView>(R.id.btnIncrement)
            var ignoring = false

            fun refreshColors(q: Int) {
                etQty.setTextColor(requireContext().getColor(
                    if (q > 0) R.color.volunteer else R.color.text_primary
                ))
                btnDec.alpha = if (q > 0) 1f else 0.3f
                btnInc.alpha = if (q < item.count) 1f else 0.3f
            }

            fun setQty(q: Int) {
                val clamped = q.coerceIn(0, item.count)
                quantities[item.key] = clamped
                ignoring = true
                etQty.setText(clamped.toString())
                ignoring = false
                refreshColors(clamped)
            }

            btnDec.setOnClickListener { setQty((quantities[item.key] ?: 0) - 1) }
            btnInc.setOnClickListener { setQty((quantities[item.key] ?: 0) + 1) }

            etQty.addTextChangedListener { text ->
                if (ignoring) return@addTextChangedListener
                val v = text.toString().toIntOrNull() ?: 0
                val clamped = v.coerceIn(0, item.count)
                quantities[item.key] = clamped
                refreshColors(clamped)
            }

            setQty(0)
            binding.containerInventoryInputs.addView(row)
        }
    }

    private fun refreshConfirmButton() {
        val enabled = selectedVolunteerIds.isNotEmpty()
        binding.btnConfirmAssign.isEnabled = enabled
        binding.btnConfirmAssign.alpha     = if (enabled) 1f else 0.4f
    }

    private fun doAssign() {
        val selected = volunteers.filter { (id, _) -> selectedVolunteerIds.contains(id) }
        if (selected.isEmpty()) return  // button should already be disabled; guard only

        val inventoryJson = JSONObject()
        quantities.filter { it.value > 0 }.forEach { (k, v) -> inventoryJson.put(k, v) }

        val instructions = binding.etInstructions.text?.toString()?.trim() ?: ""

        onAssign?.invoke(
            selected.map { it.first },
            selected.map { it.second },
            inventoryJson.toString(),
            instructions
        )
        dismiss()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        fun newInstance(
            signal: Signal,
            onAssign: (List<String>, List<String>, String, String) -> Unit
        ) = AssignTicketBottomSheet().apply {
            this.signal   = signal
            this.onAssign = onAssign
        }
    }
}
