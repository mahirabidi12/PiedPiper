package com.disastermesh.app.db.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One conversational thread with the on-device AI.
 *
 * Sessions are crisis-topic-scoped (Feature 1: Session Isolation): instead of
 * one continuous Gemma chat log, the UI shows distinct threads — Medical,
 * Sustenance, Shelter, etc. Each session tracks its own message_count and
 * lastMessageAt so the dashboard can sort by most-recently-active.
 */
@Entity(
    tableName = "ai_sessions",
    indices = [Index("topic"), Index("last_message_at")]
)
data class AiSessionEntity(
    @PrimaryKey
    val id: String,

    /** Topic discriminator: MEDICAL | SUSTENANCE | SHELTER | FREEFORM | CUSTOM */
    val topic: String,

    /** Display label, e.g. "Medical Assistant Log" */
    val title: String,

    @ColumnInfo(name = "created_at")      val createdAt: Long,
    @ColumnInfo(name = "last_message_at") val lastMessageAt: Long,
    @ColumnInfo(name = "message_count")   val messageCount: Int = 0,
    /** Last language used in this session; lets the UI restore the picker. */
    @ColumnInfo(name = "language_code")   val languageCode: String = "en"
)
