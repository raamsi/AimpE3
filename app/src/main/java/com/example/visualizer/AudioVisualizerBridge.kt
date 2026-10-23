package com.example.visualizer

import android.content.Context
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import com.example.ui.theme.MyApplicationTheme

/**
 * Java-friendly bridge to connect Android Activities and Compose Views
 * to the real-time audio visualization engine.
 */
object AudioVisualizerBridge {

    @JvmStatic
    fun setupVisualizerView(
        composeView: ComposeView,
        engine: AudioVisualizerEngine
    ) {
        composeView.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
        )
        composeView.setContent {
            MyApplicationTheme {
                AudioVisualizerCard(
                    engine = engine,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }

    @JvmStatic
    fun setupCompactSpectrum(
        composeView: ComposeView,
        engine: AudioVisualizerEngine
    ) {
        composeView.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
        )
        composeView.setContent {
            MyApplicationTheme {
                AudioSpectrumVisualizer(
                    engine = engine,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(58.dp),
                    showModeBadge = true,
                    enableClickToToggleMode = true
                )
            }
        }
    }
}
