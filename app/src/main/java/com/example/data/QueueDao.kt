package com.example.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface QueueDao {

    @Query("SELECT * FROM playback_queue ORDER BY orderIndex ASC")
    fun getQueueFlow(): Flow<List<QueueItemEntity>>

    @Query("SELECT * FROM playback_queue ORDER BY orderIndex ASC")
    fun getQueueSync(): List<QueueItemEntity>

    @Query("SELECT * FROM playback_queue WHERE isCurrentlyPlaying = 1 LIMIT 1")
    fun getCurrentPlayingItem(): Flow<QueueItemEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: QueueItemEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<QueueItemEntity>)

    @Update
    suspend fun update(item: QueueItemEntity)

    @Delete
    suspend fun delete(item: QueueItemEntity)

    @Query("DELETE FROM playback_queue WHERE path = :path")
    suspend fun deleteByPath(path: String)

    @Query("DELETE FROM playback_queue")
    suspend fun clearQueue()

    @Query("UPDATE playback_queue SET isCurrentlyPlaying = 0")
    suspend fun resetCurrentPlayingFlags()

    @Query("UPDATE playback_queue SET isCurrentlyPlaying = 1 WHERE path = :path")
    suspend fun setPlayingFlagForPath(path: String)

    @Transaction
    suspend fun updateCurrentlyPlaying(path: String) {
        resetCurrentPlayingFlags()
        setPlayingFlagForPath(path)
    }

    @Transaction
    suspend fun replaceQueue(newItems: List<QueueItemEntity>) {
        clearQueue()
        insertAll(newItems)
    }
}
