package com.disastermesh.civilian

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.disastermesh.R

class SentSignalAdapter : RecyclerView.Adapter<SentSignalAdapter.VH>() {

    private val items = mutableListOf<String>()

    fun addItem(summary: String) {
        items.add(0, summary)
        notifyItemInserted(0)
    }

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val tvText: TextView = view.findViewById(R.id.tvSentText)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        return VH(
            LayoutInflater.from(parent.context).inflate(R.layout.item_sent_signal, parent, false)
        )
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.tvText.text = "Queued: ${items[position]}"
    }

    override fun getItemCount(): Int = items.size
}
