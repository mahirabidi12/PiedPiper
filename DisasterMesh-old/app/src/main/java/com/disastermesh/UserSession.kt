package com.disastermesh

import android.content.Context

class UserSession(context: Context) {
    private val prefs = context.getSharedPreferences("session", Context.MODE_PRIVATE)

    var name: String
        get() = prefs.getString("name", "") ?: ""
        set(v) { prefs.edit().putString("name", v).apply() }

    var role: Role
        get() = Role.fromName(prefs.getString("role", null))
        set(v) { prefs.edit().putString("role", v.name).apply() }

    val isSetup: Boolean
        get() = prefs.contains("role") && name.isNotBlank()

    fun save(name: String, role: Role) {
        prefs.edit()
            .putString("name", name.trim())
            .putString("role", role.name)
            .apply()
    }

    fun clear() = prefs.edit().clear().apply()
}
