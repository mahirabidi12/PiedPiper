package com.disastermesh.app.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.disastermesh.app.model.InventoryItem

@Entity(tableName = "inventory")
data class InventoryEntity(
    @PrimaryKey val key: String,
    val label: String,
    val unit: String,
    val count: Int
) {
    fun toDomain() = InventoryItem(key, label, unit, count)
}
