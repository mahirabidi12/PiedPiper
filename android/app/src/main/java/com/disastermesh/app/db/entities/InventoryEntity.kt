package com.disastermesh.app.db.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.disastermesh.app.model.InventoryItem

@Entity(tableName = "inventory")
data class InventoryEntity(
    @PrimaryKey val key: String,
    val label: String,
    val unit: String,
    val count: Int,
    @ColumnInfo(name = "updated_at")      val updatedAt: Long = 0L,
    @ColumnInfo(name = "updated_by")      val updatedBy: String = "",
    @ColumnInfo(name = "updated_by_name") val updatedByName: String = "",
    @ColumnInfo(name = "is_deleted")      val isDeleted: Boolean = false
) {
    fun toDomain() = InventoryItem(key, label, unit, count, updatedAt, updatedBy, updatedByName)
}
