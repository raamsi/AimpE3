package com.example.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

data class PlaylistWithSongCount(
    val id: Long,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    val songCount: Int
)

@Dao
interface PlaylistDao {

    @Query("""
        SELECT p.id, p.name, p.createdAt, p.updatedAt, COUNT(s.id) as songCount
        FROM playlists p
        LEFT JOIN playlist_songs s ON p.id = s.playlistId
        GROUP BY p.id
        ORDER BY p.updatedAt DESC
    """)
    fun getAllPlaylistsWithCount(): Flow<List<PlaylistWithSongCount>>

    @Query("""
        SELECT p.id, p.name, p.createdAt, p.updatedAt, COUNT(s.id) as songCount
        FROM playlists p
        LEFT JOIN playlist_songs s ON p.id = s.playlistId
        GROUP BY p.id
        ORDER BY p.updatedAt DESC
    """)
    fun getAllPlaylistsWithCountSync(): List<PlaylistWithSongCount>

    @Query("SELECT * FROM playlists ORDER BY updatedAt DESC")
    fun getAllPlaylists(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlists ORDER BY updatedAt DESC")
    fun getAllPlaylistsSync(): List<PlaylistEntity>

    @Query("SELECT * FROM playlists WHERE id = :id LIMIT 1")
    fun getPlaylistById(id: Long): Flow<PlaylistEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylist(playlist: PlaylistEntity): Long

    @Update
    suspend fun updatePlaylist(playlist: PlaylistEntity)

    @Delete
    suspend fun deletePlaylist(playlist: PlaylistEntity)

    @Query("DELETE FROM playlists WHERE id = :playlistId")
    suspend fun deletePlaylistById(playlistId: Long)

    // Playlist Songs
    @Query("SELECT * FROM playlist_songs WHERE playlistId = :playlistId ORDER BY orderIndex ASC")
    fun getSongsForPlaylist(playlistId: Long): Flow<List<PlaylistSongEntity>>

    @Query("SELECT * FROM playlist_songs WHERE playlistId = :playlistId ORDER BY orderIndex ASC")
    fun getSongsForPlaylistSync(playlistId: Long): List<PlaylistSongEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSong(song: PlaylistSongEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSongs(songs: List<PlaylistSongEntity>)

    @Query("DELETE FROM playlist_songs WHERE id = :songId")
    suspend fun deleteSongById(songId: Long)

    @Query("DELETE FROM playlist_songs WHERE playlistId = :playlistId")
    suspend fun clearPlaylistSongs(playlistId: Long)

    @Query("SELECT MAX(orderIndex) FROM playlist_songs WHERE playlistId = :playlistId")
    suspend fun getMaxOrderIndex(playlistId: Long): Int?

    @Transaction
    suspend fun addSongToEndOfPlaylist(playlistId: Long, title: String, artist: String, path: String): Long {
        val nextIndex = (getMaxOrderIndex(playlistId) ?: -1) + 1
        val song = PlaylistSongEntity(
            playlistId = playlistId,
            title = title,
            artist = artist,
            path = path,
            orderIndex = nextIndex
        )
        val id = insertSong(song)
        touchPlaylist(playlistId, System.currentTimeMillis())
        return id
    }

    @Query("UPDATE playlists SET updatedAt = :timestamp WHERE id = :playlistId")
    suspend fun touchPlaylist(playlistId: Long, timestamp: Long)
}
