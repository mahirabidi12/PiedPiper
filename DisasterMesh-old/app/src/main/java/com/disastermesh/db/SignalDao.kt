package com.disastermesh.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface SignalDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insert(signal: SignalEntity)

    @Query("SELECT * FROM signals ORDER BY timestamp DESC")
    fun getAll(): List<SignalEntity>

    @Query("SELECT * FROM signals WHERE status != 'RESOLVED' ORDER BY timestamp DESC")
    fun getActive(): List<SignalEntity>

    @Query("SELECT * FROM signals WHERE senderId = :senderId ORDER BY timestamp DESC")
    fun getBySender(senderId: String): List<SignalEntity>

    @Query("SELECT * FROM signals WHERE category = :category ORDER BY timestamp DESC")
    fun getByCategory(category: String): List<SignalEntity>

    @Query("UPDATE signals SET status = :status WHERE id = :id")
    fun updateStatus(id: String, status: String)

    @Query("SELECT COUNT(*) FROM signals")
    fun count(): Int

    @Query("DELETE FROM signals")
    fun clear()
}
