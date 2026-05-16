package com.disastermesh.app

import android.app.Application
import android.util.Log
import com.disastermesh.app.db.AppDatabase
import com.disastermesh.app.notification.SignalNotificationManager

class DisasterMeshApp : Application() {

    val database: AppDatabase by lazy { AppDatabase.getInstance(this) }

    override fun onCreate() {
        super.onCreate()
        SignalNotificationManager.createChannel(this)
        Log.d("DisasterMeshApp", "App started")
    }
}
