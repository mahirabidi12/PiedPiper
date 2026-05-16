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

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Signal Alerts",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Alerts for incoming emergency signals"
            enableVibration(true)
            setShowBadge(true)
        }
        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
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
