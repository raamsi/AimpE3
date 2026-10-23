package com.example

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import java.util.ArrayList

/**
 * Activity hosting the Jetpack Compose LyricsTimingAdjuster component.
 */
class LyricsAdjusterActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val texts = intent?.getStringArrayListExtra("EDIT_LYRICS_TEXT") ?: arrayListOf()
        val times = intent?.getIntegerArrayListExtra("EDIT_LYRICS_TIME") ?: arrayListOf()
        val songTitle = intent?.getStringExtra("SONG_TITLE") ?: "Morceau en cours"

        val initialLyrics = mutableListOf<EditableLyricLine>()
        val count = minOf(texts.size, times.size)
        for (i in 0 until count) {
            initialLyrics.add(EditableLyricLine(id = i, timeMs = times[i], text = texts[i]))
        }

        setContent {
            var currentPos by remember { mutableIntStateOf(MainActivity.getCurrentPositionStatic()) }
            var isPlaying by remember { mutableStateOf(MainActivity.isPlayingStatic()) }
            val duration = remember { MainActivity.getDurationStatic().coerceAtLeast(1) }

            // Polling loop to sync playback time in real-time with Compose UI
            LaunchedEffect(Unit) {
                while (true) {
                    currentPos = MainActivity.getCurrentPositionStatic()
                    isPlaying = MainActivity.isPlayingStatic()
                    delay(50)
                }
            }

            LyricsTimingAdjuster(
                songTitle = songTitle,
                initialLyrics = initialLyrics,
                currentPositionMs = currentPos,
                durationMs = duration,
                isPlaying = isPlaying,
                onPlayPause = {
                    MainActivity.togglePlayPauseStatic()
                    isPlaying = MainActivity.isPlayingStatic()
                },
                onSeekTo = { targetMs ->
                    MainActivity.seekToStatic(targetMs)
                    currentPos = targetMs
                },
                onSaveLyrics = { updatedList ->
                    val resultIntent = Intent().apply {
                        putStringArrayListExtra("NEW_LYRICS_TEXT", ArrayList(updatedList.map { it.text }))
                        putIntegerArrayListExtra("NEW_LYRICS_TIME", ArrayList(updatedList.map { it.timeMs }))
                    }
                    setResult(RESULT_OK, resultIntent)
                    finish()
                },
                onClose = {
                    finish()
                }
            )
        }
    }
}
