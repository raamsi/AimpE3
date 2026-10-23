package com.example.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Entity representing an item in the persistent playback queue.
 */
@Entity(
    tableName = "playback_queue",
    indices = [Index(value = ["orderIndex"]), Index(value = ["path"])]
)
data class QueueItemEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val artist: String,
    val path: String,
    val orderIndex: Int,
    val isCurrentlyPlaying: Boolean = false,
    val addedAt: Long = System.currentTimeMillis()
)
