package com.disastermesh.authority

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.disastermesh.R
import com.disastermesh.models.AreaCluster
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DashboardAdapter(
    private val onClusterTap: (AreaCluster) -> Unit
) : RecyclerView.Adapter<DashboardAdapter.VH>() {

    private val clusters = mutableListOf<AreaCluster>()
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    fun submitList(list: List<AreaCluster>) {
        clusters.clear()
        clusters.addAll(list)
        notifyDataSetChanged()
    }

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val tvLocation:  TextView = view.findViewById(R.id.tvClusterLocation)
        val tvMedical:   TextView = view.findViewById(R.id.tvClusterMedical)
        val tvRescue:    TextView = view.findViewById(R.id.tvClusterRescue)
        val tvResource:  TextView = view.findViewById(R.id.tvClusterResource)
        val tvSafety:    TextView = view.findViewById(R.id.tvClusterSafety)
        val tvCritical:  TextView = view.findViewById(R.id.tvClusterCritical)
        val tvTime:      TextView = view.findViewById(R.id.tvClusterTime)
        val tvTotal:     TextView = view.findViewById(R.id.tvClusterTotal)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_area_cluster, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val c = clusters[position]
        holder.tvLocation.text = "📍 ${c.areaLabel}"
        holder.tvMedical.text  = "🔴 Medical    ${c.medicalCount}"
        holder.tvRescue.text   = "🟠 Rescue      ${c.rescueCount}"
        holder.tvResource.text = "🔵 Resource   ${c.resourceCount}"
        holder.tvSafety.text   = "🟢 Safety      ${c.safetyCount}"
        holder.tvTotal.text    = "${c.totalCount} signal${if (c.totalCount != 1) "s" else ""}"
        holder.tvTime.text     = timeFormat.format(Date(c.latestTimestamp))

        if (c.criticalCount > 0) {
            holder.tvCritical.visibility = View.VISIBLE
            holder.tvCritical.text = "⚠ ${c.criticalCount} CRITICAL"
            holder.tvCritical.setTextColor(Color.parseColor("#EF4444"))
        } else {
            holder.tvCritical.visibility = View.GONE
        }

        holder.itemView.setOnClickListener { onClusterTap(c) }
    }

    override fun getItemCount() = clusters.size
}
