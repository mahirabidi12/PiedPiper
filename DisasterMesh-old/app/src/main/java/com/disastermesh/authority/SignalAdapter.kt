package com.disastermesh.authority

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import com.disastermesh.R
import com.disastermesh.models.Signal
import com.disastermesh.models.SignalStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SignalAdapter(private val signals: List<Signal>) :
    RecyclerView.Adapter<SignalAdapter.VH>() {

    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val tvSender: TextView = view.findViewById(R.id.tvSignalSender)
        val tvPriority: TextView = view.findViewById(R.id.tvSignalPriority)
        val tvCategory: TextView = view.findViewById(R.id.tvSignalCategory)
        val tvSummary: TextView = view.findViewById(R.id.tvSignalSummary)
        val tvRaw: TextView = view.findViewById(R.id.tvSignalRaw)
        val tvTags: TextView = view.findViewById(R.id.tvSignalTags)
        val tvTime: TextView = view.findViewById(R.id.tvSignalTime)
        val tvLocation: TextView = view.findViewById(R.id.tvSignalLocation)
        val tvPeople: TextView = view.findViewById(R.id.tvSignalPeople)
        val rowActions: View = view.findViewById(R.id.rowSignalActions)
        val btnAck: TextView = view.findViewById(R.id.btnSignalAck)
        val btnResolve: TextView = view.findViewById(R.id.btnSignalResolve)
        val viewPriorityBar: View = view.findViewById(R.id.viewPriorityBar)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        return VH(LayoutInflater.from(parent.context).inflate(R.layout.item_signal, parent, false))
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val signal = signals[position]

        // Priority bar color
        holder.viewPriorityBar.setBackgroundColor(Color.parseColor(signal.priority.colorHex))

        holder.tvSender.text = signal.senderName
        holder.tvSummary.text = signal.summary
        holder.tvRaw.text = signal.rawMessage
        holder.tvTime.text = timeFormat.format(Date(signal.timestamp))

        holder.tvPriority.text = signal.priority.label.uppercase(Locale.getDefault())
        holder.tvPriority.setBackgroundColor(Color.parseColor(signal.priority.colorHex))
        holder.tvPriority.setTextColor(Color.WHITE)

        holder.tvCategory.text = signal.category.label.uppercase(Locale.getDefault())
        holder.tvCategory.setTextColor(Color.parseColor(signal.category.colorHex))

        if (signal.tags.isNotEmpty()) {
            holder.tvTags.visibility = View.VISIBLE
            holder.tvTags.text = signal.tags.joinToString(" / ")
        } else {
            holder.tvTags.visibility = View.GONE
        }

        val location = listOfNotNull(
            signal.locationText,
            signal.latitude?.let { latitude ->
                val longitude = signal.longitude ?: return@let null
                "%.4f, %.4f".format(latitude, longitude)
            }
        ).firstOrNull()

        if (location != null) {
            holder.tvLocation.visibility = View.VISIBLE
            holder.tvLocation.text = "LOC: $location"
        } else {
            holder.tvLocation.visibility = View.GONE
        }

        if (signal.peopleCount != null) {
            holder.tvPeople.visibility = View.VISIBLE
            holder.tvPeople.text = "PPL: ${signal.peopleCount}"
        } else {
            holder.tvPeople.visibility = View.GONE
        }

        if (signal.status == SignalStatus.RESOLVED) {
            holder.rowActions.visibility = View.GONE
        } else {
            holder.rowActions.visibility = View.VISIBLE
            holder.btnAck.setOnClickListener {
                Toast.makeText(
                    holder.itemView.context,
                    "Acknowledge shell for ${signal.id}",
                    Toast.LENGTH_SHORT
                ).show()
            }
            holder.btnResolve.setOnClickListener {
                Toast.makeText(
                    holder.itemView.context,
                    "Resolve shell for ${signal.id}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    override fun getItemCount(): Int = signals.size
}
