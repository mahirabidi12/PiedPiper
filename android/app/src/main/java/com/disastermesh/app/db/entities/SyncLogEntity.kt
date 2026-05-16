package com.disastermesh.app.db.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** Human-readable event feed (INFO / WARN / ERROR) for diagnostics. */
@Entity(tableName = "sync_log")
data class SyncLogEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val level: String = "INFO",     // INFO | WARN | ERROR
    val message: String,
    @ColumnInfo(name = "created_at") val createdAt: Long
)
