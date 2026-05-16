package com.disastermesh.app.core

import android.content.Context
import com.disastermesh.app.model.Role

/**
 * Persists the current user's name and role in SharedPreferences.
 * Cleared when the user leaves the mesh.
 */
object UserSession {
    private const val PREFS = "mesh_session"
    private const val KEY_NAME = "name"
    private const val KEY_ROLE = "role"

    data class Session(val name: String, val role: Role)

    fun save(context: Context, name: String, role: Role) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_NAME, name)
            .putString(KEY_ROLE, role.name)
            .apply()
    }

    fun get(context: Context): Session? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val name = prefs.getString(KEY_NAME, null) ?: return null
        val role = Role.fromString(prefs.getString(KEY_ROLE, null) ?: return null)
        return Session(name, role)
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().clear().apply()
    }

    fun isLoggedIn(context: Context) = get(context) != null
}
