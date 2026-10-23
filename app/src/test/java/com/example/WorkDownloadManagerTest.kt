package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkInfo
import com.example.download.work.MusicDownloadWorker
import com.example.download.work.WorkDownloadItem
import com.example.download.work.WorkDownloadManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WorkDownloadManagerTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Context>()
        try {
            val config = androidx.work.Configuration.Builder()
                .setMinimumLoggingLevel(android.util.Log.DEBUG)
                .build()
            androidx.work.WorkManager.initialize(context, config)
        } catch (ignored: Exception) {
        }
    }

    @Test
    fun testWorkDownloadItemStates() {
        val id = UUID.randomUUID()
        val runningItem = WorkDownloadItem(
            id = id,
            title = "Test Song",
            artist = "Artist",
            url = "https://example.com/song.mp3",
            progressPercent = 45,
            bytesDownloaded = 4500000L,
            bytesTotal = 10000000L,
            speedText = "1.5 Mo/s",
            state = WorkInfo.State.RUNNING
        )

        assertTrue(runningItem.isRunning)
        assertFalse(runningItem.isFinished)
        assertFalse(runningItem.isSuccess)
        assertTrue(runningItem.formattedDownloadedSize.contains("Mo"))
        assertTrue(runningItem.formattedTotalSize.contains("Mo"))

        val completedItem = runningItem.copy(
            state = WorkInfo.State.SUCCEEDED,
            progressPercent = 100,
            filePath = "/storage/emulated/0/Music/song.mp3"
        )
        assertTrue(completedItem.isFinished)
        assertTrue(completedItem.isSuccess)
        assertFalse(completedItem.isRunning)

        val failedItem = runningItem.copy(
            state = WorkInfo.State.FAILED,
            errorMessage = "Erreur réseau"
        )
        assertTrue(failedItem.isFailedOrCancelled)
        assertEquals("Erreur réseau", failedItem.errorMessage)
    }

    @Test
    fun testWorkDownloadManagerEnqueue() {
        val manager = WorkDownloadManager.getInstance(context)
        assertNotNull(manager)

        val workId = manager.enqueueDownload(
            url = "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-1.mp3",
            title = "SoundHelix Song 1",
            artist = "SoundHelix Project"
        )

        assertNotNull(workId)
        manager.cancelDownload(workId)
    }

    @Test
    fun testWorkerConstants() {
        assertEquals("key_url", MusicDownloadWorker.KEY_URL)
        assertEquals("key_title", MusicDownloadWorker.KEY_TITLE)
        assertEquals("key_progress", MusicDownloadWorker.KEY_PROGRESS)
        assertEquals("key_speed_text", MusicDownloadWorker.KEY_SPEED_TEXT)
        assertEquals("key_file_path", MusicDownloadWorker.KEY_FILE_PATH)
    }
}
