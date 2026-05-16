package com.disastermesh.app.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.disastermesh.app.model.Signal
import com.disastermesh.app.model.SignalPriority
import com.disastermesh.app.ui.MainActivity

object TicketNotificationManager {

    const val CHANNEL_ID = "ticket_assignments"

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Ticket Assignments",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications when you are assigned a ticket"
                enableVibration(true)
                setShowBadge(true)
            }
        )
    }

    /** Notify volunteer when a new ticket is assigned to them. */
    fun notifyAssigned(context: Context, signal: Signal) {
        val nm = context.getSystemService(NotificationManager::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context, signal.id.hashCode(),
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val priorityLabel = when (signal.priority) {
            SignalPriority.CRITICAL -> "CRITICAL"
            SignalPriority.HIGH     -> "HIGH"
            SignalPriority.NORMAL   -> "NORMAL"
            SignalPriority.LOW      -> "LOW"
        }

        val body = buildString {
            append("[$priorityLabel] ${signal.category.label} — ${signal.message.take(80)}")
            if (!signal.instructions.isNullOrBlank()) {
                append("\nInstructions: ${signal.instructions.take(60)}")
            }
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Ticket assigned to you")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setVibrate(longArrayOf(0, 250, 150, 250))
            .build()

        nm.notify("ticket_assigned_${signal.id}".hashCode(), notification)
    }

    /** Notify volunteer when instructions on their ticket were updated. */
    fun notifyInstructionsUpdated(context: Context, signal: Signal) {
        val nm = context.getSystemService(NotificationManager::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context, signal.id.hashCode(),
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val body = "Instructions updated: ${signal.instructions?.take(100) ?: "—"}"

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Ticket instructions updated")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        nm.notify("ticket_instructions_${signal.id}".hashCode(), notification)
    }

    /** Notify volunteer when priority of their ticket changed. */
    fun notifyPriorityChanged(context: Context, signal: Signal, oldPriority: String) {
        val nm = context.getSystemService(NotificationManager::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context, signal.id.hashCode(),
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val body = "Priority changed from $oldPriority to ${signal.priority.name}"

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Ticket priority changed")
            .setContentText(body)
            .setPriority(if (signal.priority == SignalPriority.CRITICAL)
                NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setVibrate(longArrayOf(0, 300, 150, 300))
            .build()

        nm.notify("ticket_priority_${signal.id}".hashCode(), notification)
    }
}
