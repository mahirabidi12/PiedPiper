package com.disastermesh.app.db.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One turn inside an [AiSessionEntity] — either a user query or an AI response.
 *
 * - `pinned` (Feature 2):  vital advice the user marked for instant offline
 *   retrieval — these float to the top of the session dashboard.
 * - `archived` (Feature 3): rows past the active-message cap are flagged
 *   instead of deleted, so they stay available for sitrep export but vanish
 *   from the live chat view, keeping the active query small and fast.
 */
@Entity(
    tableName = "ai_messages",
    indices = [Index("session_id"), Index("created_at"), Index("pinned")]
)
data class AiMessageEntity(
    @PrimaryKey
    val id: String,

    @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "is_user")    val isUser: Boolean,
    val text: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,

    /** Feature 2 — pinned emergency snippets float to the top of the dashboard. */
    val pinned: Boolean = false,

    /** Feature 3 — soft-archived rows are excluded from active queries but retained. */
    val archived: Boolean = false
)
