package com.disastermesh.app.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.core.widget.addTextChangedListener
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.disastermesh.app.R
import com.disastermesh.app.model.InventoryItem
import java.util.concurrent.TimeUnit

class InventoryAdapter(
    private val canEdit: Boolean,
    private val canDelete: Boolean,
    private val onAdjust: (key: String, delta: Int) -> Unit,
    private val onDelete: (key: String) -> Unit = {}
) : ListAdapter<InventoryItem, InventoryAdapter.VH>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<InventoryItem>() {
            override fun areItemsTheSame(a: InventoryItem, b: InventoryItem) = a.key == b.key
            override fun areContentsTheSame(a: InventoryItem, b: InventoryItem) = a == b
        }
        private const val LOW_STOCK_THRESHOLD = 10
    }

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val etCount      = view.findViewById<EditText>(R.id.tvCount)
        val tvLabel      = view.findViewById<TextView>(R.id.tvLabel)
        val tvUnit       = view.findViewById<TextView>(R.id.tvUnit)
        val tvStockBadge = view.findViewById<TextView>(R.id.tvStockBadge)
        val tvUpdatedBy  = view.findViewById<TextView>(R.id.tvUpdatedBy)
        val tvUpdatedAt  = view.findViewById<TextView>(R.id.tvUpdatedAt)
        val btnMinus     = view.findViewById<TextView>(R.id.btnMinus)
        val btnPlus      = view.findViewById<TextView>(R.id.btnPlus)
        val btnDelete    = view.findViewById<TextView>(R.id.btnDelete)
        var boundCount   = 0   // last count written by bind; used to compute delta on commit
        var ignoreTextChange = false
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_inventory_card, parent, false))

    override fun onBindViewHolder(vh: VH, position: Int) {
        val item = getItem(position)
        vh.boundCount = item.count
        vh.tvLabel.text = item.label
        vh.tvUnit.text  = item.unit

        // Only update text if not currently focused (don't interrupt typing)
        if (!vh.etCount.isFocused) {
            vh.ignoreTextChange = true
            vh.etCount.setText(item.count.toString())
            vh.ignoreTextChange = false
        }

        if (item.count <= LOW_STOCK_THRESHOLD && item.count >= 0) {
            vh.tvStockBadge.visibility = View.VISIBLE
            vh.tvStockBadge.text = if (item.count == 0) "OUT OF STOCK" else "LOW"
            val ctx = vh.itemView.context
            if (item.count == 0) {
                vh.tvStockBadge.setTextColor(ctx.getColor(R.color.priority_critical))
                vh.tvStockBadge.setBackgroundColor(ctx.getColor(R.color.critical_tint))
            } else {
                vh.tvStockBadge.setTextColor(ctx.getColor(R.color.priority_high))
                vh.tvStockBadge.setBackgroundColor(ctx.getColor(R.color.ink_800))
            }
        } else {
            vh.tvStockBadge.visibility = View.GONE
        }

        vh.tvUpdatedBy.text = if (item.updatedByName.isNotBlank()) "by ${item.updatedByName}" else ""
        vh.tvUpdatedAt.text = if (item.updatedAt > 0) ageText(item.updatedAt) else ""

        if (canEdit) {
            vh.btnMinus.visibility = View.VISIBLE
            vh.btnPlus.visibility  = View.VISIBLE
            vh.etCount.isFocusableInTouchMode = true
            vh.etCount.isFocusable = true

            vh.btnMinus.setOnClickListener {
                val shown = vh.etCount.text.toString().toIntOrNull() ?: vh.boundCount
                if (shown > 0) {
                    val delta = (shown - 1) - vh.boundCount
                    vh.ignoreTextChange = true
                    vh.etCount.setText((shown - 1).toString())
                    vh.ignoreTextChange = false
                    onAdjust(item.key, delta)
                    vh.boundCount = shown - 1
                }
            }
            vh.btnPlus.setOnClickListener {
                val shown = vh.etCount.text.toString().toIntOrNull() ?: vh.boundCount
                val delta = (shown + 1) - vh.boundCount
                vh.ignoreTextChange = true
                vh.etCount.setText((shown + 1).toString())
                vh.ignoreTextChange = false
                onAdjust(item.key, delta)
                vh.boundCount = shown + 1
            }

            // Commit typed value on focus loss
            vh.etCount.setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus) {
                    val newVal = vh.etCount.text.toString().toIntOrNull() ?: vh.boundCount
                    val delta = newVal - vh.boundCount
                    if (delta != 0) {
                        onAdjust(item.key, delta)
                        vh.boundCount = newVal
                    }
                }
            }
        } else {
            vh.btnMinus.visibility = View.GONE
            vh.btnPlus.visibility  = View.GONE
            vh.etCount.isFocusableInTouchMode = false
            vh.etCount.isFocusable = false
        }

        if (canDelete) {
            vh.btnDelete.visibility = View.VISIBLE
            vh.btnDelete.setOnClickListener { onDelete(item.key) }
        } else {
            vh.btnDelete.visibility = View.GONE
        }
    }

    private fun ageText(epochMs: Long): String {
        val diff = System.currentTimeMillis() - epochMs
        return when {
            diff < TimeUnit.MINUTES.toMillis(1) -> "just now"
            diff < TimeUnit.HOURS.toMillis(1)   -> "${TimeUnit.MILLISECONDS.toMinutes(diff)}m ago"
            diff < TimeUnit.DAYS.toMillis(1)    -> "${TimeUnit.MILLISECONDS.toHours(diff)}h ago"
            else                                -> "${TimeUnit.MILLISECONDS.toDays(diff)}d ago"
        }
    }
}
