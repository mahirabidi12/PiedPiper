package com.disastermesh

object MeshState {

    @Volatile
    var peerCount: Int = 0
        private set

    private val listeners = mutableListOf<(Int) -> Unit>()

    fun update(count: Int) {
        peerCount = count
        listeners.toList().forEach { it(count) }
    }

    fun addListener(listener: (Int) -> Unit) {
        listeners.add(listener)
        listener(peerCount)
    }

    fun removeListener(listener: (Int) -> Unit) {
        listeners.remove(listener)
    }
}
