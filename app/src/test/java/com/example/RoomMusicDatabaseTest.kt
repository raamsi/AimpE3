package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.MusicDatabase
import com.example.data.PlaylistDao
import com.example.data.PlaylistEntity
import com.example.data.QueueDao
import com.example.data.QueueItemEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomMusicDatabaseTest {

    private lateinit var db: MusicDatabase
    private lateinit var queueDao: QueueDao
    private lateinit var playlistDao: PlaylistDao

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MusicDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        queueDao = db.queueDao()
        playlistDao = db.playlistDao()
    }

    @After
    fun closeDb() {
        db.close()
    }

    @Test
    fun testPlaybackQueueInsertAndOrder() = runBlocking {
        val song1 = QueueItemEntity(title = "Track A", artist = "Artist 1", path = "/music/a.mp3", orderIndex = 0)
        val song2 = QueueItemEntity(title = "Track B", artist = "Artist 2", path = "/music/b.mp3", orderIndex = 1)
        val song3 = QueueItemEntity(title = "Track C", artist = "Artist 3", path = "/music/c.mp3", orderIndex = 2)

        queueDao.insertAll(listOf(song2, song1, song3))

        val queue = queueDao.getQueueSync()
        assertEquals(3, queue.size)
        assertEquals("Track A", queue[0].title)
        assertEquals("Track B", queue[1].title)
        assertEquals("Track C", queue[2].title)
    }

    @Test
    fun testQueueCurrentlyPlayingUpdate() = runBlocking {
        val song1 = QueueItemEntity(title = "Track A", artist = "Artist 1", path = "/music/a.mp3", orderIndex = 0, isCurrentlyPlaying = true)
        val song2 = QueueItemEntity(title = "Track B", artist = "Artist 2", path = "/music/b.mp3", orderIndex = 1, isCurrentlyPlaying = false)
        queueDao.insertAll(listOf(song1, song2))

        queueDao.updateCurrentlyPlaying("/music/b.mp3")

        val queue = queueDao.getQueueSync()
        assertFalse(queue[0].isCurrentlyPlaying)
        assertTrue(queue[1].isCurrentlyPlaying)
    }

    @Test
    fun testPlaylistCreationAndAddSongs() = runBlocking {
        val playlistId = playlistDao.insertPlaylist(PlaylistEntity(name = "Rock Classics"))
        assertTrue(playlistId > 0)

        playlistDao.addSongToEndOfPlaylist(playlistId, "Bohemian Rhapsody", "Queen", "/queen.mp3")
        playlistDao.addSongToEndOfPlaylist(playlistId, "Hotel California", "Eagles", "/eagles.mp3")

        val songs = playlistDao.getSongsForPlaylistSync(playlistId)
        assertEquals(2, songs.size)
        assertEquals("Bohemian Rhapsody", songs[0].title)
        assertEquals(0, songs[0].orderIndex)
        assertEquals("Hotel California", songs[1].title)
        assertEquals(1, songs[1].orderIndex)
    }

    @Test
    fun testPlaylistCascadeDelete() = runBlocking {
        val playlistId = playlistDao.insertPlaylist(PlaylistEntity(name = "Temp Playlist"))
        playlistDao.addSongToEndOfPlaylist(playlistId, "Song 1", "Artist 1", "/s1.mp3")
        playlistDao.addSongToEndOfPlaylist(playlistId, "Song 2", "Artist 2", "/s2.mp3")

        assertEquals(2, playlistDao.getSongsForPlaylistSync(playlistId).size)

        // Delete playlist
        playlistDao.deletePlaylistById(playlistId)

        // Verify songs were deleted via cascade
        val remainingSongs = playlistDao.getSongsForPlaylistSync(playlistId)
        assertEquals(0, remainingSongs.size)
    }

    @Test
    fun testPlaylistSongCountQuery() = runBlocking {
        val p1 = playlistDao.insertPlaylist(PlaylistEntity(name = "Favorites"))
        val p2 = playlistDao.insertPlaylist(PlaylistEntity(name = "Empty"))

        playlistDao.addSongToEndOfPlaylist(p1, "Song A", "Artist A", "/a.mp3")
        playlistDao.addSongToEndOfPlaylist(p1, "Song B", "Artist B", "/b.mp3")

        val playlists = playlistDao.getAllPlaylistsWithCountSync()
        assertEquals(2, playlists.size)

        val fav = playlists.first { it.id == p1 }
        assertEquals("Favorites", fav.name)
        assertEquals(2, fav.songCount)

        val empty = playlists.first { it.id == p2 }
        assertEquals("Empty", empty.name)
        assertEquals(0, empty.songCount)
    }
}
