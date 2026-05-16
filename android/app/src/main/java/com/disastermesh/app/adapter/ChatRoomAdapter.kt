package com.disastermesh.app.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.disastermesh.app.R
import com.disastermesh.app.databinding.ItemChatRoomBinding
import com.disastermesh.app.model.ChatRooms

class ChatRoomAdapter(
    private val rooms: List<ChatRooms.RoomDef>,
    private val onClick: (ChatRooms.RoomDef) -> Unit
) : RecyclerView.Adapter<ChatRoomAdapter.VH>() {

    inner class VH(val binding: ItemChatRoomBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemChatRoomBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val room = rooms[position]
        val ctx = holder.binding.root.context

        val accentColorRes = when (room.id) {
            "room-all"  -> R.color.room_all
            "room-vol"  -> R.color.room_vol
            "room-auth" -> R.color.room_auth
            else        -> R.color.civilian
        }
        val accentColor = ctx.getColor(accentColorRes)

        holder.binding.roomAccentBar.setBackgroundColor(accentColor)
        holder.binding.roomIconBox.setBackgroundColor(accentColor.withAlpha(25))
        holder.binding.tvRoomName.text = room.name
        holder.binding.tvRoomDesc.text = room.description
        holder.binding.tvLastMsgText.text = ""
        holder.binding.tvLastMsgBadge.visibility = View.GONE
        holder.binding.tvMsgCount.text = ""
        holder.binding.tvUnreadBadge.visibility = View.GONE

        holder.binding.root.setOnClickListener { onClick(room) }
    }

    override fun getItemCount() = rooms.size
}

private fun Int.withAlpha(alpha: Int): Int =
    android.graphics.Color.argb(alpha,
        android.graphics.Color.red(this),
        android.graphics.Color.green(this),
        android.graphics.Color.blue(this))
