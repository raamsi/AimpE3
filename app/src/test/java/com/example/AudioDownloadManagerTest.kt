package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.download.AudioDownloadManager
import com.example.download.DownloadItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AudioDownloadManagerTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Context>()
    }

    @Test
    fun testTitleExtractionFromUrl() {
        val url1 = "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-1.mp3"
        val title1 = AudioDownloadManager.extractTitleFromUrl(url1)
        assertEquals("SoundHelix Song 1", title1)

        val url2 = "https://example.com/audio/chill_vibes_2024.mp3"
        val title2 = AudioDownloadManager.extractTitleFromUrl(url2)
        assertEquals("chill vibes 2024", title2)

        val emptyTitle = AudioDownloadManager.extractTitleFromUrl("")
        assertTrue(emptyTitle.isNotEmpty())
    }

    @Test
    fun testFileNameSanitization() {
        val dangerous = "Song: \"Best/Hit\" *2024* ? <Special> | End.mp3"
        val clean = AudioDownloadManager.sanitizeFileName(dangerous)
        assertFalse(clean.contains(":"))
        assertFalse(clean.contains("\""))
        assertFalse(clean.contains("/"))
        assertFalse(clean.contains("*"))
        assertFalse(clean.contains("?"))
        assertFalse(clean.contains("<"))
        assertFalse(clean.contains(">"))
        assertFalse(clean.contains("|"))
    }

    @Test
    fun testDownloadItemJsonSerialization() {
        val item = DownloadItem(1001L, "https://test.com/track.mp3", "Test Track", "track.mp3")
        item.status = DownloadItem.STATUS_RUNNING
        item.progress = 42
        item.totalBytes = 10485760L
        item.downloadedBytes = 4404019L
        item.localFilePath = "/storage/emulated/0/Music/track.mp3"

        val json = item.toJson()
        assertNotNull(json)
        assertEquals(1001L, json.getLong("downloadId"))
        assertEquals("Test Track", json.getString("title"))
        assertEquals(42, json.getInt("progress"))
        assertEquals(DownloadItem.STATUS_RUNNING, json.getInt("status"))

        val restored = DownloadItem.fromJson(json)
        assertNotNull(restored)
        assertEquals(item.downloadId, restored!!.downloadId)
        assertEquals(item.url, restored.url)
        assertEquals(item.title, restored.title)
        assertEquals(item.fileName, restored.fileName)
        assertEquals(item.status, restored.status)
        assertEquals(item.progress, restored.progress)
        assertEquals(item.totalBytes, restored.totalBytes)
        assertEquals(item.downloadedBytes, restored.downloadedBytes)
        assertEquals(item.localFilePath, restored.localFilePath)
        assertTrue(restored.isInProgress)
        assertFalse(restored.isSuccessful)
    }

    @Test
    fun testPresetTracksAvailability() {
        val manager = AudioDownloadManager.getInstance(context)
        val presets = manager.presetTracks
        assertNotNull(presets)
        assertTrue("Preset tracks should not be empty", presets.isNotEmpty())
        for (preset in presets) {
            assertTrue(preset.title.isNotEmpty())
            assertTrue(preset.url.startsWith("https://"))
            assertTrue(preset.url.endsWith(".mp3"))
        }
    }

    @Test
    fun testSearchResultItemAndTrending() {
        val trending = com.example.download.MusicSearchEngine.getTrendingTracks()
        assertNotNull(trending)
        assertTrue("Trending list should not be empty", trending.isNotEmpty())

        val first = trending[0]
        assertNotNull(first.title)
        assertNotNull(first.artist)
        assertNotNull(first.streamUrl)
        assertNotNull(first.downloadUrl)
        assertTrue(first.formattedSize.contains("Mo"))

        val customItem = com.example.download.SearchResultItem(
            "test_1",
            "Mon Beau Titre",
            "Super Artiste",
            "03:15",
            "https://example.com/stream.mp3",
            "https://example.com/download.mp3",
            null,
            "Test Source",
            "MP3 320k",
            5242880L
        )
        assertEquals("Mon Beau Titre", customItem.title)
        assertEquals("Super Artiste", customItem.artist)
        assertEquals("5.0 Mo", customItem.formattedSize)
    }
}
