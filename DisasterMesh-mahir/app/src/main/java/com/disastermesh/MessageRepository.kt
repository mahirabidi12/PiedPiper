package com.disastermesh

class MessageRepository {
    private val seenIds = mutableSetOf<String>()
    private val _messages = mutableListOf<Message>()

    val messages: List<Message> get() = _messages.toList()

    // Returns true if message is NEW — caller should display + forward it
    // Returns false if already seen — caller should silently drop it
    fun add(message: Message): Boolean {
        if (seenIds.contains(message.id)) return false
        seenIds.add(message.id)
        _messages.add(0, message) // newest first
        return true
    }

    fun clear() {
        seenIds.clear()
        _messages.clear()
    }
}
