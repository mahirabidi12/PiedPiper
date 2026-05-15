package com.disastermesh.authority

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
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
        val tvLocation: TextView = view.findViewById(R.id.tvClusterLocation)
        val tvMedical: TextView = view.findViewById(R.id.tvClusterMedical)
        val tvRescue: TextView = view.findViewById(R.id.tvClusterRescue)
        val tvResource: TextView = view.findViewById(R.id.tvClusterResource)
        val tvSafety: TextView = view.findViewById(R.id.tvClusterSafety)
        val tvCritical: TextView = view.findViewById(R.id.tvClusterCritical)
        val tvTime: TextView = view.findViewById(R.id.tvClusterTime)
        val tvTotal: TextView = view.findViewById(R.id.tvClusterTotal)
        val btnGemma: TextView = view.findViewById(R.id.btnClusterGemma)
        val btnOpen: TextView = view.findViewById(R.id.btnClusterOpen)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        return VH(
            LayoutInflater.from(parent.context).inflate(R.layout.item_area_cluster, parent, false)
        )
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val cluster = clusters[position]
        holder.tvLocation.text = cluster.areaLabel
        holder.tvMedical.text = "MEDICAL   ${cluster.medicalCount}"
        holder.tvRescue.text = "RESCUE    ${cluster.rescueCount}"
        holder.tvResource.text = "RESOURCE  ${cluster.resourceCount}"
        holder.tvSafety.text = "SAFETY    ${cluster.safetyCount}"
        holder.tvTotal.text = "${cluster.totalCount} signals"
        holder.tvTime.text = "Last update ${timeFormat.format(Date(cluster.latestTimestamp))}"

        if (cluster.criticalCount > 0) {
            holder.tvCritical.visibility = View.VISIBLE
            holder.tvCritical.text = "${cluster.criticalCount} CRITICAL"
            holder.tvCritical.setTextColor(Color.parseColor("#FF4D4D"))
        } else {
            holder.tvCritical.visibility = View.GONE
        }

        holder.btnOpen.setOnClickListener { onClusterTap(cluster) }
        holder.btnGemma.setOnClickListener {
            Toast.makeText(
                holder.itemView.context,
                "Gemma zone resolver shell is ready.",
                Toast.LENGTH_SHORT
            ).show()
        }
        holder.itemView.setOnClickListener { onClusterTap(cluster) }
    }

    override fun getItemCount(): Int = clusters.size
}
