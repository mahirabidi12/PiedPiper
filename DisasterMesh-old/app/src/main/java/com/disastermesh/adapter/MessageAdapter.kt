package com.disastermesh.adapter

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.disastermesh.Message
import com.disastermesh.R
import com.disastermesh.Role
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MessageAdapter(
    private val localDeviceId: String,
    private val myRole: Role
) : RecyclerView.Adapter<MessageAdapter.MessageViewHolder>() {

    private val messages = mutableListOf<Message>()
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    fun addMessage(message: Message) {
        messages.add(0, message)
        notifyItemInserted(0)
    }

    inner class MessageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvSender: TextView   = view.findViewById(R.id.tvSenderName)
        val tvBadge: TextView    = view.findViewById(R.id.tvRoleBadge)
        val tvText: TextView     = view.findViewById(R.id.tvMessageText)
        val tvTime: TextView     = view.findViewById(R.id.tvTimestamp)
        val tvTarget: TextView   = view.findViewById(R.id.tvTargetLabel)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_message, parent, false)
        return MessageViewHolder(view)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        val msg     = messages[position]
        val isLocal = msg.senderId == localDeviceId
        val role    = msg.role()

        // Sender name
        holder.tvSender.text = if (isLocal) "You" else msg.senderName
        holder.tvSender.setTextColor(role.color())

        // Role badge
        holder.tvBadge.text = role.badgeLabel
        holder.tvBadge.setTextColor(Color.WHITE)
        holder.tvBadge.setBackgroundColor(role.color())
        holder.tvBadge.visibility = if (isLocal) View.GONE else View.VISIBLE

        // Message text
        holder.tvText.text = msg.text

        // Timestamp
        holder.tvTime.text = timeFormat.format(Date(msg.timestamp))

        // Target label — show if message wasn't sent to everyone
        if (!isLocal && msg.targetRole != "ALL") {
            holder.tvTarget.visibility = View.VISIBLE
            holder.tvTarget.text = when (msg.targetRole) {
                "VOLUNTEER" -> "→ Volunteers"
                "AUTHORITY" -> "→ Authorities"
                else        -> ""
            }
            holder.tvTarget.setTextColor(role.color())
        } else {
            holder.tvTarget.visibility = View.GONE
        }

        // Bubble style + alignment
        if (isLocal) {
            holder.tvText.setBackgroundResource(R.drawable.bubble_local)
            holder.tvText.setTextColor(Color.WHITE)
            (holder.itemView as ViewGroup).layoutDirection = View.LAYOUT_DIRECTION_RTL
        } else {
            // Peer bubble colour changes slightly per role
            holder.tvText.setBackgroundResource(R.drawable.bubble_peer)
            holder.tvText.setTextColor(Color.parseColor("#E6EDF3"))
            (holder.itemView as ViewGroup).layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
    }

    override fun getItemCount() = messages.size
}
