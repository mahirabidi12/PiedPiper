package com.disastermesh.app.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.disastermesh.app.R
import com.disastermesh.app.db.entities.AuditLogEntity
import java.util.concurrent.TimeUnit

class AuditLogAdapter : ListAdapter<AuditLogEntity, AuditLogAdapter.VH>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<AuditLogEntity>() {
            override fun areItemsTheSame(a: AuditLogEntity, b: AuditLogEntity) = a.id == b.id
            override fun areContentsTheSame(a: AuditLogEntity, b: AuditLogEntity) = a == b
        }
    }

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val accent      = view.findViewById<View>(R.id.auditAccent)
        val tvAction    = view.findViewById<TextView>(R.id.tvAuditAction)
        val tvLabel     = view.findViewById<TextView>(R.id.tvAuditLabel)
        val tvTime      = view.findViewById<TextView>(R.id.tvAuditTime)
        val tvActor     = view.findViewById<TextView>(R.id.tvAuditActor)
        val tvDetail    = view.findViewById<TextView>(R.id.tvAuditDetail)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_audit_log, parent, false))

    override fun onBindViewHolder(vh: VH, position: Int) {
        val entry = getItem(position)
        val ctx = vh.itemView.context

        vh.tvAction.text  = entry.action
        vh.tvLabel.text   = entry.entityLabel
        vh.tvTime.text    = ageText(entry.createdAt)
        vh.tvActor.text   = entry.actorName
        vh.tvDetail.text  = entry.detail

        val (accentColor, actionColor, actionBg) = when (entry.action) {
            "ADD"    -> Triple(R.color.volunteer,        R.color.volunteer,        R.color.volunteer_tint)
            "DELETE" -> Triple(R.color.priority_critical, R.color.priority_critical, R.color.critical_tint)
            "ADJUST" -> Triple(R.color.civilian,         R.color.civilian,         R.color.civilian_tint)
            else     -> Triple(R.color.text_dim,         R.color.text_muted,       R.color.ink_800)
        }
        vh.accent.setBackgroundColor(ctx.getColor(accentColor))
        vh.tvAction.setTextColor(ctx.getColor(actionColor))
        vh.tvAction.setBackgroundColor(ctx.getColor(actionBg))
    }

    private fun ageText(epochMs: Long): String {
        val diff = System.currentTimeMillis() - epochMs
        return when {
            diff < TimeUnit.MINUTES.toMillis(1) -> "just now"
            diff < TimeUnit.HOURS.toMillis(1)   -> "${TimeUnit.MILLISECONDS.toMinutes(diff)}m ago"
            diff < TimeUnit.DAYS.toMillis(1)    -> "${TimeUnit.MILLISECONDS.toHours(diff)}h ago"
            else                                -> "${TimeUnit.MILLISECONDS.toDays(diff)}d ago"
        }
    }
}
