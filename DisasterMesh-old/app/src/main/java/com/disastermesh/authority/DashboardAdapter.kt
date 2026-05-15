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
        val viewPriorityBar: View = view.findViewById(R.id.viewPriorityBar)
        val tvSubLabel: TextView? = view.findViewById(R.id.tvClusterSubLabel)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        return VH(
            LayoutInflater.from(parent.context).inflate(R.layout.item_area_cluster, parent, false)
        )
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val cluster = clusters[position]
        holder.tvLocation.text = cluster.areaLabel

        // Priority bar color
        val priorityColor = when {
            cluster.criticalCount > 0 -> Color.parseColor("#FF4D4D")
            cluster.medicalCount > 0 || cluster.rescueCount > 0 -> Color.parseColor("#FFA033")
            else -> Color.parseColor("#71717A")
        }
        holder.viewPriorityBar.setBackgroundColor(priorityColor)
        holder.tvTotal.setTextColor(priorityColor)
        holder.tvTotal.text = cluster.totalCount.toString()

        // Sub label
        holder.tvSubLabel?.text = "${cluster.totalCount} signals · ${timeFormat.format(Date(cluster.latestTimestamp))}"

        // Category counts - hide if zero
        if (cluster.medicalCount > 0) {
            holder.tvMedical.visibility = View.VISIBLE
            holder.tvMedical.text = "MED ${cluster.medicalCount}"
            holder.tvMedical.setTextColor(Color.parseColor("#FF4D4D"))
        } else {
            holder.tvMedical.visibility = View.GONE
        }

        if (cluster.rescueCount > 0) {
            holder.tvRescue.visibility = View.VISIBLE
            holder.tvRescue.text = "RESC ${cluster.rescueCount}"
            holder.tvRescue.setTextColor(Color.parseColor("#FFA033"))
        } else {
            holder.tvRescue.visibility = View.GONE
        }

        if (cluster.resourceCount > 0) {
            holder.tvResource.visibility = View.VISIBLE
            holder.tvResource.text = "RES ${cluster.resourceCount}"
            holder.tvResource.setTextColor(Color.parseColor("#58A6FF"))
        } else {
            holder.tvResource.visibility = View.GONE
        }

        if (cluster.safetyCount > 0) {
            holder.tvSafety.visibility = View.VISIBLE
            holder.tvSafety.text = "SAF ${cluster.safetyCount}"
            holder.tvSafety.setTextColor(Color.parseColor("#3FB950"))
        } else {
            holder.tvSafety.visibility = View.GONE
        }

        holder.tvTime.text = "Last ${timeFormat.format(Date(cluster.latestTimestamp))}"

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
