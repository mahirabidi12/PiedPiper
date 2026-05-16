package com.disastermesh.app.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.disastermesh.app.R
import com.disastermesh.app.model.Signal
import com.disastermesh.app.model.SignalPriority
import com.disastermesh.app.ui.MainActivity

object SignalNotificationManager {

    const val CHANNEL_ID = "signal_alerts"
    const val BROADCAST_CHANNEL_ID = "authority_broadcasts"

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Signal Alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Alerts for incoming emergency signals"
                enableVibration(true)
                setShowBadge(true)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(BROADCAST_CHANNEL_ID, "Authority Broadcasts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Emergency broadcasts from authorities"
                enableVibration(true)
                setShowBadge(true)
            }
        )
    }

    fun notifyBroadcast(context: Context, senderName: String, message: String) {
        val nm = context.getSystemService(NotificationManager::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, BROADCAST_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("⚡ BROADCAST from $senderName")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setVibrate(longArrayOf(0, 300, 150, 300, 150, 300))
            .build()
        nm.notify("broadcast".hashCode(), notification)
    }

    fun notify(context: Context, signal: Signal) {
        val nm = context.getSystemService(NotificationManager::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val priorityLabel = when (signal.priority) {
            SignalPriority.CRITICAL -> "CRITICAL"
            SignalPriority.HIGH     -> "HIGH"
            else                   -> signal.priority.name
        }

        val title = "${signal.category.icon} $priorityLabel · ${signal.category.label}"
        val body  = "${signal.senderName}: ${signal.message.take(100)}"

        val notifPriority = if (signal.priority == SignalPriority.CRITICAL)
            NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_HIGH

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(notifPriority)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setVibrate(
                if (signal.priority == SignalPriority.CRITICAL)
                    longArrayOf(0, 400, 200, 400) else longArrayOf(0, 200)
            )
            .build()

        nm.notify(signal.id.hashCode(), notification)
    }
}
