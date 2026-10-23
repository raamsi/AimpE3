package com.example.download.work

import androidx.work.WorkInfo
import java.util.Locale
import java.util.UUID

data class WorkDownloadItem(
    val id: UUID,
    val title: String,
    val artist: String,
    val url: String,
    val coverUrl: String? = null,
    val filePath: String? = null,
    val progressPercent: Int = 0,
    val bytesDownloaded: Long = 0L,
    val bytesTotal: Long = 0L,
    val speedText: String = "",
    val state: WorkInfo.State = WorkInfo.State.ENQUEUED,
    val errorMessage: String? = null
) {
    val isRunning: Boolean
        get() = state == WorkInfo.State.RUNNING

    val isPending: Boolean
        get() = state == WorkInfo.State.ENQUEUED || state == WorkInfo.State.BLOCKED

    val isFinished: Boolean
        get() = state.isFinished

    val isSuccess: Boolean
        get() = state == WorkInfo.State.SUCCEEDED

    val isFailedOrCancelled: Boolean
        get() = state == WorkInfo.State.FAILED || state == WorkInfo.State.CANCELLED

    val formattedDownloadedSize: String
        get() = formatBytes(bytesDownloaded)

    val formattedTotalSize: String
        get() = if (bytesTotal > 0) formatBytes(bytesTotal) else "--"

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f Mo", bytes / (1024.0 * 1024.0))
            bytes >= 1024 -> String.format(Locale.getDefault(), "%d Ko", bytes / 1024)
            else -> "$bytes o"
        }
    }
}
