package com.disastermesh.app.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.disastermesh.app.R
import com.disastermesh.app.databinding.ItemZoneCardBinding
import com.disastermesh.app.model.AreaCluster
import java.util.concurrent.TimeUnit

class AreaClusterAdapter(
    private val onClick: (AreaCluster) -> Unit
) : ListAdapter<AreaCluster, AreaClusterAdapter.VH>(DIFF) {

    inner class VH(val binding: ItemZoneCardBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemZoneCardBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val c = getItem(position)
        val b = holder.binding
        val ctx = b.root.context

        b.tvZoneLabel.text = c.areaLabel

        if (c.criticalCount > 0) {
            b.containerCriticalBadge.visibility = View.VISIBLE
            b.tvZoneCritical.text = "${c.criticalCount} CRITICAL"
            b.zoneAccentBar.setBackgroundColor(ctx.getColor(R.color.priority_critical))
        } else if (c.signals.any { it.priority.name == "HIGH" }) {
            b.containerCriticalBadge.visibility = View.GONE
            b.zoneAccentBar.setBackgroundColor(ctx.getColor(R.color.priority_high))
        } else {
            b.containerCriticalBadge.visibility = View.GONE
            b.zoneAccentBar.setBackgroundColor(ctx.getColor(R.color.border_strong))
        }

        b.tvZoneMedical.text  = "✚ ${c.medicalCount}"
        b.tvZoneRescue.text   = "⬆ ${c.rescueCount}"
        b.tvZoneResource.text = "◆ ${c.resourceCount}"
        b.tvZoneSafety.text   = "▲ ${c.safetyCount}"
        b.tvZoneOther.text    = "● ${c.otherCount}"

        val total = c.totalCount
        val open  = c.openCount
        b.tvZoneTotal.text = "$total signal${if (total != 1) "s" else ""} · $open open"
        b.tvZoneTime.text  = timeAgo(c.latestTimestamp)

        b.root.setOnClickListener { onClick(c) }
    }

    private fun timeAgo(epochMs: Long): String {
        val diff = System.currentTimeMillis() - epochMs
        return when {
            diff < TimeUnit.MINUTES.toMillis(1) -> "now"
            diff < TimeUnit.HOURS.toMillis(1)   -> "${TimeUnit.MILLISECONDS.toMinutes(diff)}m ago"
            else                                -> "${TimeUnit.MILLISECONDS.toHours(diff)}h ago"
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<AreaCluster>() {
            override fun areItemsTheSame(a: AreaCluster, b: AreaCluster) =
                a.areaLabel == b.areaLabel
            override fun areContentsTheSame(a: AreaCluster, b: AreaCluster) = a == b
        }
    }
}
