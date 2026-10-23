package com.example.visualizer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Pure Jetpack Compose Canvas frequency spectrum visualizer.
 * Renders real-time audio playback using dynamic frequency magnitudes,
 * floating peak markers, smooth Bézier waveforms, and radial circular spectrums.
 */
@Composable
fun AudioSpectrumVisualizer(
    engine: AudioVisualizerEngine,
    modifier: Modifier = Modifier,
    showModeBadge: Boolean = true,
    enableClickToToggleMode: Boolean = true
) {
    val snapshot by engine.snapshotState.collectAsState()

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF0F0F12))
            .testTag("audio_spectrum_container")
            .then(
                if (enableClickToToggleMode) {
                    Modifier.clickable { engine.cycleNextMode() }
                } else Modifier
            )
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .testTag("audio_spectrum_canvas")
        ) {
            val width = size.width
            val height = size.height
            if (width <= 0f || height <= 0f) return@Canvas

            when (snapshot.mode) {
                VisualizerMode.BAR_SPECTRUM -> drawBarSpectrum(snapshot, width, height)
                VisualizerMode.NEON_WAVEFORM -> drawNeonWaveform(snapshot, width, height)
                VisualizerMode.MIRRORED_BARS -> drawMirroredSpectrum(snapshot, width, height)
                VisualizerMode.CIRCULAR_SPECTRUM -> drawCircularSpectrum(snapshot, width, height)
            }
        }

        // Overlay status & mode badge
        if (showModeBadge) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (snapshot.beatDetected) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .background(Color(0xFFFF0055), CircleShape)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                }
                Surface(
                    color = Color.Black.copy(alpha = 0.55f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = snapshot.mode.displayName,
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
        }
    }
}

/**
 * Draws standard frequency spectrum bars with rounded caps and falling peak markers.
 */
private fun DrawScope.drawBarSpectrum(
    snapshot: VisualizerSnapshot,
    width: Float,
    height: Float
) {
    val magnitudes = snapshot.magnitudes
    val peaks = snapshot.peaks
    val count = magnitudes.size
    if (count == 0) return

    val barSpacing = width * 0.01f
    val totalSpacing = barSpacing * (count + 1)
    val barWidth = max(2f, (width - totalSpacing) / count)
    val gradientBrush = snapshot.theme.toVerticalBrush(height)
    val peakColor = snapshot.theme.colors.lastOrNull() ?: Color.White

    // Subtle background grid line
    drawLine(
        color = Color.White.copy(alpha = 0.07f),
        start = Offset(0f, height - 1f),
        end = Offset(width, height - 1f),
        strokeWidth = 1f
    )

    for (i in 0 until count) {
        val x = barSpacing + i * (barWidth + barSpacing)
        val mag = magnitudes[i].coerceIn(0f, 1f)
        val barHeight = max(4f, mag * (height - 8f))
        val top = height - barHeight

        // Draw frequency bar
        drawRoundRect(
            brush = gradientBrush,
            topLeft = Offset(x, top),
            size = Size(barWidth, barHeight),
            cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
        )

        // Draw peak hold indicator
        val peak = peaks[i].coerceIn(0f, 1f)
        val peakTop = (height - max(4f, peak * (height - 8f)) - 3f).coerceAtLeast(0f)
        drawRoundRect(
            color = peakColor.copy(alpha = 0.95f),
            topLeft = Offset(x, peakTop),
            size = Size(barWidth, 2.5f),
            cornerRadius = CornerRadius(1.5f, 1.5f)
        )
    }
}

/**
 * Draws an oscilloscope neon waveform with gradient fill.
 */
private fun DrawScope.drawNeonWaveform(
    snapshot: VisualizerSnapshot,
    width: Float,
    height: Float
) {
    val waveform = snapshot.waveform
    val count = waveform.size
    if (count < 2) return

    val centerY = height * 0.5f
    val wavePath = Path()
    val fillPath = Path()

    val stepX = width / (count - 1)
    val ampScale = (height * 0.42f)

    wavePath.moveTo(0f, centerY + waveform[0] * ampScale)
    fillPath.moveTo(0f, height)
    fillPath.lineTo(0f, centerY + waveform[0] * ampScale)

    for (i in 1 until count) {
        val prevX = (i - 1) * stepX
        val prevY = centerY + waveform[i - 1] * ampScale
        val curX = i * stepX
        val curY = centerY + waveform[i] * ampScale
        val midX = (prevX + curX) / 2f

        wavePath.quadraticTo(prevX, prevY, midX, (prevY + curY) / 2f)
        fillPath.quadraticTo(prevX, prevY, midX, (prevY + curY) / 2f)
    }

    fillPath.lineTo(width, height)
    fillPath.close()

    val fillBrush = Brush.verticalGradient(
        colors = listOf(
            snapshot.theme.colors.first().copy(alpha = 0.45f),
            snapshot.theme.colors.last().copy(alpha = 0.05f)
        ),
        startY = 0f,
        endY = height
    )

    // Glow underfill
    drawPath(fillPath, brush = fillBrush)

    // Bright outer glow line
    drawPath(
        path = wavePath,
        color = snapshot.theme.colors.first().copy(alpha = 0.35f),
        style = Stroke(width = 6f, cap = StrokeCap.Round)
    )

    // Bright central neon wire
    drawPath(
        path = wavePath,
        brush = snapshot.theme.toHorizontalBrush(width),
        style = Stroke(width = 2.5f, cap = StrokeCap.Round)
    )
}

/**
 * Draws mirrored vertical spectrum bars reflected across the vertical center.
 */
private fun DrawScope.drawMirroredSpectrum(
    snapshot: VisualizerSnapshot,
    width: Float,
    height: Float
) {
    val magnitudes = snapshot.magnitudes
    val count = magnitudes.size
    if (count == 0) return

    val centerY = height * 0.5f
    val barSpacing = width * 0.012f
    val totalSpacing = barSpacing * (count + 1)
    val barWidth = max(2f, (width - totalSpacing) / count)
    val gradientBrush = snapshot.theme.toVerticalBrush(height)

    // Center pulse line
    drawLine(
        color = Color.White.copy(alpha = 0.15f),
        start = Offset(0f, centerY),
        end = Offset(width, centerY),
        strokeWidth = 1f
    )

    for (i in 0 until count) {
        val x = barSpacing + i * (barWidth + barSpacing)
        val mag = magnitudes[i].coerceIn(0f, 1f)
        val halfHeight = max(2f, mag * (centerY - 4f))

        // Top half
        drawRoundRect(
            brush = gradientBrush,
            topLeft = Offset(x, centerY - halfHeight),
            size = Size(barWidth, halfHeight),
            cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
        )

        // Bottom reflected half
        drawRoundRect(
            brush = gradientBrush,
            topLeft = Offset(x, centerY),
            size = Size(barWidth, halfHeight),
            cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
        )
    }
}

/**
 * Draws circular radial frequency bars emanating from a center pulse circle.
 */
private fun DrawScope.drawCircularSpectrum(
    snapshot: VisualizerSnapshot,
    width: Float,
    height: Float
) {
    val magnitudes = snapshot.magnitudes
    val count = magnitudes.size
    if (count == 0) return

    val center = Offset(width / 2f, height / 2f)
    val maxRadius = min(width, height) / 2f
    val baseRadius = maxRadius * (0.35f + snapshot.bassLevel * 0.12f)
    val barLengthScale = maxRadius - baseRadius - 6f
    val themeColor = snapshot.theme.colors.first()

    // Pulsing central orb
    drawCircle(
        color = themeColor.copy(alpha = 0.22f),
        radius = baseRadius * 0.95f,
        center = center
    )
    drawCircle(
        color = themeColor.copy(alpha = 0.85f),
        radius = baseRadius * 0.70f,
        center = center
    )

    val angleStep = (2 * PI / count).toFloat()
    for (i in 0 until count) {
        val angle = i * angleStep - (PI / 2).toFloat()
        val mag = magnitudes[i].coerceIn(0f, 1f)
        val len = max(4f, mag * barLengthScale)

        val startX = center.x + cos(angle) * baseRadius
        val startY = center.y + sin(angle) * baseRadius
        val endX = center.x + cos(angle) * (baseRadius + len)
        val endY = center.y + sin(angle) * (baseRadius + len)

        val colorProgress = i.toFloat() / count
        val colorIndex = (colorProgress * (snapshot.theme.colors.size - 1)).toInt()
        val barColor = snapshot.theme.colors[colorIndex.coerceIn(0, snapshot.theme.colors.size - 1)]

        drawLine(
            color = barColor,
            start = Offset(startX, startY),
            end = Offset(endX, endY),
            strokeWidth = max(2.5f, (2 * PI * baseRadius / count).toFloat() * 0.65f),
            cap = StrokeCap.Round
        )
    }
}

/**
 * Complete interactive panel for the visualizer containing mode tabs,
 * color themes, sensitivity slider, and the high-performance spectrum canvas.
 */
@Composable
fun AudioVisualizerCard(
    engine: AudioVisualizerEngine,
    modifier: Modifier = Modifier,
    onExpandFullVisualizer: (() -> Unit)? = null
) {
    val snapshot by engine.snapshotState.collectAsState()
    var expandedControls by remember { mutableStateOf(false) }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("audio_visualizer_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF16161C)),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            // Header: Title & Quick controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { expandedControls = !expandedControls }
                ) {
                    Icon(
                        imageVector = Icons.Default.GraphicEq,
                        contentDescription = "Visualiseur",
                        tint = snapshot.theme.colors.first(),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Spectre Audio Temps Réel",
                        style = MaterialTheme.typography.titleSmall,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Quick mode cycle button
                    Surface(
                        color = Color(0xFF242430),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .clickable { engine.cycleNextMode() }
                            .testTag("btn_cycle_visualizer_mode")
                    ) {
                        Text(
                            text = snapshot.mode.displayName,
                            color = snapshot.theme.colors.first(),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    // Theme cycle button
                    Surface(
                        color = Color(0xFF242430),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .clickable { engine.cycleNextTheme() }
                            .testTag("btn_cycle_visualizer_theme")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Palette,
                            contentDescription = "Palette",
                            tint = Color.White.copy(alpha = 0.8f),
                            modifier = Modifier
                                .size(24.dp)
                                .padding(4.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Main Canvas Component
            AudioSpectrumVisualizer(
                engine = engine,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(68.dp),
                showModeBadge = false,
                enableClickToToggleMode = true
            )

            // Frequency Labels bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("30 Hz", color = Color(0xFF71717A), fontSize = 9.sp)
                Text("250 Hz", color = Color(0xFF71717A), fontSize = 9.sp)
                Text("1 kHz", color = Color(0xFF71717A), fontSize = 9.sp)
                Text("4 kHz", color = Color(0xFF71717A), fontSize = 9.sp)
                Text("16 kHz", color = Color(0xFF71717A), fontSize = 9.sp)
            }

            // Expanded settings (modes, themes, sensitivity)
            AnimatedVisibility(
                visible = expandedControls,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp)
                ) {
                    // Modes Selector Chips
                    Text(
                        text = "Mode de rendu",
                        fontSize = 12.sp,
                        color = Color.LightGray,
                        fontWeight = FontWeight.Medium
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        VisualizerMode.values().forEach { mode ->
                            FilterChip(
                                selected = snapshot.mode == mode,
                                onClick = { engine.setMode(mode) },
                                label = { Text(mode.displayName, fontSize = 11.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = snapshot.theme.colors.first().copy(alpha = 0.25f),
                                    selectedLabelColor = snapshot.theme.colors.first()
                                )
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // Theme Selector Chips
                    Text(
                        text = "Thème de couleur",
                        fontSize = 12.sp,
                        color = Color.LightGray,
                        fontWeight = FontWeight.Medium
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        VisualizerTheme.values().forEach { theme ->
                            FilterChip(
                                selected = snapshot.theme == theme,
                                onClick = { engine.setTheme(theme) },
                                label = { Text(theme.displayName, fontSize = 11.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = theme.colors.first().copy(alpha = 0.25f),
                                    selectedLabelColor = theme.colors.first()
                                )
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    // Sensitivity Slider
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Speed,
                            contentDescription = "Sensibilité",
                            tint = Color.LightGray,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Sensibilité : ${(snapshot.sensitivity * 100).toInt()}%",
                            fontSize = 11.sp,
                            color = Color.LightGray
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        Slider(
                            value = snapshot.sensitivity,
                            onValueChange = { engine.setSensitivity(it) },
                            valueRange = 0.5f..2.0f,
                            modifier = Modifier
                                .width(140.dp)
                                .height(32.dp),
                            colors = SliderDefaults.colors(
                                thumbColor = snapshot.theme.colors.first(),
                                activeTrackColor = snapshot.theme.colors.first()
                            )
                        )
                    }
                }
            }
        }
    }
}
