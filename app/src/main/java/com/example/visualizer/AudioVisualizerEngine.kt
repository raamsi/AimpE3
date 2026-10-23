package com.example.visualizer

import android.content.Context
import android.media.audiofx.Visualizer
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Snapshot of audio visualization data for rendering in Compose Canvas.
 */
data class VisualizerSnapshot(
    val magnitudes: FloatArray,
    val peaks: FloatArray,
    val waveform: FloatArray,
    val bassLevel: Float,
    val beatDetected: Boolean,
    val isPlaying: Boolean,
    val mode: VisualizerMode = VisualizerMode.BAR_SPECTRUM,
    val theme: VisualizerTheme = VisualizerTheme.SPOTIFY_GREEN,
    val sensitivity: Float = 1.0f
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as VisualizerSnapshot
        return magnitudes.contentEquals(other.magnitudes) &&
                peaks.contentEquals(other.peaks) &&
                waveform.contentEquals(other.waveform) &&
                bassLevel == other.bassLevel &&
                beatDetected == other.beatDetected &&
                isPlaying == other.isPlaying &&
                mode == other.mode &&
                theme == other.theme &&
                sensitivity == other.sensitivity
    }

    override fun hashCode(): Int {
        var result = magnitudes.contentHashCode()
        result = 31 * result + peaks.contentHashCode()
        result = 31 * result + waveform.contentHashCode()
        result = 31 * result + bassLevel.hashCode()
        result = 31 * result + beatDetected.hashCode()
        result = 31 * result + isPlaying.hashCode()
        result = 31 * result + mode.hashCode()
        result = 31 * result + theme.hashCode()
        result = 31 * result + sensitivity.hashCode()
        return result
    }
}

/**
 * Real-time audio frequency analysis engine.
 * Captures live hardware audio data via Android Visualizer, extracts FFT magnitudes,
 * calculates smoothed frequency bands and peak gravity points, with an adaptive fallback
 * generator when hardware capture is restricted (e.g. streaming emulator without mic permission).
 */
class AudioVisualizerEngine(private val context: Context) {

    companion object {
        const val NUM_BANDS = 36
        const val NUM_WAVEFORM_POINTS = 64
        private const val ATTACK_FACTOR = 0.65f
        private const val DECAY_FACTOR = 0.82f
        private const val PEAK_DECAY_RATE = 0.018f
        private const val SIMULATION_TICK_MS = 25L
    }

    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private var tickerJob: Job? = null

    private var visualizer: Visualizer? = null
    private var activeSessionId: Int = 0
    private var isPlaying: Boolean = false
    private var lastHardwareDataTime: Long = 0L

    private val currentMagnitudes = FloatArray(NUM_BANDS)
    private val currentPeaks = FloatArray(NUM_BANDS)
    private val currentWaveform = FloatArray(NUM_WAVEFORM_POINTS)
    private var currentBassLevel = 0f
    private var beatTriggered = false
    private var lastBeatTime = 0L

    private var visualizerMode = VisualizerMode.BAR_SPECTRUM
    private var visualizerTheme = VisualizerTheme.SPOTIFY_GREEN
    private var sensitivity: Float = 1.0f

    private val _snapshotState = MutableStateFlow(
        VisualizerSnapshot(
            magnitudes = FloatArray(NUM_BANDS),
            peaks = FloatArray(NUM_BANDS),
            waveform = FloatArray(NUM_WAVEFORM_POINTS),
            bassLevel = 0f,
            beatDetected = false,
            isPlaying = false,
            mode = visualizerMode,
            theme = visualizerTheme,
            sensitivity = sensitivity
        )
    )
    val snapshotState: StateFlow<VisualizerSnapshot> = _snapshotState.asStateFlow()

    init {
        startUpdateLoop()
    }

    /**
     * Links the active MediaPlayer audio session ID to the hardware Visualizer.
     */
    @Synchronized
    fun attachAudioSession(sessionId: Int) {
        if (sessionId <= 0 || sessionId == activeSessionId) return
        this.activeSessionId = sessionId
        releaseHardwareVisualizer()

        try {
            val viz = Visualizer(sessionId)
            val range = Visualizer.getCaptureSizeRange()
            viz.captureSize = range[1] // High resolution (usually 1024)
            viz.setDataCaptureListener(
                object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(
                        v: Visualizer?,
                        waveform: ByteArray?,
                        samplingRate: Int
                    ) {
                        waveform?.let { processHardwareWaveform(it) }
                    }

                    override fun onFftDataCapture(
                        v: Visualizer?,
                        fft: ByteArray?,
                        samplingRate: Int
                    ) {
                        fft?.let { processHardwareFft(it, samplingRate) }
                    }
                },
                Visualizer.getMaxCaptureRate(),
                true,
                true
            )
            viz.enabled = true
            this.visualizer = viz
        } catch (_: Exception) {
            visualizer = null
        }
    }

    /**
     * Updates playback active state.
     */
    @Synchronized
    fun setPlaying(playing: Boolean) {
        this.isPlaying = playing
        _snapshotState.value = _snapshotState.value.copy(isPlaying = playing)
    }

    fun setMode(mode: VisualizerMode) {
        synchronized(this) {
            this.visualizerMode = mode
            _snapshotState.value = _snapshotState.value.copy(mode = mode)
        }
    }

    fun setTheme(theme: VisualizerTheme) {
        synchronized(this) {
            this.visualizerTheme = theme
            _snapshotState.value = _snapshotState.value.copy(theme = theme)
        }
    }

    fun setSensitivity(sens: Float) {
        val clamped = sens.coerceIn(0.4f, 2.5f)
        synchronized(this) {
            this.sensitivity = clamped
            _snapshotState.value = _snapshotState.value.copy(sensitivity = clamped)
        }
    }

    fun cycleNextMode(): VisualizerMode {
        val modes = VisualizerMode.values()
        val nextIndex = (visualizerMode.ordinal + 1) % modes.size
        val nextMode = modes[nextIndex]
        setMode(nextMode)
        return nextMode
    }

    fun cycleNextTheme(): VisualizerTheme {
        val themes = VisualizerTheme.values()
        val nextIndex = (visualizerTheme.ordinal + 1) % themes.size
        val nextTheme = themes[nextIndex]
        setTheme(nextTheme)
        return nextTheme
    }

    private fun startUpdateLoop() {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            var simTime = 0.0f
            while (isActive) {
                val now = SystemClock.uptimeMillis()
                val hardwareActive = (now - lastHardwareDataTime) < 300L

                if (hardwareActive && isPlaying) {
                    // Hardware visualizer is providing live FFT
                    synchronized(this@AudioVisualizerEngine) {
                        updatePeaksAndDecay(isReceiving = true)
                    }
                } else if (isPlaying) {
                    // Fallback procedural FFT synthesis (dynamic music animation)
                    simTime += 0.05f
                    simulateAudioSpectrum(simTime)
                } else {
                    // Idle decay towards zero
                    synchronized(this@AudioVisualizerEngine) {
                        updatePeaksAndDecay(isReceiving = false)
                    }
                }

                // Emit snapshot
                synchronized(this@AudioVisualizerEngine) {
                    _snapshotState.value = VisualizerSnapshot(
                        magnitudes = currentMagnitudes.copyOf(),
                        peaks = currentPeaks.copyOf(),
                        waveform = currentWaveform.copyOf(),
                        bassLevel = currentBassLevel,
                        beatDetected = beatTriggered,
                        isPlaying = isPlaying,
                        mode = visualizerMode,
                        theme = visualizerTheme,
                        sensitivity = sensitivity
                    )
                    beatTriggered = false
                }

                delay(SIMULATION_TICK_MS)
            }
        }
    }

    /**
     * Processes live FFT data from hardware Visualizer.
     * Computes magnitudes and maps them to logarithmic frequency bins.
     */
    private fun processHardwareFft(fft: ByteArray, samplingRate: Int) {
        if (fft.size < 4) return
        lastHardwareDataTime = SystemClock.uptimeMillis()

        val n = fft.size / 2
        val rawMagnitudes = FloatArray(NUM_BANDS)
        val bandCounts = IntArray(NUM_BANDS)

        // Android Visualizer FFT format:
        // fft[0] = DC, fft[1] = Nyquist
        // fft[2k] = real, fft[2k+1] = imag
        val nyquistHz = (samplingRate / 2000f) // kHz
        val minFreq = 20f
        val maxFreq = min(16000f, nyquistHz * 1000f)

        for (k in 1 until n) {
            val r = fft[2 * k].toFloat()
            val i = fft[2 * k + 1].toFloat()
            val mag = hypot(r, i)

            val freq = (k.toFloat() / n) * (samplingRate / 2f / 1000f) * 1000f
            if (freq in minFreq..maxFreq) {
                // Logarithmic frequency binning
                val logNorm = (ln(freq) - ln(minFreq)) / (ln(maxFreq) - ln(minFreq))
                val band = (logNorm * NUM_BANDS).toInt().coerceIn(0, NUM_BANDS - 1)
                rawMagnitudes[band] += mag
                bandCounts[band]++
            }
        }

        synchronized(this) {
            for (b in 0 until NUM_BANDS) {
                val count = max(1, bandCounts[b])
                // Normalize and apply frequency slope compensation (+3dB per octave for human hearing)
                val slopeGain = 1.0f + (b.toFloat() / NUM_BANDS) * 1.6f
                val rawVal = (rawMagnitudes[b] / count) / 90f * sensitivity * slopeGain
                val clamped = rawVal.coerceIn(0f, 1f)

                // Fast attack, smooth decay
                if (clamped > currentMagnitudes[b]) {
                    currentMagnitudes[b] = currentMagnitudes[b] + (clamped - currentMagnitudes[b]) * ATTACK_FACTOR
                } else {
                    currentMagnitudes[b] = currentMagnitudes[b] * DECAY_FACTOR
                }

                if (currentMagnitudes[b] > currentPeaks[b]) {
                    currentPeaks[b] = currentMagnitudes[b]
                }
            }
        }
    }

    /**
     * Processes live PCM waveform bytes into normalized [-1.0f, 1.0f] float points.
     */
    private fun processHardwareWaveform(wave: ByteArray) {
        if (wave.isEmpty()) return
        lastHardwareDataTime = SystemClock.uptimeMillis()

        var sumSq = 0f
        val step = max(1, wave.size / NUM_WAVEFORM_POINTS)

        synchronized(this) {
            for (i in 0 until NUM_WAVEFORM_POINTS) {
                val idx = (i * step).coerceIn(0, wave.size - 1)
                val normalized = ((wave[idx].toInt() and 0xFF) - 128) / 128f
                currentWaveform[i] = normalized
                sumSq += normalized * normalized
            }

            val rms = sqrt(sumSq / NUM_WAVEFORM_POINTS)
            currentBassLevel = (rms * 2.2f * sensitivity).coerceIn(0f, 1f)

            val now = SystemClock.uptimeMillis()
            if (currentBassLevel > 0.45f && (now - lastBeatTime) > 220L) {
                beatTriggered = true
                lastBeatTime = now
            }
        }
    }

    /**
     * Generates responsive musical frequency movements when hardware visualizer cannot
     * access microphone/stream (e.g., standard streaming emulator).
     */
    private fun simulateAudioSpectrum(time: Float) {
        val bassPulse = (sin(time * 3.5f) * 0.5f + 0.5f)
        val midPulse = (cos(time * 5.2f + 1.2f) * 0.5f + 0.5f)
        val highPulse = (sin(time * 7.8f + 2.5f) * 0.5f + 0.5f)

        synchronized(this) {
            for (i in 0 until NUM_BANDS) {
                val progress = i.toFloat() / NUM_BANDS
                val base = when {
                    progress < 0.25f -> bassPulse * (1f - progress * 1.5f)
                    progress < 0.65f -> midPulse * 0.75f + (sin(time * 4f + i) * 0.2f)
                    else -> highPulse * (0.85f - (progress - 0.65f)) + (cos(time * 6f + i) * 0.15f)
                }

                val variance = sin(time * 2.2f + i * 0.45f) * 0.15f
                val target = ((base + variance) * sensitivity).coerceIn(0.08f, 0.95f)

                if (target > currentMagnitudes[i]) {
                    currentMagnitudes[i] = currentMagnitudes[i] + (target - currentMagnitudes[i]) * 0.55f
                } else {
                    currentMagnitudes[i] = currentMagnitudes[i] * DECAY_FACTOR
                }

                if (currentMagnitudes[i] > currentPeaks[i]) {
                    currentPeaks[i] = currentMagnitudes[i]
                } else {
                    currentPeaks[i] = max(0f, currentPeaks[i] - PEAK_DECAY_RATE)
                }
            }

            // Synthesize waveform
            for (i in 0 until NUM_WAVEFORM_POINTS) {
                val phase = (i.toFloat() / NUM_WAVEFORM_POINTS) * 4f * Math.PI.toFloat()
                val wave = (sin(phase + time * 4.5f) * 0.5f + sin(phase * 2f + time * 3f) * 0.3f) * bassPulse
                currentWaveform[i] = wave.coerceIn(-1f, 1f)
            }

            currentBassLevel = (bassPulse * sensitivity).coerceIn(0f, 1f)
            if (bassPulse > 0.82f) {
                val now = SystemClock.uptimeMillis()
                if (now - lastBeatTime > 240L) {
                    beatTriggered = true
                    lastBeatTime = now
                }
            }
        }
    }

    private fun updatePeaksAndDecay(isReceiving: Boolean) {
        val decayRate = if (isReceiving) DECAY_FACTOR else 0.70f
        for (i in 0 until NUM_BANDS) {
            if (!isReceiving) {
                currentMagnitudes[i] *= decayRate
            }
            currentPeaks[i] = max(currentMagnitudes[i], currentPeaks[i] - PEAK_DECAY_RATE)
        }
        if (!isReceiving) {
            currentBassLevel *= 0.75f
            for (i in 0 until NUM_WAVEFORM_POINTS) {
                currentWaveform[i] *= 0.75f
            }
        }
    }

    @Synchronized
    fun releaseHardwareVisualizer() {
        try {
            visualizer?.enabled = false
            visualizer?.release()
        } catch (_: Exception) {
        } finally {
            visualizer = null
        }
    }

    fun release() {
        tickerJob?.cancel()
        releaseHardwareVisualizer()
    }
}
