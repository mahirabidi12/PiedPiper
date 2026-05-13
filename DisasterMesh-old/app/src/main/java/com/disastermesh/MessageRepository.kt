package com.disastermesh

import android.content.Context
import com.disastermesh.db.AppDatabase
import com.disastermesh.db.MessageEntity

class MessageRepository(context: Context) {

    private val dao = AppDatabase.getInstance(context).messageDao()

    // In-memory dedup set — pre-loaded from DB on startup
    private val seenIds = mutableSetOf<String>()

    // In-memory list for the UI — pre-loaded from DB on startup
    private val _messages = mutableListOf<Message>()

    val messages: List<Message> get() = _messages.toList()

    init {
        // Load persisted state from SQLite on startup
        val stored = dao.getAll()               // newest first (ORDER BY timestamp DESC)
        stored.forEach { entity ->
            seenIds.add(entity.id)
            _messages.add(entity.toMessage())
        }
    }

    // Returns true if message is NEW — caller should display + forward it
    // Returns false if already seen — caller should silently drop it
    fun add(message: Message): Boolean {
        if (seenIds.contains(message.id)) return false
        seenIds.add(message.id)
        _messages.add(0, message)               // newest first in memory
        dao.insert(MessageEntity.fromMessage(message))  // persist to SQLite
        return true
    }

    // Returns all messages for store-and-forward to new peers
    fun getAllForSync(): List<Message> = dao.getAll().map { it.toMessage() }

    fun clear() {
        seenIds.clear()
        _messages.clear()
        dao.clear()
    }
}
