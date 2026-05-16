package com.disastermesh.app.ui.inventory

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.disastermesh.app.R
import com.disastermesh.app.adapter.AuditLogAdapter
import com.disastermesh.app.adapter.InventoryAdapter
import com.disastermesh.app.core.NodeIdentity
import com.disastermesh.app.core.UserSession
import com.disastermesh.app.databinding.FragmentInventoryBinding
import com.disastermesh.app.model.Role
import com.disastermesh.app.ui.AppViewModel
import com.disastermesh.app.ui.MainActivity
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class InventoryFragment : Fragment() {

    private var _binding: FragmentInventoryBinding? = null
    private val binding get() = _binding!!

    private val appViewModel: AppViewModel by activityViewModels()

    private lateinit var inventoryAdapter: InventoryAdapter
    private lateinit var auditLogAdapter: AuditLogAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentInventoryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val session = UserSession.get(requireContext()) ?: return
        val canEdit = session.role == Role.AUTHORITY || session.role == Role.VOLUNTEER

        inventoryAdapter = InventoryAdapter(
            canEdit   = canEdit,
            canDelete = canEdit,
            onAdjust  = { key, delta -> adjustItem(key, delta) },
            onDelete  = { key -> confirmDelete(key) }
        )

        auditLogAdapter = AuditLogAdapter()

        binding.rvInventory.layoutManager = LinearLayoutManager(requireContext())
        binding.rvInventory.adapter = inventoryAdapter
        binding.rvInventory.isNestedScrollingEnabled = false

        binding.rvAuditLog.layoutManager = LinearLayoutManager(requireContext())
        binding.rvAuditLog.adapter = auditLogAdapter
        binding.rvAuditLog.isNestedScrollingEnabled = false

        if (canEdit) {
            binding.btnAddItem.visibility = View.VISIBLE
            binding.btnAddItem.setOnClickListener { showAddItemDialog() }
        }

        observeData()
        observeSync()
    }

    private fun observeData() {
        viewLifecycleOwner.lifecycleScope.launch {
            appViewModel.inventory.collectLatest { entities ->
                val items = entities.map { it.toDomain() }

                binding.tvStatItems.text    = items.size.toString()
                binding.tvStatLowStock.text = items.count { it.count <= 10 }.toString()

                inventoryAdapter.submitList(items)

                if (items.isEmpty()) {
                    binding.tvNoItems.visibility   = View.VISIBLE
                    binding.rvInventory.visibility = View.GONE
                } else {
                    binding.tvNoItems.visibility   = View.GONE
                    binding.rvInventory.visibility = View.VISIBLE
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            appViewModel.auditLog.collectLatest { entries ->
                auditLogAdapter.submitList(entries)
                if (entries.isEmpty()) {
                    binding.tvNoAuditLog.visibility = View.VISIBLE
                    binding.rvAuditLog.visibility   = View.GONE
                } else {
                    binding.tvNoAuditLog.visibility = View.GONE
                    binding.rvAuditLog.visibility   = View.VISIBLE
                }
            }
        }
    }

    private fun observeSync() {
        val svc = (requireActivity() as MainActivity).meshService

        viewLifecycleOwner.lifecycleScope.launch {
            if (svc != null) {
                svc.peerCount.collectLatest { count ->
                    binding.tvStatPeers.text = count.toString()
                    val synced = count > 0
                    val ctx = requireContext()
                    binding.syncDot.setBackgroundColor(
                        ctx.getColor(if (synced) R.color.mesh_online else R.color.mesh_searching)
                    )
                    binding.tvSyncStatus.text = if (synced) "SYNCED · $count peers" else "OFFLINE"
                }
            } else {
                binding.tvStatPeers.text = "0"
                binding.tvSyncStatus.text = "OFFLINE"
            }
        }
    }

    private fun adjustItem(key: String, delta: Int) {
        val svc = (requireActivity() as MainActivity).meshService
        if (svc != null) {
            svc.adjustInventoryItem(key, delta)
        } else {
            appViewModel.adjustInventory(key, delta)
        }
    }

    private fun confirmDelete(key: String) {
        val item = appViewModel.inventory.value.find { it.key == key } ?: return
        AlertDialog.Builder(requireContext())
            .setTitle("Delete Item")
            .setMessage("Remove \"${item.label}\" from inventory? This will sync to all peers.")
            .setPositiveButton("Delete") { _, _ ->
                val svc = (requireActivity() as MainActivity).meshService
                svc?.deleteInventoryItem(key) ?: appViewModel.deleteInventory(key)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showAddItemDialog() {
        val ctx = requireContext()
        val dialogView = LayoutInflater.from(ctx).inflate(R.layout.dialog_add_inventory_item, null, false)
        val etLabel = dialogView.findViewById<EditText>(R.id.etItemLabel)
        val etUnit  = dialogView.findViewById<EditText>(R.id.etItemUnit)
        val etCount = dialogView.findViewById<EditText>(R.id.etItemCount)

        AlertDialog.Builder(ctx)
            .setTitle("Add Inventory Item")
            .setView(dialogView)
            .setPositiveButton("Add") { _, _ ->
                val label = etLabel.text.toString().trim()
                val unit  = etUnit.text.toString().trim().ifBlank { "units" }
                val count = etCount.text.toString().toIntOrNull() ?: 0
                if (label.isNotBlank()) {
                    val key = label.lowercase().replace(Regex("[^a-z0-9]"), "_")
                    val svc = (requireActivity() as MainActivity).meshService
                    svc?.addInventoryItem(key, label, unit, count)
                        ?: appViewModel.addInventory(key, label, unit, count)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
