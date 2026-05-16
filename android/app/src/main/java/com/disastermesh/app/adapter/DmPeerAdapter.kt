package com.disastermesh.app.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.disastermesh.app.databinding.ItemDmPeerBinding
import com.disastermesh.app.db.entities.DirectMessageEntity
import java.text.SimpleDateFormat
import java.util.*

class DmPeerAdapter(
    private val localNodeId: String,
    private val onClick: (peerId: String, peerName: String) -> Unit
) : ListAdapter<DirectMessageEntity, DmPeerAdapter.VH>(DIFF) {

    inner class VH(val binding: ItemDmPeerBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemDmPeerBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val msg = getItem(position)
        val isOutgoing = msg.senderNodeId == localNodeId
        val peerId     = if (isOutgoing) msg.recipientNodeId else msg.senderNodeId
        val peerName   = if (isOutgoing) peerId.take(8)      else msg.senderName

        with(holder.binding) {
            tvAvatarInitial.text = peerName.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
            tvPeerName.text      = peerName
            tvLastMessage.text   = if (isOutgoing) "You: ${msg.text}" else msg.text
            tvTimestamp.text     = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(msg.createdAt))
            root.setOnClickListener { onClick(peerId, peerName) }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<DirectMessageEntity>() {
            override fun areItemsTheSame(a: DirectMessageEntity, b: DirectMessageEntity) =
                a.threadId == b.threadId
            override fun areContentsTheSame(a: DirectMessageEntity, b: DirectMessageEntity) =
                a == b
        }
    }
}
