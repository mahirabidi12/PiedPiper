package com.disastermesh.app.core

import android.content.Context
import java.util.UUID

/**
 * Stable per-device identity.
 *
 * Generated once on first launch, stored in SharedPreferences.
 * This fixes the "two users named Rahul collide" bug in the old codebase —
 * node_id is the canonical identity; display name is just cosmetic.
 */
object NodeIdentity {
    private const val PREFS = "mesh_identity"
    private const val KEY_NODE_ID = "node_id"

    fun get(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString(KEY_NODE_ID, null) ?: UUID.randomUUID().toString().also { id ->
            prefs.edit().putString(KEY_NODE_ID, id).apply()
        }
    }
}
