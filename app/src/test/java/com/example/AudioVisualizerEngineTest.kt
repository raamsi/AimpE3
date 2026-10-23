package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.visualizer.AudioVisualizerEngine
import com.example.visualizer.VisualizerMode
import com.example.visualizer.VisualizerTheme
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AudioVisualizerEngineTest {

    private lateinit var context: Context
    private lateinit var engine: AudioVisualizerEngine

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        engine = AudioVisualizerEngine(context)
    }

    @After
    fun tearDown() {
        engine.release()
    }

    @Test
    fun testInitialSnapshotState() = runTest {
        val snapshot = engine.snapshotState.first()
        assertNotNull(snapshot)
        assertEquals(AudioVisualizerEngine.NUM_BANDS, snapshot.magnitudes.size)
        assertEquals(AudioVisualizerEngine.NUM_BANDS, snapshot.peaks.size)
        assertEquals(AudioVisualizerEngine.NUM_WAVEFORM_POINTS, snapshot.waveform.size)
        assertEquals(VisualizerMode.BAR_SPECTRUM, snapshot.mode)
        assertEquals(VisualizerTheme.SPOTIFY_GREEN, snapshot.theme)
        assertFalse(snapshot.isPlaying)
    }

    @Test
    fun testCycleModes() {
        val nextMode = engine.cycleNextMode()
        assertEquals(VisualizerMode.NEON_WAVEFORM, nextMode)

        val mode3 = engine.cycleNextMode()
        assertEquals(VisualizerMode.MIRRORED_BARS, mode3)

        val mode4 = engine.cycleNextMode()
        assertEquals(VisualizerMode.CIRCULAR_SPECTRUM, mode4)

        val mode1Again = engine.cycleNextMode()
        assertEquals(VisualizerMode.BAR_SPECTRUM, mode1Again)
    }

    @Test
    fun testCycleThemes() {
        val nextTheme = engine.cycleNextTheme()
        assertEquals(VisualizerTheme.NEON_CYAN_PURPLE, nextTheme)

        engine.setTheme(VisualizerTheme.SUNSET_FIRE)
        assertEquals(VisualizerTheme.SUNSET_FIRE, engine.snapshotState.value.theme)
    }

    @Test
    fun testSetPlayingState() = runTest {
        engine.setPlaying(true)
        // Wait briefly for simulation tick
        kotlinx.coroutines.delay(100)
        val snapshot = engine.snapshotState.value
        assertTrue(snapshot.isPlaying)
        assertNotNull(snapshot.magnitudes)
    }

    @Test
    fun testSensitivityClamping() {
        engine.setSensitivity(0.1f) // below min 0.4
        // Must clamp to 0.4f
        assertTrue(engine.snapshotState.value.sensitivity >= 0.4f)

        engine.setSensitivity(5.0f) // above max 2.5
        // Must clamp to 2.5f
        assertTrue(engine.snapshotState.value.sensitivity <= 2.5f)
    }

    @Test
    fun testThemeBrushes() {
        val theme = VisualizerTheme.SPOTIFY_GREEN
        assertNotNull(theme.toVerticalBrush(100f))
        assertNotNull(theme.toHorizontalBrush(200f))
        assertTrue(theme.colors.isNotEmpty())
    }
}
