package com.example.download.work

import android.content.Context
import android.media.MediaScannerConnection
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.File
import java.util.UUID

class WorkDownloadManager private constructor(private val appContext: Context) {

    private val workManager = WorkManager.getInstance(appContext)

    companion object {
        const val TAG_MUSIC_DOWNLOAD = "tag_music_download"

        @Volatile
        private var instance: WorkDownloadManager? = null

        fun getInstance(context: Context): WorkDownloadManager {
            return instance ?: synchronized(this) {
                instance ?: WorkDownloadManager(context.applicationContext).also { instance = it }
            }
        }
    }

    fun enqueueDownload(
        url: String,
        title: String,
        artist: String = "Artiste inconnu",
        coverUrl: String? = null
    ): UUID {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val inputData = workDataOf(
            MusicDownloadWorker.KEY_URL to url,
            MusicDownloadWorker.KEY_TITLE to title,
            MusicDownloadWorker.KEY_ARTIST to artist,
            MusicDownloadWorker.KEY_COVER_URL to (coverUrl ?: "")
        )

        val workRequest = OneTimeWorkRequestBuilder<MusicDownloadWorker>()
            .setConstraints(constraints)
            .setInputData(inputData)
            .addTag(TAG_MUSIC_DOWNLOAD)
            .build()

        workManager.enqueue(workRequest)
        return workRequest.id
    }

    fun cancelDownload(id: UUID) {
        workManager.cancelWorkById(id)
    }

    fun retryDownload(item: WorkDownloadItem): UUID {
        cancelDownload(item.id)
        return enqueueDownload(item.url, item.title, item.artist, item.coverUrl)
    }

    fun deleteDownload(item: WorkDownloadItem) {
        cancelDownload(item.id)
        item.filePath?.let { path ->
            try {
                val file = File(path)
                if (file.exists()) {
                    file.delete()
                    MediaScannerConnection.scanFile(
                        appContext,
                        arrayOf(path),
                        arrayOf("audio/mpeg"),
                        null
                    )
                }
            } catch (ignored: Exception) {}
        }
    }

    fun clearCompletedDownloads() {
        workManager.pruneWork()
    }

    fun observeDownloads(): Flow<List<WorkDownloadItem>> {
        return workManager.getWorkInfosByTagFlow(TAG_MUSIC_DOWNLOAD).map { workInfos ->
            workInfos.map { workInfo ->
                mapWorkInfoToDownloadItem(workInfo)
            }.sortedByDescending { it.isRunning || it.isPending }
        }
    }

    private fun mapWorkInfoToDownloadItem(workInfo: WorkInfo): WorkDownloadItem {
        val progress = workInfo.progress
        val output = workInfo.outputData

        val title = progress.getString(MusicDownloadWorker.KEY_TITLE)
            ?: output.getString(MusicDownloadWorker.KEY_TITLE)
            ?: "Morceau MP3"

        val artist = progress.getString(MusicDownloadWorker.KEY_ARTIST)
            ?: output.getString(MusicDownloadWorker.KEY_ARTIST)
            ?: "Artiste inconnu"

        val url = progress.getString(MusicDownloadWorker.KEY_URL)
            ?: output.getString(MusicDownloadWorker.KEY_URL)
            ?: ""

        val coverUrl = progress.getString(MusicDownloadWorker.KEY_COVER_URL)
            ?: output.getString(MusicDownloadWorker.KEY_COVER_URL)

        val filePath = output.getString(MusicDownloadWorker.KEY_FILE_PATH)
            ?: progress.getString(MusicDownloadWorker.KEY_FILE_PATH)

        val progressPercent = when (workInfo.state) {
            WorkInfo.State.SUCCEEDED -> 100
            WorkInfo.State.RUNNING -> progress.getInt(MusicDownloadWorker.KEY_PROGRESS, 0)
            else -> 0
        }

        val bytesDownloaded = when (workInfo.state) {
            WorkInfo.State.SUCCEEDED -> output.getLong(MusicDownloadWorker.KEY_BYTES_DOWNLOADED, 0L)
            else -> progress.getLong(MusicDownloadWorker.KEY_BYTES_DOWNLOADED, 0L)
        }

        val bytesTotal = when (workInfo.state) {
            WorkInfo.State.SUCCEEDED -> output.getLong(MusicDownloadWorker.KEY_BYTES_TOTAL, bytesDownloaded)
            else -> progress.getLong(MusicDownloadWorker.KEY_BYTES_TOTAL, 0L)
        }

        val speedText = when (workInfo.state) {
            WorkInfo.State.RUNNING -> progress.getString(MusicDownloadWorker.KEY_SPEED_TEXT) ?: "En cours..."
            WorkInfo.State.SUCCEEDED -> "Terminé ✓"
            WorkInfo.State.FAILED -> "Échoué"
            WorkInfo.State.CANCELLED -> "Annulé"
            WorkInfo.State.ENQUEUED -> "En attente..."
            WorkInfo.State.BLOCKED -> "En attente du réseau..."
        }

        val error = output.getString(MusicDownloadWorker.KEY_ERROR)

        return WorkDownloadItem(
            id = workInfo.id,
            title = title,
            artist = artist,
            url = url,
            coverUrl = coverUrl,
            filePath = filePath,
            progressPercent = progressPercent,
            bytesDownloaded = bytesDownloaded,
            bytesTotal = bytesTotal,
            speedText = speedText,
            state = workInfo.state,
            errorMessage = error
        )
    }
}
