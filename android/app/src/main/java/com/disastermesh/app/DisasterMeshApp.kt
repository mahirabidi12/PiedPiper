package com.disastermesh.app

import android.app.Application
import android.util.Log
import com.disastermesh.app.db.AppDatabase
import com.disastermesh.app.notification.SignalNotificationManager
import com.disastermesh.app.notification.TicketNotificationManager

class DisasterMeshApp : Application() {

    val database: AppDatabase by lazy { AppDatabase.getInstance(this) }

    override fun onCreate() {
        super.onCreate()
        SignalNotificationManager.createChannel(this)
        TicketNotificationManager.createChannel(this)
        Log.d("DisasterMeshApp", "App started")
    }
}
