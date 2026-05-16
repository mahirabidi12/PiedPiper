package com.disastermesh.app.ui.sheet

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.disastermesh.app.R
import com.disastermesh.app.databinding.BottomSheetInventoryBinding
import com.disastermesh.app.ui.AppViewModel
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class InventoryBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetInventoryBinding? = null
    private val binding get() = _binding!!

    private var canEdit = false
    private var onAdjust: ((String, Int) -> Unit)? = null

    private val appViewModel: AppViewModel by activityViewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = BottomSheetInventoryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.btnClose.setOnClickListener { dismiss() }
        observeInventory()
    }

    private fun observeInventory() {
        viewLifecycleOwner.lifecycleScope.launch {
            appViewModel.inventory.collectLatest { items ->
                binding.containerItems.removeAllViews()
                val inflater = LayoutInflater.from(requireContext())
                items.forEach { item ->
                    val row = inflater.inflate(R.layout.item_inventory_row, binding.containerItems, false)
                    row.findViewById<TextView>(R.id.tvItemLabel).text = item.label
                    val tvCount = row.findViewById<TextView>(R.id.tvItemCount)
                    tvCount.text = "${item.count} ${item.unit}"
                    if (item.count <= 5) tvCount.setTextColor(requireContext().getColor(R.color.priority_critical))

                    if (canEdit) {
                        row.findViewById<View>(R.id.btnMinus).visibility = View.VISIBLE
                        row.findViewById<View>(R.id.btnPlus).visibility  = View.VISIBLE
                        row.findViewById<View>(R.id.btnMinus).setOnClickListener {
                            onAdjust?.invoke(item.key, -1)
                        }
                        row.findViewById<View>(R.id.btnPlus).setOnClickListener {
                            onAdjust?.invoke(item.key, 1)
                        }
                    }
                    binding.containerItems.addView(row)
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        fun newInstance(canEdit: Boolean, onAdjust: (String, Int) -> Unit) =
            InventoryBottomSheet().apply {
                this.canEdit  = canEdit
                this.onAdjust = onAdjust
            }
    }
}
