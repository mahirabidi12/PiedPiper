package com.disastermesh.app.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.disastermesh.app.R
import com.disastermesh.app.databinding.ItemChatMessageInBinding
import com.disastermesh.app.databinding.ItemChatMessageOutBinding
import com.disastermesh.app.model.ChatMessage
import com.disastermesh.app.model.Role
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

private const val VIEW_IN  = 0
private const val VIEW_OUT = 1

class ChatMessageAdapter(
    private val localNodeId: String
) : ListAdapter<ChatMessage, RecyclerView.ViewHolder>(DIFF) {

    override fun getItemViewType(position: Int): Int {
        return if (getItem(position).senderNodeId == localNodeId) VIEW_OUT else VIEW_IN
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == VIEW_OUT) {
            OutVH(ItemChatMessageOutBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        } else {
            InVH(ItemChatMessageInBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val msg = getItem(position)
        when (holder) {
            is InVH  -> bindIn(holder, msg)
            is OutVH -> bindOut(holder, msg)
        }
    }

    private fun bindIn(holder: InVH, msg: ChatMessage) {
        val ctx = holder.binding.root.context
        val roleColor = when (msg.senderRole) {
            Role.CIVILIAN  -> ctx.getColor(R.color.civilian)
            Role.VOLUNTEER -> ctx.getColor(R.color.volunteer)
            Role.AUTHORITY -> ctx.getColor(R.color.authority)
        }
        holder.binding.tvRoleBadge.text = msg.senderRole.badge
        holder.binding.tvRoleBadge.setTextColor(roleColor)
        holder.binding.tvSenderName.text = msg.senderName
        holder.binding.tvSenderName.setTextColor(roleColor)
        holder.binding.tvTimestamp.text = formatTime(msg.createdAt)
        holder.binding.tvMessageText.text = msg.text
    }

    private fun bindOut(holder: OutVH, msg: ChatMessage) {
        val ctx = holder.binding.root.context
        val roleColor = when (msg.senderRole) {
            Role.CIVILIAN  -> ctx.getColor(R.color.civilian)
            Role.VOLUNTEER -> ctx.getColor(R.color.volunteer)
            Role.AUTHORITY -> ctx.getColor(R.color.authority)
        }
        holder.binding.tvOutMessageText.text = msg.text
        holder.binding.tvOutMessageText.setTextColor(roleColor)
        holder.binding.tvOutTimestamp.text = formatTime(msg.createdAt)
    }

    private fun formatTime(epochMs: Long): String {
        val diffMs = System.currentTimeMillis() - epochMs
        return if (diffMs < TimeUnit.HOURS.toMillis(1)) {
            SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(epochMs))
        } else {
            SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(epochMs))
        }
    }

    inner class InVH(val binding: ItemChatMessageInBinding) : RecyclerView.ViewHolder(binding.root)
    inner class OutVH(val binding: ItemChatMessageOutBinding) : RecyclerView.ViewHolder(binding.root)

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<ChatMessage>() {
            override fun areItemsTheSame(a: ChatMessage, b: ChatMessage) = a.id == b.id
            override fun areContentsTheSame(a: ChatMessage, b: ChatMessage) = a == b
        }
    }
}
