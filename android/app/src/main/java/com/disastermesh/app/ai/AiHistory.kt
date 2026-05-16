package com.disastermesh.app.ai

import com.disastermesh.app.db.AppDatabase
import com.disastermesh.app.db.entities.AiMessageEntity
import com.disastermesh.app.db.entities.AiSessionEntity
import java.util.UUID

/**
 * One-stop helper for the disaster-optimized chat-history layer.
 *
 * Feature map (matches spec):
 *  1. Session isolation        → [seedDefaultTopics] + [AppDatabase.aiSessionDao]
 *  2. Pinned emergency snippets → [setPinned]
 *  3. Compact SQLite cap        → [capSession] (called from [appendMessage])
 *  4. Mesh-exportable sitrep    → [exportSitrep]
 *  5. Battery-saver truncation  → [batterySaverWindow]
 *
 * All DB work happens on the caller's coroutine context — pick Dispatchers.IO.
 */
object AiHistory {

    /** Feature 3 — soft cap before old messages get archived. */
    const val SESSION_MESSAGE_CAP = 100

    /** Feature 5 — default in-memory bubble count when battery is low. */
    const val BATTERY_SAVER_WINDOW = 3

    /** Feature 4 — per-bullet char cap to keep mesh payloads tiny. */
    const val SITREP_BULLET_MAX_CHARS = 240

    /** Feature 4 — overall payload cap (bytes) so a sitrep fits one Nearby payload. */
    const val SITREP_MAX_BYTES = 4_000

    // Stable IDs so the dashboard can deep-link / pin specific topics.
    const val SESSION_MEDICAL    = "ai-medical"
    const val SESSION_SUSTENANCE = "ai-sustenance"
    const val SESSION_SHELTER    = "ai-shelter"

    // ── Feature 1: Session Isolation ──────────────────────────────────────────

    /** Idempotently seed the three predefined disaster topics. */
    suspend fun seedDefaultTopics(db: AppDatabase) {
        val now = System.currentTimeMillis()
        listOf(
            AiSessionEntity(SESSION_MEDICAL,    "MEDICAL",    "Medical Assistant Log",   now, now),
            AiSessionEntity(SESSION_SUSTENANCE, "SUSTENANCE", "Water & Sustenance Guide", now, now),
            AiSessionEntity(SESSION_SHELTER,    "SHELTER",    "Shelter Prep & Aftershock", now, now),
        ).forEach { db.aiSessionDao().upsert(it) }
    }

    /** Create a freeform session on demand (e.g. "+ New Thread"). */
    suspend fun createSession(db: AppDatabase, title: String, topic: String = "FREEFORM"): String {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        db.aiSessionDao().upsert(AiSessionEntity(id, topic, title, now, now))
        return id
    }

    // ── Common write path ─────────────────────────────────────────────────────

    /** Append a turn, bump session activity, then cap. */
    suspend fun appendMessage(
        db: AppDatabase,
        sessionId: String,
        isUser: Boolean,
        text: String
    ): AiMessageEntity {
        val now = System.currentTimeMillis()
        val msg = AiMessageEntity(
            id        = UUID.randomUUID().toString(),
            sessionId = sessionId,
            isUser    = isUser,
            text      = text,
            createdAt = now
        )
        db.aiMessageDao().insert(msg)
        db.aiSessionDao().bumpActivity(sessionId, now)
        capSession(db, sessionId)
        return msg
    }

    // ── Feature 2: Pinned Emergency Snippets ──────────────────────────────────

    suspend fun setPinned(db: AppDatabase, messageId: String, pinned: Boolean) {
        db.aiMessageDao().setPinned(messageId, pinned)
    }

    // ── Feature 3: Compact SQLite Cap ─────────────────────────────────────────

    /**
     * Archive the oldest non-pinned rows once the session exceeds [cap].
     * Pinned snippets are always retained — they are the user's offline survival
     * reference and must not silently disappear.
     */
    suspend fun capSession(db: AppDatabase, sessionId: String, cap: Int = SESSION_MESSAGE_CAP) {
        if (db.aiMessageDao().activeCount(sessionId) > cap) {
            db.aiMessageDao().archivePastCap(sessionId, cap)
        }
    }

    // ── Feature 4: Mesh-Exportable Sitrep ─────────────────────────────────────

    /**
     * Condense a session into a single bulleted text blob suitable for gossip
     * broadcast. AI responses only (user prompts are stripped — peers don't need
     * the question, only the actionable answer). Hard-capped at [SITREP_MAX_BYTES]
     * so it fits in a single Nearby Connections payload.
     */
    suspend fun exportSitrep(db: AppDatabase, sessionId: String): ByteArray {
        val session  = db.aiSessionDao().getById(sessionId) ?: return ByteArray(0)
        val rows     = db.aiMessageDao().allActive(sessionId).filter { !it.isUser }
        if (rows.isEmpty()) return ByteArray(0)

        val sb = StringBuilder("SITREP[").append(session.topic).append("]\n")
        for (row in rows) {
            val line = row.text.replace('\n', ' ').trim().take(SITREP_BULLET_MAX_CHARS)
            val next = "- $line\n"
            // Don't exceed the wire-payload cap; truncate gracefully.
            if (sb.length + next.length > SITREP_MAX_BYTES) {
                sb.append("- …\n")
                break
            }
            sb.append(next)
        }
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    // ── Feature 5: Battery-Saver Memory Truncation ────────────────────────────

    /**
     * Returns ONLY the latest [keep] active messages, oldest→newest.
     * UI should hold these in RAM; anything older lives in SQLite and is
     * paged in on demand when the user scrolls up.
     *
     * Call this from the battery-low broadcast hook with [keep] = 3 to force
     * the chat adapter to drop its in-memory backlog.
     */
    suspend fun batterySaverWindow(
        db: AppDatabase,
        sessionId: String,
        keep: Int = BATTERY_SAVER_WINDOW
    ): List<AiMessageEntity> =
        db.aiMessageDao().latestN(sessionId, keep).asReversed()
}
