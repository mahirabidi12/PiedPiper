package com.disastermesh.app.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.disastermesh.app.R
import com.disastermesh.app.databinding.ItemChatMessageInBinding
import com.disastermesh.app.databinding.ItemChatMessageOutBinding
import com.disastermesh.app.db.entities.AiMessageEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Chat-style bubble adapter for the on-device AI conversation.
 *
 * User turns render with the outgoing bubble; AI responses with the incoming
 * bubble + a "GEMMA" role badge. Long-press an AI bubble to toggle pin
 * (Feature 2: Pinned Emergency Snippets).
 */
class AiChatAdapter(
    private val onPinToggle: (AiMessageEntity) -> Unit = {}
) : ListAdapter<AiMessageEntity, RecyclerView.ViewHolder>(DIFF) {

    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

    override fun getItemViewType(position: Int) =
        if (getItem(position).isUser) VIEW_USER else VIEW_AI

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == VIEW_USER) {
            UserVH(ItemChatMessageOutBinding.inflate(inflater, parent, false))
        } else {
            AiVH(ItemChatMessageInBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val msg = getItem(position)
        when (holder) {
            is UserVH -> {
                holder.binding.tvOutMessageText.text = msg.text
                holder.binding.tvOutTimestamp.text   = timeFmt.format(Date(msg.createdAt))
            }
            is AiVH -> {
                val ctx = holder.binding.root.context
                val accent = ctx.getColor(R.color.civilian)
                holder.binding.tvRoleBadge.text       = if (msg.pinned) "📌" else "AI"
                holder.binding.tvRoleBadge.setTextColor(accent)
                holder.binding.tvSenderName.text      = "Gemma"
                holder.binding.tvSenderName.setTextColor(accent)
                holder.binding.tvMessageText.text     = msg.text
                holder.binding.tvTimestamp.text       = timeFmt.format(Date(msg.createdAt))
                holder.itemView.setOnLongClickListener {
                    onPinToggle(msg); true
                }
            }
        }
    }

    class UserVH(val binding: ItemChatMessageOutBinding) : RecyclerView.ViewHolder(binding.root)
    class AiVH  (val binding: ItemChatMessageInBinding ) : RecyclerView.ViewHolder(binding.root)

    companion object {
        private const val VIEW_USER = 0
        private const val VIEW_AI   = 1

        private val DIFF = object : DiffUtil.ItemCallback<AiMessageEntity>() {
            override fun areItemsTheSame(a: AiMessageEntity, b: AiMessageEntity) = a.id == b.id
            override fun areContentsTheSame(a: AiMessageEntity, b: AiMessageEntity) = a == b
        }
    }
}
