package com.disastermesh.app.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.disastermesh.app.R
import com.disastermesh.app.databinding.ItemPeerCardBinding
import com.disastermesh.app.model.Peer
import com.disastermesh.app.model.PeerState
import com.disastermesh.app.model.Role
import java.util.concurrent.TimeUnit

class PeerAdapter : ListAdapter<Peer, PeerAdapter.VH>(DIFF) {

    inner class VH(val binding: ItemPeerCardBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemPeerCardBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val peer = getItem(position)
        val ctx  = holder.binding.root.context

        val roleColor = when (peer.role) {
            Role.CIVILIAN  -> ctx.getColor(R.color.civilian)
            Role.VOLUNTEER -> ctx.getColor(R.color.volunteer)
            Role.AUTHORITY -> ctx.getColor(R.color.authority)
        }
        val roleTint = when (peer.role) {
            Role.CIVILIAN  -> ctx.getColor(R.color.civilian_tint)
            Role.VOLUNTEER -> ctx.getColor(R.color.volunteer_tint)
            Role.AUTHORITY -> ctx.getColor(R.color.authority_tint)
        }

        // Left accent bar
        holder.binding.peerAccentBar.setBackgroundColor(
            if (peer.connectionState == PeerState.CONNECTED) roleColor
            else ctx.getColor(R.color.text_dim)
        )

        // Role badge
        holder.binding.tvPeerRoleBadge.text = peer.role.badge
        holder.binding.tvPeerRoleBadge.setTextColor(roleColor)
        holder.binding.tvPeerRoleBadge.setBackgroundColor(roleTint)

        // Name
        holder.binding.tvPeerName.text = peer.name
        holder.binding.tvPeerName.alpha = if (peer.connectionState == PeerState.CONNECTED) 1.0f else 0.45f

        // Node ID (short)
        holder.binding.tvPeerNodeId.text = peer.nodeId.take(8)

        // Last seen
        holder.binding.tvPeerLastSeen.text = when (peer.connectionState) {
            PeerState.CONNECTED -> "NOW"
            else                -> lastSeenText(peer.lastSeen)
        }

        // Status dot color
        val dotColor = when (peer.connectionState) {
            PeerState.CONNECTED -> ctx.getColor(R.color.mesh_online)
            PeerState.SEEN      -> ctx.getColor(R.color.mesh_searching)
            PeerState.LOST      -> ctx.getColor(R.color.mesh_offline)
        }
        holder.binding.peerStatusDot.setBackgroundColor(dotColor)
    }

    private fun lastSeenText(epochMs: Long): String {
        val diff = System.currentTimeMillis() - epochMs
        return when {
            diff < TimeUnit.MINUTES.toMillis(1)  -> "${TimeUnit.MILLISECONDS.toSeconds(diff)}s ago"
            diff < TimeUnit.HOURS.toMillis(1)    -> "${TimeUnit.MILLISECONDS.toMinutes(diff)}m ago"
            diff < TimeUnit.DAYS.toMillis(1)     -> "${TimeUnit.MILLISECONDS.toHours(diff)}h ago"
            else                                 -> "${TimeUnit.MILLISECONDS.toDays(diff)}d ago"
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<Peer>() {
            override fun areItemsTheSame(a: Peer, b: Peer) = a.nodeId == b.nodeId
            override fun areContentsTheSame(a: Peer, b: Peer) = a == b
        }
    }
}
