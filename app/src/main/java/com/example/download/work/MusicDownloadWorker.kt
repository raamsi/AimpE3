package com.example.download.work

import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.download.AudioDownloadManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.Locale
import java.util.concurrent.TimeUnit

class MusicDownloadWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    companion object {
        const val TAG = "MusicDownloadWorker"
        const val KEY_URL = "key_url"
        const val KEY_TITLE = "key_title"
        const val KEY_ARTIST = "key_artist"
        const val KEY_COVER_URL = "key_cover_url"

        const val KEY_PROGRESS = "key_progress"
        const val KEY_BYTES_DOWNLOADED = "key_bytes_downloaded"
        const val KEY_BYTES_TOTAL = "key_bytes_total"
        const val KEY_SPEED_TEXT = "key_speed_text"
        const val KEY_FILE_PATH = "key_file_path"
        const val KEY_ERROR = "key_error"

        val okHttpClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val url = inputData.getString(KEY_URL) ?: return@withContext Result.failure(
            workDataOf(KEY_ERROR to "URL manquante")
        )
        val title = inputData.getString(KEY_TITLE) ?: AudioDownloadManager.extractTitleFromUrl(url)
        val artist = inputData.getString(KEY_ARTIST) ?: "Artiste inconnu"
        val coverUrl = inputData.getString(KEY_COVER_URL) ?: ""

        val safeTitle = AudioDownloadManager.sanitizeFileName(title)
        val fileName = "$safeTitle.mp3"

        // Destination in external Music dir or app private external files
        val musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
            ?: context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
            ?: context.filesDir

        if (!musicDir.exists()) {
            musicDir.mkdirs()
        }

        var targetFile = File(musicDir, fileName)
        var counter = 1
        while (targetFile.exists()) {
            targetFile = File(musicDir, "${safeTitle}_$counter.mp3")
            counter++
        }

        val tempFile = File(musicDir, "${targetFile.name}.tmp")

        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Android; Snaptube MP3 Downloader)")
                .build()

            val response = okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(
                    workDataOf(KEY_ERROR to "Erreur HTTP ${response.code}")
                )
            }

            val body = response.body ?: return@withContext Result.failure(
                workDataOf(KEY_ERROR to "Corps de réponse vide")
            )

            val totalBytes = body.contentLength()
            var downloadedBytes = 0L

            val inputStream: InputStream = body.byteStream()
            val outputStream = FileOutputStream(tempFile)

            val buffer = ByteArray(8192)
            var bytesRead: Int
            var lastUpdateTime = System.currentTimeMillis()
            var bytesSinceLastUpdate = 0L
            var currentSpeedText = "0 Ko/s"

            // Set initial state
            setProgress(
                workDataOf(
                    KEY_PROGRESS to 0,
                    KEY_BYTES_DOWNLOADED to 0L,
                    KEY_BYTES_TOTAL to totalBytes,
                    KEY_SPEED_TEXT to "Démarrage...",
                    KEY_TITLE to title,
                    KEY_ARTIST to artist,
                    KEY_COVER_URL to coverUrl,
                    KEY_URL to url
                )
            )

            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                if (isStopped) {
                    outputStream.close()
                    inputStream.close()
                    tempFile.delete()
                    return@withContext Result.failure(
                        workDataOf(KEY_ERROR to "Téléchargement interrompu par l'utilisateur")
                    )
                }

                outputStream.write(buffer, 0, bytesRead)
                downloadedBytes += bytesRead
                bytesSinceLastUpdate += bytesRead

                val now = System.currentTimeMillis()
                val elapsed = now - lastUpdateTime
                if (elapsed >= 350 || downloadedBytes == totalBytes) {
                    val progressPercent = if (totalBytes > 0) {
                        ((downloadedBytes * 100) / totalBytes).toInt().coerceIn(0, 100)
                    } else {
                        -1
                    }

                    val speedBytesPerSec = if (elapsed > 0) (bytesSinceLastUpdate * 1000) / elapsed else 0L
                    currentSpeedText = formatSpeed(speedBytesPerSec)

                    setProgress(
                        workDataOf(
                            KEY_PROGRESS to progressPercent,
                            KEY_BYTES_DOWNLOADED to downloadedBytes,
                            KEY_BYTES_TOTAL to totalBytes,
                            KEY_SPEED_TEXT to currentSpeedText,
                            KEY_TITLE to title,
                            KEY_ARTIST to artist,
                            KEY_COVER_URL to coverUrl,
                            KEY_URL to url,
                            KEY_FILE_PATH to targetFile.absolutePath
                        )
                    )

                    lastUpdateTime = now
                    bytesSinceLastUpdate = 0L
                }
            }

            outputStream.flush()
            outputStream.close()
            inputStream.close()

            // Rename tmp file to final file
            if (tempFile.renameTo(targetFile)) {
                // Index with MediaScanner so music players detect it immediately
                MediaScannerConnection.scanFile(
                    context,
                    arrayOf(targetFile.absolutePath),
                    arrayOf("audio/mpeg")
                ) { path, uri ->
                    Log.i(TAG, "Indexed downloaded file with MediaScanner: $path -> $uri")
                }

                Result.success(
                    workDataOf(
                        KEY_PROGRESS to 100,
                        KEY_BYTES_DOWNLOADED to targetFile.length(),
                        KEY_BYTES_TOTAL to targetFile.length(),
                        KEY_SPEED_TEXT to "Terminé",
                        KEY_FILE_PATH to targetFile.absolutePath,
                        KEY_TITLE to title,
                        KEY_ARTIST to artist,
                        KEY_COVER_URL to coverUrl
                    )
                )
            } else {
                Result.failure(workDataOf(KEY_ERROR to "Impossible de finaliser le fichier"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Download worker failed: ${e.message}", e)
            if (tempFile.exists()) tempFile.delete()
            Result.failure(workDataOf(KEY_ERROR to (e.message ?: "Erreur réseau inconnue")))
        }
    }

    private fun formatSpeed(bytesPerSec: Long): String {
        return when {
            bytesPerSec >= 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f Mo/s", bytesPerSec / (1024.0 * 1024.0))
            bytesPerSec >= 1024 -> String.format(Locale.getDefault(), "%d Ko/s", bytesPerSec / 1024)
            else -> "$bytesPerSec o/s"
        }
    }
}
