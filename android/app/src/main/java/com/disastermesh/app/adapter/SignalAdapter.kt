package com.disastermesh.app.adapter

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.disastermesh.app.R
import com.disastermesh.app.databinding.ItemSignalCardBinding
import com.disastermesh.app.model.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class SignalAdapter(
    private val onClick: ((Signal) -> Unit)? = null,
    private val onCancel: ((Signal) -> Unit)? = null
) : ListAdapter<Signal, SignalAdapter.VH>(DIFF) {

    inner class VH(val binding: ItemSignalCardBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemSignalCardBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val signal = getItem(position)
        val ctx = holder.binding.root.context

        // Left accent bar color
        val priorityColor = when (signal.priority) {
            SignalPriority.CRITICAL -> ctx.getColor(R.color.priority_critical)
            SignalPriority.HIGH     -> ctx.getColor(R.color.priority_high)
            SignalPriority.NORMAL   -> ctx.getColor(R.color.priority_normal)
            SignalPriority.LOW      -> ctx.getColor(R.color.priority_low)
        }
        holder.binding.signalPriorityAccent.setBackgroundColor(priorityColor)
        holder.binding.signalIconBox.setBackgroundColor(priorityColor.withAlpha(30))
        holder.binding.tvCategoryIcon.setTextColor(priorityColor)
        holder.binding.tvCategoryIcon.text = signal.category.icon

        // Category label
        holder.binding.tvCategory.text = signal.category.label
        holder.binding.tvCategory.setTextColor(priorityColor)

        // Status chip
        holder.binding.tvStatus.text = signal.status.name
        val statusColor = when (signal.status) {
            SignalStatus.NEW                  -> ctx.getColor(R.color.status_new)
            SignalStatus.ACKNOWLEDGED         -> ctx.getColor(R.color.status_acknowledged)
            SignalStatus.ASSIGNED             -> ctx.getColor(R.color.status_assigned)
            SignalStatus.ACCEPTED             -> ctx.getColor(R.color.status_accepted)
            SignalStatus.IN_PROGRESS          -> ctx.getColor(R.color.status_in_progress)
            SignalStatus.WAITING_FOR_INVENTORY -> ctx.getColor(R.color.status_waiting)
            SignalStatus.ON_HOLD              -> ctx.getColor(R.color.status_on_hold)
            SignalStatus.RESOLVED             -> ctx.getColor(R.color.status_resolved)
            SignalStatus.REJECTED             -> ctx.getColor(R.color.status_rejected)
            SignalStatus.CANCELLED            -> ctx.getColor(R.color.status_cancelled)
            SignalStatus.FAILED               -> ctx.getColor(R.color.status_failed)
            SignalStatus.EXPIRED              -> ctx.getColor(R.color.text_dim)
            SignalStatus.QUEUED               -> ctx.getColor(R.color.text_muted)
        }
        holder.binding.tvStatus.setTextColor(statusColor)

        // Time ago
        holder.binding.tvTimeAgo.text = timeAgo(signal.createdAt)

        // Message
        holder.binding.tvMessage.text = signal.message

        // Sender
        val roleColor = when (signal.senderRole) {
            Role.CIVILIAN  -> ctx.getColor(R.color.civilian)
            Role.VOLUNTEER -> ctx.getColor(R.color.volunteer)
            Role.AUTHORITY -> ctx.getColor(R.color.authority)
        }
        holder.binding.tvSenderBadge.text = signal.senderRole.badge
        holder.binding.tvSenderBadge.setTextColor(roleColor)
        holder.binding.tvSenderName.text = signal.senderName

        // People count
        val people = signal.peopleCount
        holder.binding.tvPeopleCount.text = if (people != null && people > 0) "· $people people" else ""

        holder.binding.root.setOnClickListener { onClick?.invoke(signal) }

        // Cancel button — only show for non-terminal signals when authority provides onCancel
        if (onCancel != null && !signal.status.isTerminal) {
            holder.binding.btnCancelTicket.visibility = View.VISIBLE
            holder.binding.btnCancelTicket.setOnClickListener { onCancel.invoke(signal) }
        } else {
            holder.binding.btnCancelTicket.visibility = View.GONE
        }
    }

    private fun timeAgo(epochMs: Long): String {
        val diffMs = System.currentTimeMillis() - epochMs
        return when {
            diffMs < TimeUnit.MINUTES.toMillis(1)  -> "just now"
            diffMs < TimeUnit.HOURS.toMillis(1)    -> "${TimeUnit.MILLISECONDS.toMinutes(diffMs)}m ago"
            diffMs < TimeUnit.DAYS.toMillis(1)     -> "${TimeUnit.MILLISECONDS.toHours(diffMs)}h ago"
            else                                   -> "${TimeUnit.MILLISECONDS.toDays(diffMs)}d ago"
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<Signal>() {
            override fun areItemsTheSame(a: Signal, b: Signal) = a.id == b.id
            override fun areContentsTheSame(a: Signal, b: Signal) = a == b
        }
    }
}

private fun Int.withAlpha(alpha: Int): Int =
    Color.argb(alpha, Color.red(this), Color.green(this), Color.blue(this))
