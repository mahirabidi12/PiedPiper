package com.disastermesh.app.model

data class InventoryItem(
    val key: String,
    val label: String,
    val unit: String,
    val count: Int,
    val updatedAt: Long = 0L,
    val updatedBy: String = "",
    val updatedByName: String = ""
)
