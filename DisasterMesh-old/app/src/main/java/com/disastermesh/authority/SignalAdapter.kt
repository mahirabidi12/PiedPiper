package com.disastermesh.authority

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.disastermesh.R
import com.disastermesh.models.Signal
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SignalAdapter(private val signals: List<Signal>) :
    RecyclerView.Adapter<SignalAdapter.VH>() {

    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val tvSender:   TextView = view.findViewById(R.id.tvSignalSender)
        val tvPriority: TextView = view.findViewById(R.id.tvSignalPriority)
        val tvCategory: TextView = view.findViewById(R.id.tvSignalCategory)
        val tvSummary:  TextView = view.findViewById(R.id.tvSignalSummary)
        val tvRaw:      TextView = view.findViewById(R.id.tvSignalRaw)
        val tvTags:     TextView = view.findViewById(R.id.tvSignalTags)
        val tvTime:     TextView = view.findViewById(R.id.tvSignalTime)
        val tvLocation: TextView = view.findViewById(R.id.tvSignalLocation)
        val tvPeople:   TextView = view.findViewById(R.id.tvSignalPeople)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_signal, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val s = signals[position]

        holder.tvSender.text   = s.senderName
        holder.tvSummary.text  = s.summary
        holder.tvRaw.text      = "\"${s.rawMessage}\""
        holder.tvTime.text     = timeFormat.format(Date(s.timestamp))

        // Priority badge
        holder.tvPriority.text = s.priority.label
        holder.tvPriority.setBackgroundColor(Color.parseColor(s.priority.colorHex))
        holder.tvPriority.setTextColor(Color.WHITE)

        // Category badge
        holder.tvCategory.text = "${s.category.emoji} ${s.category.label}"
        holder.tvCategory.setTextColor(Color.parseColor(s.category.colorHex))

        // Tags
        if (s.tags.isNotEmpty()) {
            holder.tvTags.visibility = View.VISIBLE
            holder.tvTags.text = s.tags.joinToString(" · ")
        } else {
            holder.tvTags.visibility = View.GONE
        }

        // Location
        val loc = listOfNotNull(s.locationText, s.latitude?.let { "%.4f, %.4f".format(it, s.longitude) })
            .firstOrNull()
        if (loc != null) {
            holder.tvLocation.visibility = View.VISIBLE
            holder.tvLocation.text = "📍 $loc"
        } else {
            holder.tvLocation.visibility = View.GONE
        }

        // People count
        if (s.peopleCount != null) {
            holder.tvPeople.visibility = View.VISIBLE
            holder.tvPeople.text = "👥 ${s.peopleCount} people"
        } else {
            holder.tvPeople.visibility = View.GONE
        }
    }

    override fun getItemCount() = signals.size
}
