package com.example.download.work

import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy

object SnaptubeDownloadManagerBridge {

    @JvmStatic
    fun attachTo(
        composeView: ComposeView,
        downloadManager: WorkDownloadManager,
        listener: OnTrackPlayListener
    ) {
        composeView.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
        )
        composeView.setContent {
            SnaptubeDownloadManagerComponent(
                downloadManager = downloadManager,
                onPlayTrack = { filePath, title ->
                    listener.onPlayTrack(filePath, title)
                }
            )
        }
    }

    interface OnTrackPlayListener {
        fun onPlayTrack(filePath: String, title: String)
    }
}
