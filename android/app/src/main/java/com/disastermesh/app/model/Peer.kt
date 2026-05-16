package com.disastermesh.app.model

data class Peer(
    val nodeId: String,
    val name: String,
    val role: Role,
    val endpointId: String?,        // volatile — null when not currently connected
    val connectionState: PeerState,
    val firstSeen: Long,
    val lastSeen: Long
)

enum class PeerState { CONNECTED, SEEN, LOST }
