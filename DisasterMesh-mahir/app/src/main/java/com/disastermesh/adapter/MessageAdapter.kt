package com.disastermesh.adapter

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.disastermesh.Message
import com.disastermesh.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MessageAdapter(private val localDeviceId: String) :
    RecyclerView.Adapter<MessageAdapter.MessageViewHolder>() {

    private val messages = mutableListOf<Message>()
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    fun addMessage(message: Message) {
        messages.add(0, message)
        notifyItemInserted(0)
    }

    inner class MessageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvSender: TextView = view.findViewById(R.id.tvSenderName)
        val tvText: TextView = view.findViewById(R.id.tvMessageText)
        val tvTime: TextView = view.findViewById(R.id.tvTimestamp)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_message, parent, false)
        return MessageViewHolder(view)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        val msg = messages[position]
        val isLocal = msg.senderId == localDeviceId

        holder.tvSender.text = if (isLocal) "You" else msg.senderName
        holder.tvText.text = msg.text
        holder.tvTime.text = timeFormat.format(Date(msg.timestamp))

        // Local messages: blue bubble aligned right
        // Peer messages: dark bubble aligned left
        if (isLocal) {
            holder.tvText.setBackgroundResource(R.drawable.bubble_local)
            holder.tvText.setTextColor(Color.WHITE)
            holder.tvSender.setTextColor(Color.parseColor("#58A6FF"))
            (holder.itemView as ViewGroup).layoutDirection = View.LAYOUT_DIRECTION_RTL
        } else {
            holder.tvText.setBackgroundResource(R.drawable.bubble_peer)
            holder.tvText.setTextColor(Color.parseColor("#E6EDF3"))
            holder.tvSender.setTextColor(Color.parseColor("#8B949E"))
            (holder.itemView as ViewGroup).layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
    }

    override fun getItemCount() = messages.size
}
