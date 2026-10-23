package com.example.data

import android.content.Context
import com.example.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Repository abstracting data access for the playback queue and playlists.
 */
class MusicRepository(context: Context) {

    private val db = MusicDatabase.getInstance(context)
    private val queueDao = db.queueDao()
    private val playlistDao = db.playlistDao()
    private val scope = CoroutineScope(Dispatchers.IO)

    // =========================================================================
    //  Queue Operations
    // =========================================================================

    val queueFlow: Flow<List<QueueItemEntity>> = queueDao.getQueueFlow()
    val currentlyPlayingFlow: Flow<QueueItemEntity?> = queueDao.getCurrentPlayingItem()

    suspend fun getQueueSync(): List<QueueItemEntity> = withContext(Dispatchers.IO) {
        queueDao.getQueueSync()
    }

    suspend fun saveCurrentQueue(songs: List<Song>, currentPlayingPath: String?) = withContext(Dispatchers.IO) {
        val entities = songs.mapIndexed { index, song ->
            QueueItemEntity(
                title = song.title,
                artist = song.artist,
                path = song.path,
                orderIndex = index,
                isCurrentlyPlaying = (song.path == currentPlayingPath)
            )
        }
        queueDao.replaceQueue(entities)
    }

    suspend fun updateCurrentlyPlaying(path: String) = withContext(Dispatchers.IO) {
        queueDao.updateCurrentlyPlaying(path)
    }

    suspend fun addToQueue(title: String, artist: String, path: String) = withContext(Dispatchers.IO) {
        val currentQueue = queueDao.getQueueSync()
        val nextIndex = (currentQueue.maxOfOrNull { it.orderIndex } ?: -1) + 1
        queueDao.insert(
            QueueItemEntity(
                title = title,
                artist = artist,
                path = path,
                orderIndex = nextIndex
            )
        )
    }

    suspend fun removeFromQueue(path: String) = withContext(Dispatchers.IO) {
        queueDao.deleteByPath(path)
    }

    suspend fun clearQueue() = withContext(Dispatchers.IO) {
        queueDao.clearQueue()
    }

    fun interface Callback<T> {
        fun onResult(result: T)
    }

    // Java-friendly asynchronous queue helpers
    fun saveQueueAsync(songs: List<Song>, currentPath: String?) {
        scope.launch {
            saveCurrentQueue(songs, currentPath)
        }
    }

    fun updateCurrentlyPlayingAsync(path: String) {
        scope.launch {
            updateCurrentlyPlaying(path)
        }
    }

    fun restoreSavedQueueAsync(callback: Callback<List<QueueItemEntity>>) {
        scope.launch {
            val queue = getQueueSync()
            withContext(Dispatchers.Main) {
                callback.onResult(queue)
            }
        }
    }

    // =========================================================================
    //  Playlist Operations
    // =========================================================================

    val playlistsFlow: Flow<List<PlaylistWithSongCount>> = playlistDao.getAllPlaylistsWithCount()

    suspend fun createPlaylist(name: String): Long = withContext(Dispatchers.IO) {
        val entity = PlaylistEntity(name = name.trim())
        playlistDao.insertPlaylist(entity)
    }

    suspend fun deletePlaylist(playlistId: Long) = withContext(Dispatchers.IO) {
        playlistDao.deletePlaylistById(playlistId)
    }

    suspend fun renamePlaylist(playlistId: Long, newName: String) = withContext(Dispatchers.IO) {
        val entity = PlaylistEntity(
            id = playlistId,
            name = newName.trim(),
            updatedAt = System.currentTimeMillis()
        )
        playlistDao.updatePlaylist(entity)
    }

    fun getSongsForPlaylistFlow(playlistId: Long): Flow<List<PlaylistSongEntity>> {
        return playlistDao.getSongsForPlaylist(playlistId)
    }

    suspend fun getSongsForPlaylistSync(playlistId: Long): List<PlaylistSongEntity> = withContext(Dispatchers.IO) {
        playlistDao.getSongsForPlaylistSync(playlistId)
    }

    suspend fun addSongToPlaylist(playlistId: Long, song: Song): Long = withContext(Dispatchers.IO) {
        playlistDao.addSongToEndOfPlaylist(
            playlistId = playlistId,
            title = song.title,
            artist = song.artist,
            path = song.path
        )
    }

    suspend fun removeSongFromPlaylist(songId: Long) = withContext(Dispatchers.IO) {
        playlistDao.deleteSongById(songId)
    }

    // Java-friendly asynchronous playlist helpers
    fun addSongToPlaylistAsync(playlistId: Long, song: Song, onComplete: Callback<Long>? = null) {
        scope.launch {
            val id = addSongToPlaylist(playlistId, song)
            withContext(Dispatchers.Main) {
                onComplete?.onResult(id)
            }
        }
    }

    fun createPlaylistAsync(name: String, onComplete: Callback<Long>? = null) {
        scope.launch {
            val id = createPlaylist(name)
            withContext(Dispatchers.Main) {
                onComplete?.onResult(id)
            }
        }
    }

    fun getAllPlaylistsAsync(callback: Callback<List<PlaylistWithSongCount>>) {
        scope.launch {
            val playlists = withContext(Dispatchers.IO) {
                playlistDao.getAllPlaylistsWithCountSync()
            }
            withContext(Dispatchers.Main) {
                callback.onResult(playlists)
            }
        }
    }

    fun getSongsForPlaylistAsync(playlistId: Long, callback: Callback<List<PlaylistSongEntity>>) {
        scope.launch {
            val songs = getSongsForPlaylistSync(playlistId)
            withContext(Dispatchers.Main) {
                callback.onResult(songs)
            }
        }
    }

    fun deletePlaylistAsync(playlistId: Long, onComplete: Runnable? = null) {
        scope.launch {
            deletePlaylist(playlistId)
            withContext(Dispatchers.Main) {
                onComplete?.run()
            }
        }
    }
}
