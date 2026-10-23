package com.example.visualizer

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * Visualizer rendering styles supported by the Compose Canvas audio spectrum.
 */
enum class VisualizerMode(val displayName: String, val description: String) {
    BAR_SPECTRUM("Barres FFT", "Barres de fréquences avec crêtes flottantes"),
    NEON_WAVEFORM("Onde Néon", "Courbe d'onde continue lissée et luminescente"),
    MIRRORED_BARS("Spectre Miroir", "Barres symétriques avec pulsation des basses"),
    CIRCULAR_SPECTRUM("Halo Circulaire", "Rayonnement radial réactif au rythme")
}

/**
 * Color palettes for the spectrum visualizer.
 */
enum class VisualizerTheme(val displayName: String, val colors: List<Color>) {
    SPOTIFY_GREEN(
        "Émeraude",
        listOf(
            Color(0xFF1DB954),
            Color(0xFF1ED760),
            Color(0xFFA3E635)
        )
    ),
    NEON_CYAN_PURPLE(
        "Cyber Neon",
        listOf(
            Color(0xFF00F5D4),
            Color(0xFF00BBF9),
            Color(0xFF9B5DE5),
            Color(0xFFF15BB5)
        )
    ),
    SUNSET_FIRE(
        "Feu Couchant",
        listOf(
            Color(0xFFFF007F),
            Color(0xFFFF5400),
            Color(0xFFFFBD00)
        )
    ),
    OCEAN_BLUE(
        "Océan Profond",
        listOf(
            Color(0xFF0077B6),
            Color(0xFF00B4D8),
            Color(0xFF90E0EF)
        )
    ),
    RGB_AURORA(
        "Aurore Boréale",
        listOf(
            Color(0xFF38EF7D),
            Color(0xFF11998E),
            Color(0xFF00C9FF),
            Color(0xFF92FE9D)
        )
    );

    fun toVerticalBrush(height: Float): Brush {
        return Brush.verticalGradient(
            colors = colors.reversed(),
            startY = 0f,
            endY = height.coerceAtLeast(1f)
        )
    }

    fun toHorizontalBrush(width: Float): Brush {
        return Brush.horizontalGradient(
            colors = colors,
            startX = 0f,
            endX = width.coerceAtLeast(1f)
        )
    }
}
