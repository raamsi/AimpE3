package com.example

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

/**
 * Data model for a lyric line being edited in real-time.
 */
data class EditableLyricLine(
    val id: Int,
    val timeMs: Int,
    val text: String
)

/**
 * Modern Jetpack Compose UI component that allows users to manually shift
 * and adjust timestamps for lyrics in real-time while a song is playing.
 *
 * @param songTitle Title of the song currently playing.
 * @param initialLyrics List of initial lyric lines with timestamps.
 * @param currentPositionMs Current playback position in milliseconds.
 * @param durationMs Total duration of the song in milliseconds.
 * @param isPlaying True if audio is actively playing.
 * @param onPlayPause Toggled when user presses Play/Pause.
 * @param onSeekTo Called when user seeks audio position.
 * @param onSaveLyrics Called with the updated list of lyric lines when user finishes.
 * @param onClose Called when user cancels or closes the adjuster.
 */
@Composable
fun LyricsTimingAdjuster(
    songTitle: String,
    initialLyrics: List<EditableLyricLine>,
    currentPositionMs: Int,
    durationMs: Int,
    isPlaying: Boolean,
    onPlayPause: () -> Unit,
    onSeekTo: (Int) -> Unit,
    onSaveLyrics: (List<EditableLyricLine>) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Local editable copy of lyric entries
    val lyrics = remember(initialLyrics) {
        mutableStateListOf<EditableLyricLine>().apply {
            addAll(initialLyrics.sortedBy { it.timeMs })
        }
    }

    val listState = rememberLazyListState()
    var autoScrollEnabled by remember { mutableStateOf(true) }

    // Active line index based on current playback position
    val activeIndex = remember(currentPositionMs, lyrics.size) {
        var idx = -1
        for (i in lyrics.indices) {
            if (lyrics[i].timeMs <= currentPositionMs) {
                idx = i
            } else {
                break
            }
        }
        idx
    }

    // Auto-scroll to active line during playback unless manually detached
    LaunchedEffect(activeIndex, autoScrollEnabled) {
        if (autoScrollEnabled && activeIndex >= 0 && activeIndex < lyrics.size) {
            listState.animateScrollToItem(activeIndex)
        }
    }

    Surface(
        modifier = modifier
            .fillMaxSize()
            .testTag("lyrics_timing_adjuster_root"),
        color = Color(0xFF0B0B10)
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // 1. Top Header Bar
            TopHeaderBar(
                songTitle = songTitle,
                onClose = onClose,
                onSave = { onSaveLyrics(lyrics.toList()) },
                onReset = {
                    lyrics.clear()
                    lyrics.addAll(initialLyrics.sortedBy { it.timeMs })
                }
            )

            // 2. Global Shift Controls Banner
            GlobalShiftBanner(
                onGlobalShift = { deltaMs ->
                    for (i in lyrics.indices) {
                        val newTime = (lyrics[i].timeMs + deltaMs).coerceAtLeast(0)
                        lyrics[i] = lyrics[i].copy(timeMs = newTime)
                    }
                }
            )

            // 3. Lyrics List with Individual Line Adjustment Cards
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .testTag("lyrics_list"),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(
                    items = lyrics,
                    key = { _, item -> item.id }
                ) { index, item ->
                    val isActive = index == activeIndex

                    LyricAdjusterRow(
                        index = index,
                        item = item,
                        isActive = isActive,
                        currentPlaybackMs = currentPositionMs,
                        onSeekTo = {
                            onSeekTo(item.timeMs)
                            autoScrollEnabled = true
                        },
                        onAdjustTime = { deltaMs ->
                            val newTime = (item.timeMs + deltaMs).coerceAtLeast(0)
                            lyrics[index] = item.copy(timeMs = newTime)
                        },
                        onSnapToNow = {
                            lyrics[index] = item.copy(timeMs = currentPositionMs)
                        }
                    )
                }
            }

            // 4. Bottom Playback Control Dock
            PlaybackControlDock(
                currentPositionMs = currentPositionMs,
                durationMs = durationMs,
                isPlaying = isPlaying,
                autoScroll = autoScrollEnabled,
                onToggleAutoScroll = { autoScrollEnabled = !autoScrollEnabled },
                onPlayPause = onPlayPause,
                onSeek = onSeekTo
            )
        }
    }
}

@Composable
private fun TopHeaderBar(
    songTitle: String,
    onClose: () -> Unit,
    onSave: () -> Unit,
    onReset: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF16161C))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = onClose,
            modifier = Modifier.testTag("btn_close_adjuster")
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Fermer",
                tint = Color.White
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp)
        ) {
            Text(
                text = "Ajusteur de Paroles en Direct",
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = songTitle,
                color = Color(0xFF1DB954),
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        IconButton(
            onClick = onReset,
            modifier = Modifier.testTag("btn_reset_lyrics")
        ) {
            Icon(
                imageVector = Icons.Default.Restore,
                contentDescription = "Réinitialiser",
                tint = Color(0xFFAAAAAA)
            )
        }

        Button(
            onClick = onSave,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1DB954)),
            shape = RoundedCornerShape(20.dp),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
            modifier = Modifier.testTag("btn_save_lyrics")
        ) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "Sauvegarder",
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun GlobalShiftBanner(
    onGlobalShift: (Int) -> Unit
) {
    Surface(
        color = Color(0xFF1B1B22),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Décalage global :",
                color = Color(0xFFCCCCCC),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ShiftButton(text = "-500ms", onClick = { onGlobalShift(-500) }, tag = "btn_global_minus_500")
                ShiftButton(text = "-100ms", onClick = { onGlobalShift(-100) }, tag = "btn_global_minus_100")
                ShiftButton(text = "+100ms", onClick = { onGlobalShift(100) }, tag = "btn_global_plus_100")
                ShiftButton(text = "+500ms", onClick = { onGlobalShift(500) }, tag = "btn_global_plus_500")
            }
        }
    }
}

@Composable
private fun ShiftButton(
    text: String,
    onClick: () -> Unit,
    tag: String
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF262632))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .testTag(tag),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = Color(0xFFFFAA00),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun LyricAdjusterRow(
    index: Int,
    item: EditableLyricLine,
    isActive: Boolean,
    currentPlaybackMs: Int,
    onSeekTo: () -> Unit,
    onAdjustTime: (Int) -> Unit,
    onSnapToNow: () -> Unit
) {
    val cardBackground by animateColorAsState(
        targetValue = if (isActive) Color(0xFF1D2820) else Color(0xFF16161C),
        animationSpec = tween(200),
        label = "card_bg"
    )

    val borderStroke = if (isActive) {
        BorderStroke(1.5.dp, Color(0xFF1DB954))
    } else {
        BorderStroke(1.dp, Color(0xFF262632))
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSeekTo)
            .testTag("lyric_line_$index"),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = cardBackground),
        border = borderStroke
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            // Text of the lyric line
            Text(
                text = item.text,
                color = if (isActive) Color(0xFF1DB954) else Color.White,
                fontSize = if (isActive) 16.sp else 14.sp,
                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Controls row: Timestamp badge + Nudge buttons + Snap button
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Timestamp badge
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (isActive) Color(0xFF1DB954) else Color(0xFF262632))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = formatMillis(item.timeMs),
                        color = if (isActive) Color.Black else Color(0xFFFFAA00),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Adjust buttons
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // -100ms
                    NudgeButton(label = "-0.1s", onClick = { onAdjustTime(-100) }, tag = "btn_minus_100_$index")

                    // +100ms
                    NudgeButton(label = "+0.1s", onClick = { onAdjustTime(100) }, tag = "btn_plus_100_$index")

                    // "Snap to Now" button
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFFFFAA00))
                            .clickable(onClick = onSnapToNow)
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                            .testTag("btn_snap_now_$index"),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Schedule,
                                contentDescription = null,
                                tint = Color.Black,
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(modifier = Modifier.width(3.dp))
                            Text(
                                text = "Caler",
                                color = Color.Black,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NudgeButton(
    label: String,
    onClick: () -> Unit,
    tag: String
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF262632))
            .clickable(onClick = onClick)
            .padding(horizontal = 7.dp, vertical = 4.dp)
            .testTag(tag),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun PlaybackControlDock(
    currentPositionMs: Int,
    durationMs: Int,
    isPlaying: Boolean,
    autoScroll: Boolean,
    onToggleAutoScroll: () -> Unit,
    onPlayPause: () -> Unit,
    onSeek: (Int) -> Unit
) {
    Surface(
        color = Color(0xFF16161C),
        modifier = Modifier.fillMaxWidth(),
        shadowElevation = 8.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            // Seek bar with time labels
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = formatMillis(currentPositionMs),
                    color = Color(0xFF1DB954),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = if (autoScroll) "Auto-scroll: ON" else "Auto-scroll: OFF",
                    color = if (autoScroll) Color(0xFF1DB954) else Color(0xFF888888),
                    fontSize = 11.sp,
                    modifier = Modifier
                        .clickable(onClick = onToggleAutoScroll)
                        .testTag("btn_toggle_autoscroll")
                )

                Text(
                    text = formatMillis(durationMs),
                    color = Color(0xFFAAAAAA),
                    fontSize = 12.sp
                )
            }

            Slider(
                value = if (durationMs > 0) currentPositionMs.coerceIn(0, durationMs).toFloat() else 0f,
                onValueChange = { onSeek(it.toInt()) },
                valueRange = 0f..(durationMs.coerceAtLeast(1).toFloat()),
                colors = SliderDefaults.colors(
                    thumbColor = Color(0xFF1DB954),
                    activeTrackColor = Color(0xFF1DB954),
                    inactiveTrackColor = Color(0xFF33333E)
                ),
                modifier = Modifier.testTag("playback_slider")
            )

            // Playback buttons
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Rewind 5s
                IconButton(
                    onClick = { onSeek((currentPositionMs - 5000).coerceAtLeast(0)) },
                    modifier = Modifier.testTag("btn_rewind_5s")
                ) {
                    Icon(
                        imageVector = Icons.Default.FastRewind,
                        contentDescription = "Reculer 5s",
                        tint = Color.White
                    )
                }

                Spacer(modifier = Modifier.width(16.dp))

                // Play / Pause Circle Button
                Box(
                    modifier = Modifier
                        .size(54.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                        .clickable(onClick = onPlayPause)
                        .testTag("btn_play_pause"),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Lecture",
                        tint = Color.Black,
                        modifier = Modifier.size(32.dp)
                    )
                }

                Spacer(modifier = Modifier.width(16.dp))

                // Forward 5s
                IconButton(
                    onClick = { onSeek((currentPositionMs + 5000).coerceAtMost(durationMs)) },
                    modifier = Modifier.testTag("btn_forward_5s")
                ) {
                    Icon(
                        imageVector = Icons.Default.FastForward,
                        contentDescription = "Avancer 5s",
                        tint = Color.White
                    )
                }
            }
        }
    }
}

private fun formatMillis(ms: Int): String {
    val totalSec = (ms.coerceAtLeast(0)) / 1000
    val min = totalSec / 60
    val sec = totalSec % 60
    val hundredths = (ms.coerceAtLeast(0) % 1000) / 10
    return String.format(Locale.US, "%02d:%02d.%02d", min, sec, hundredths)
}
