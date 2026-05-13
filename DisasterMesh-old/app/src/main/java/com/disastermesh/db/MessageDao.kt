package com.disastermesh.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface MessageDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insert(message: MessageEntity)

    @Query("SELECT * FROM messages ORDER BY timestamp DESC")
    fun getAll(): List<MessageEntity>

    @Query("SELECT id FROM messages")
    fun getAllIds(): List<String>

    @Query("SELECT COUNT(*) FROM messages")
    fun count(): Int

    @Query("DELETE FROM messages")
    fun clear()
}
