package com.disastermesh.app.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
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
        val tvCount      = view.findViewById<TextView>(R.id.tvCount)
        val tvLabel      = view.findViewById<TextView>(R.id.tvLabel)
        val tvUnit       = view.findViewById<TextView>(R.id.tvUnit)
        val tvStockBadge = view.findViewById<TextView>(R.id.tvStockBadge)
        val tvUpdatedBy  = view.findViewById<TextView>(R.id.tvUpdatedBy)
        val tvUpdatedAt  = view.findViewById<TextView>(R.id.tvUpdatedAt)
        val btnMinus     = view.findViewById<TextView>(R.id.btnMinus)
        val btnPlus      = view.findViewById<TextView>(R.id.btnPlus)
        val btnDelete    = view.findViewById<TextView>(R.id.btnDelete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_inventory_card, parent, false))

    override fun onBindViewHolder(vh: VH, position: Int) {
        val item = getItem(position)
        vh.tvCount.text = item.count.toString()
        vh.tvLabel.text = item.label
        vh.tvUnit.text  = item.unit

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

        if (item.updatedByName.isNotBlank()) {
            vh.tvUpdatedBy.text = "by ${item.updatedByName}"
        } else {
            vh.tvUpdatedBy.text = ""
        }
        vh.tvUpdatedAt.text = if (item.updatedAt > 0) ageText(item.updatedAt) else ""

        if (canEdit) {
            vh.btnMinus.visibility = View.VISIBLE
            vh.btnPlus.visibility  = View.VISIBLE
            vh.btnMinus.setOnClickListener { onAdjust(item.key, -1) }
            vh.btnPlus.setOnClickListener  { onAdjust(item.key, +1) }
        } else {
            vh.btnMinus.visibility = View.GONE
            vh.btnPlus.visibility  = View.GONE
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
