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
import com.disastermesh.app.db.entities.PeerEntity
import com.disastermesh.app.mesh.MeshService
import com.disastermesh.app.model.Signal
import com.disastermesh.app.model.SignalCategory
import com.disastermesh.app.model.SignalPriority
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.json.JSONObject

class AssignZoneBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetAssignTicketBinding? = null
    private val binding get() = _binding!!

    private var areaLabel: String = "Zone"
    private var signals: List<Signal> = emptyList()
    private var onAssign: ((List<MeshService.ZoneTicketAssignment>) -> Unit)? = null

    private var volunteers: List<Pair<String, String>> = emptyList()
    private val selectedVolunteerIds = mutableSetOf<String>()
    private val quantities = mutableMapOf<String, Int>()
    private var inventoryItems: List<InventoryEntity> = emptyList()
    private var inventorySuggestionApplied = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = BottomSheetAssignTicketBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.tvAssignSignalSummary.text = "$areaLabel · ${signals.size} OPEN TICKET${if (signals.size == 1) "" else "S"}"
        binding.tvAssignMessage.text = signals.joinToString("\n") { signal ->
            "${signal.category.label} / ${signal.priority.name}: ${signal.message.take(56)}"
        }.take(260)
        binding.tvResolutionStatus.text = "Preparing zone assignment distribution..."
        binding.btnClose.setOnClickListener { dismiss() }
        binding.etInstructions.setText("Zone batch assignment for $areaLabel.")

        refreshConfirmButton()
        observeVolunteers()
        observeInventory()

        binding.btnConfirmAssign.setOnClickListener { doAssign() }
    }

    private fun observeVolunteers() {
        viewLifecycleOwner.lifecycleScope.launch {
            val peerDao = AppDatabase.getInstance(requireContext()).peerDao()
            peerDao.observeAll()
                .collectLatest { peers ->
                    volunteers = peers
                        .filter { it.isVolunteerPeer() }
                        .distinctBy { it.nodeId }
                        .map { it.nodeId to it.name }

                    if (selectedVolunteerIds.isEmpty()) {
                        selectedVolunteerIds.addAll(volunteers.map { it.first })
                    } else {
                        selectedVolunteerIds.retainAll(volunteers.map { it.first }.toSet())
                    }
                    rebuildVolunteerCheckboxes()
                    refreshDistributionPreview()
                }
        }
    }

    private fun PeerEntity.isVolunteerPeer(): Boolean =
        role.equals("VOLUNTEER", ignoreCase = true) ||
        role.equals("VOL", ignoreCase = true) ||
        role.equals("Volunteer", ignoreCase = true)

    private fun rebuildVolunteerCheckboxes() {
        val container = binding.containerVolunteers
        container.removeAllViews()

        if (volunteers.isEmpty()) {
            binding.tvNoVolunteers.visibility = View.VISIBLE
            container.visibility = View.GONE
            refreshConfirmButton()
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
                refreshDistributionPreview()
            }
            container.addView(row)
        }
        refreshConfirmButton()
    }

    private fun observeInventory() {
        viewLifecycleOwner.lifecycleScope.launch {
            AppDatabase.getInstance(requireContext())
                .inventoryDao()
                .observeAll()
                .collectLatest { items ->
                    inventoryItems = items.filter { it.count > 0 }
                    if (!inventorySuggestionApplied) {
                        quantities.clear()
                        quantities.putAll(suggestInventoryTotals(inventoryItems))
                        inventorySuggestionApplied = true
                    }
                    rebuildInventoryInputs()
                    refreshDistributionPreview()
                }
        }
    }

    private fun suggestInventoryTotals(items: List<InventoryEntity>): Map<String, Int> {
        val active = signals.size
        if (active == 0) return emptyMap()

        val suggestions = mutableMapOf<String, Int>()
        fun putIfPresent(key: String, qty: Int) {
            val item = items.firstOrNull { it.key == key } ?: return
            suggestions[key] = qty.coerceIn(0, item.count)
        }

        val criticalOrMedical = signals.count {
            it.priority == SignalPriority.CRITICAL || it.category == SignalCategory.MEDICAL
        }
        val affected = signals.sumOf { it.peopleCount ?: 0 }.coerceAtLeast(active)

        putIfPresent("water", affected.coerceAtMost(active * 2))
        putIfPresent("medkit", criticalOrMedical.coerceAtLeast(if (criticalOrMedical > 0) 1 else 0))
        putIfPresent("food", signals.count { it.category == SignalCategory.RESOURCE }.coerceAtMost(active))
        putIfPresent("blanket", signals.count { it.category == SignalCategory.SAFETY }.coerceAtMost(active))
        return suggestions.filterValues { it > 0 }
    }

    private fun rebuildInventoryInputs() {
        val previousQuantities = quantities.toMap()
        binding.containerInventoryInputs.removeAllViews()
        quantities.clear()

        if (inventoryItems.isEmpty()) {
            binding.tvNoInventory.visibility = View.VISIBLE
            return
        }
        binding.tvNoInventory.visibility = View.GONE

        val inflater = LayoutInflater.from(requireContext())
        inventoryItems.forEach { item ->
            quantities[item.key] = previousQuantities[item.key]?.coerceIn(0, item.count) ?: 0
            val row = inflater.inflate(R.layout.item_inventory_input_row, binding.containerInventoryInputs, false)
            row.findViewById<TextView>(R.id.tvInputLabel).text = "${item.label} (${item.count} avail total)"

            val etQty = row.findViewById<android.widget.EditText>(R.id.etQty)
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
                refreshDistributionPreview()
            }

            btnDec.setOnClickListener { setQty((quantities[item.key] ?: 0) - 1) }
            btnInc.setOnClickListener { setQty((quantities[item.key] ?: 0) + 1) }

            etQty.addTextChangedListener { text ->
                if (ignoring) return@addTextChangedListener
                val v = text.toString().toIntOrNull() ?: 0
                val clamped = v.coerceIn(0, item.count)
                quantities[item.key] = clamped
                refreshColors(clamped)
                refreshDistributionPreview()
            }

            setQty(quantities[item.key] ?: 0)
            binding.containerInventoryInputs.addView(row)
        }
    }

    private fun refreshConfirmButton() {
        val enabled = signals.isNotEmpty() && selectedVolunteerIds.isNotEmpty()
        binding.btnConfirmAssign.isEnabled = enabled
        binding.btnConfirmAssign.alpha = if (enabled) 1f else 0.4f
    }

    private fun refreshDistributionPreview() {
        if (_binding == null) return
        val selected = selectedVolunteers()
        if (signals.isEmpty()) {
            binding.tvResolutionStatus.text = "No open tickets in this zone."
            return
        }
        if (selected.isEmpty()) {
            binding.tvResolutionStatus.text = "No online volunteers selected. Select at least one volunteer."
            return
        }

        val assignments = buildAssignments(selected)
        val volunteerText = assignments.joinToString("\n") { assignment ->
            val signal = signals.firstOrNull { it.id == assignment.signalId }
            "${signal?.category?.label ?: "TICKET"} -> ${assignment.volunteerNames.joinToString(", ")}"
        }
        val inventoryText = quantities
            .filterValues { it > 0 }
            .entries
            .joinToString(", ") { (key, qty) ->
                val item = inventoryItems.firstOrNull { it.key == key }
                "${item?.label ?: key}: $qty"
            }
            .ifBlank { "No inventory selected" }

        binding.tvResolutionStatus.text = buildString {
            append("Zone distribution ready.\n")
            append("Inventory pool: $inventoryText\n")
            append(volunteerText)
        }
    }

    private fun selectedVolunteers(): List<Pair<String, String>> =
        volunteers.filter { (id, _) -> selectedVolunteerIds.contains(id) }

    private fun buildAssignments(selected: List<Pair<String, String>>): List<MeshService.ZoneTicketAssignment> {
        if (selected.isEmpty()) return emptyList()
        val inventoryBySignal = distributeInventory()
        val instructions = binding.etInstructions.text?.toString()?.trim().orEmpty()

        return signals.mapIndexed { index, signal ->
            val volunteer = selected[index % selected.size]
            val inventoryJson = JSONObject()
            inventoryBySignal[signal.id].orEmpty().forEach { (key, qty) ->
                if (qty > 0) inventoryJson.put(key, qty)
            }

            MeshService.ZoneTicketAssignment(
                signalId = signal.id,
                volunteerIds = listOf(volunteer.first),
                volunteerNames = listOf(volunteer.second),
                inventoryJson = inventoryJson.toString(),
                instructions = instructions.ifBlank {
                    "Zone assignment for $areaLabel. Carry listed inventory and update ticket status."
                }
            )
        }
    }

    private fun distributeInventory(): Map<String, Map<String, Int>> {
        val result = signals.associate { it.id to mutableMapOf<String, Int>() }
        if (signals.isEmpty()) return result

        val orderedSignals = signals.sortedWith(
            compareByDescending<Signal> {
                when (it.priority) {
                    SignalPriority.CRITICAL -> 3
                    SignalPriority.HIGH -> 2
                    SignalPriority.NORMAL -> 1
                    SignalPriority.LOW -> 0
                }
            }.thenByDescending { it.peopleCount ?: 0 }
        )

        quantities.filterValues { it > 0 }.forEach { (key, total) ->
            repeat(total) { index ->
                val signal = orderedSignals[index % orderedSignals.size]
                val perSignal = result.getValue(signal.id)
                perSignal[key] = (perSignal[key] ?: 0) + 1
            }
        }
        return result
    }

    private fun doAssign() {
        val selected = selectedVolunteers()
        if (selected.isEmpty()) return

        onAssign?.invoke(buildAssignments(selected))
        dismiss()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        fun newInstance(
            areaLabel: String,
            signals: List<Signal>,
            onAssign: (List<MeshService.ZoneTicketAssignment>) -> Unit
        ) = AssignZoneBottomSheet().apply {
            this.areaLabel = areaLabel
            this.signals = signals
            this.onAssign = onAssign
        }
    }
}
