package com.example;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.audiofx.BassBoost;
import android.media.audiofx.Equalizer;
import android.media.audiofx.Virtualizer;
import android.media.audiofx.Visualizer;
import android.os.Handler;
import android.os.Looper;
import java.util.Arrays;

/**
 * Manages the Android AudioEffect pipeline (10-Band Equalizer, BassBoost, Virtualizer)
 * and the Visualizer spectrum analysis engine for real-time frequency data.
 */
public class AudioEffectManager {

    public interface OnSpectrumDataListener {
        void onSpectrumData(float[] tenBandSpectrum, byte[] waveForm, float beatEnergy, float bassLevel);
    }

    public static final int NUM_EQ_BANDS = 10;
    public static final String[] BAND_LABELS = {
        "31 Hz", "62 Hz", "125 Hz", "250 Hz", "500 Hz",
        "1 kHz", "2 kHz", "4 kHz", "8 kHz", "16 kHz"
    };

    public static final int[] BAND_CENTER_FREQS_HZ = {
        31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000
    };

    // Range: -12.0 dB to +12.0 dB (-1200 mB to +1200 mB)
    public static final short MIN_DB_LEVEL = -12;
    public static final short MAX_DB_LEVEL = 12;

    public static final class Preset {
        public final String name;
        public final short[] bandLevelsDb; // 10 bands in dB (-12 to +12)

        public Preset(String name, int... levels) {
            this.name = name;
            this.bandLevelsDb = new short[NUM_EQ_BANDS];
            for (int i = 0; i < NUM_EQ_BANDS && i < levels.length; i++) {
                this.bandLevelsDb[i] = (short) levels[i];
            }
        }
    }

    public static final Preset[] PRESETS = {
        new Preset("Plat / Neutre", 0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
        new Preset("Bass Boost", 8, 7, 5, 2, 0, 0, 1, 2, 3, 3),
        new Preset("Rock", 5, 4, 2, -1, -2, 1, 3, 5, 6, 6),
        new Preset("Pop", -1, 1, 3, 4, 4, 3, 2, 1, 2, 2),
        new Preset("Électro / Dance", 7, 6, 3, 0, -1, 1, 3, 5, 6, 7),
        new Preset("Vocal Booster", -3, -2, 0, 2, 5, 6, 4, 2, 0, -1),
        new Preset("Jazz", 3, 3, 2, 1, 1, 2, 2, 3, 4, 4),
        new Preset("Classique", 4, 3, 2, 1, 0, 0, 2, 3, 4, 5),
        new Preset("Acoustique", 3, 3, 2, 1, 2, 2, 3, 4, 4, 3),
        new Preset("Metal / Hard Rock", 7, 6, 2, -1, -3, 0, 4, 6, 7, 7)
    };

    private static final String PREF_KEY_10BAND = "audio_10band_eq_levels";
    private static final String PREF_KEY_BASS = "audio_bass_boost_strength";
    private static final String PREF_KEY_VIRT = "audio_virtualizer_strength";

    private final Context context;
    private final SharedPreferences prefs;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private Equalizer hardwareEqualizer;
    private BassBoost hardwareBassBoost;
    private Virtualizer hardwareVirtualizer;
    private Visualizer visualizer;

    private int activeAudioSessionId = 0;
    private final short[] virtualBandLevelsDb = new short[NUM_EQ_BANDS];

    // Spectrum analysis pipeline state
    private final float[] tenBandMagnitudes = new float[NUM_EQ_BANDS];
    private final float[] smoothBandMagnitudes = new float[NUM_EQ_BANDS];
    private float beatEnergy = 0f;
    private float bassLevel = 0f;
    private float bassAvg = 0.05f;
    private float prevRms = 0f;
    private long lastBeatTime = 0L;
    private OnSpectrumDataListener spectrumListener;

    public AudioEffectManager(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = context.getSharedPreferences("AimpE3_AudioEffects", Context.MODE_PRIVATE);
        loadSavedLevels();
    }

    public void setOnSpectrumDataListener(OnSpectrumDataListener listener) {
        this.spectrumListener = listener;
    }

    public synchronized void attachToAudioSession(int sessionId) {
        if (sessionId <= 0) return;
        this.activeAudioSessionId = sessionId;

        releaseEffects();
        releaseVisualizer();

        // 1. Equalizer setup
        try {
            hardwareEqualizer = new Equalizer(1000, sessionId);
            hardwareEqualizer.setEnabled(true);
        } catch (Exception e) {
            hardwareEqualizer = null;
        }

        // 2. BassBoost setup
        try {
            hardwareBassBoost = new BassBoost(1000, sessionId);
            hardwareBassBoost.setEnabled(true);
            short savedBass = (short) prefs.getInt(PREF_KEY_BASS, 0);
            if (hardwareBassBoost.getStrengthSupported() && savedBass > 0) {
                hardwareBassBoost.setStrength(savedBass);
            }
        } catch (Exception e) {
            hardwareBassBoost = null;
        }

        // 3. Virtualizer setup
        try {
            hardwareVirtualizer = new Virtualizer(1000, sessionId);
            hardwareVirtualizer.setEnabled(true);
            short savedVirt = (short) prefs.getInt(PREF_KEY_VIRT, 0);
            if (hardwareVirtualizer.getStrengthSupported() && savedVirt > 0) {
                hardwareVirtualizer.setStrength(savedVirt);
            }
        } catch (Exception e) {
            hardwareVirtualizer = null;
        }

        // 4. Apply 10-band profile to the hardware equalizer
        applyVirtualBandsToHardware();

        // 5. Setup Visualizer for real-time spectrum analysis pipeline
        setupVisualizer(sessionId);
    }

    private void setupVisualizer(int sessionId) {
        try {
            visualizer = new Visualizer(sessionId);
            int[] range = Visualizer.getCaptureSizeRange();
            visualizer.setCaptureSize(range[1]); // Maximum resolution (usually 1024 points)

            visualizer.setDataCaptureListener(new Visualizer.OnDataCaptureListener() {
                @Override
                public void onWaveFormDataCapture(Visualizer v, byte[] waveform, int samplingRate) {
                    processWaveform(waveform);
                }

                @Override
                public void onFftDataCapture(Visualizer v, byte[] fft, int samplingRate) {
                    processFft(fft, samplingRate);
                }
            }, Visualizer.getMaxCaptureRate(), true, true);

            visualizer.setEnabled(true);
        } catch (Exception ignored) {
            visualizer = null;
        }
    }

    private void processWaveform(byte[] wave) {
        if (wave == null || wave.length < 32) return;
        float sumSq = 0f;
        int len = wave.length;
        for (int i = 0; i < len; i++) {
            float val = ((wave[i] & 0xFF) - 128f) / 128f;
            sumSq += val * val;
        }
        float rms = (float) Math.sqrt(sumSq / len);
        bassLevel = Math.min(1.0f, rms * 1.8f);

        long now = System.currentTimeMillis();
        if (rms > bassAvg * 1.35f && rms > prevRms && (now - lastBeatTime) > 130) {
            beatEnergy = Math.min(1.0f, 0.6f + (rms / Math.max(0.01f, bassAvg) - 1f));
            lastBeatTime = now;
        } else {
            beatEnergy *= 0.85f;
            if (beatEnergy < 0.01f) beatEnergy = 0f;
        }
        bassAvg += (rms - bassAvg) * 0.08f;
        prevRms = rms;

        notifySpectrumData(wave);
    }

    private void processFft(byte[] fft, int samplingRateMilliHz) {
        if (fft == null || fft.length < 16 || samplingRateMilliHz <= 0) return;

        int n = fft.length / 2;
        float binWidthHz = (samplingRateMilliHz / 2000f) / n;

        // Extract magnitudes across the 10 standard audio bands
        // 1. 20-45 Hz, 2. 45-90 Hz, 3. 90-180 Hz, 4. 180-355 Hz, 5. 355-710 Hz,
        // 6. 710-1400 Hz, 7. 1.4-2.8 kHz, 8. 2.8-5.6 kHz, 9. 5.6-11 kHz, 10. 11-20 kHz
        float[] bandEdges = {
            20f, 45f, 90f, 180f, 355f, 710f, 1400f, 2800f, 5600f, 11200f, 20000f
        };

        for (int band = 0; band < NUM_EQ_BANDS; band++) {
            float fMin = bandEdges[band];
            float fMax = bandEdges[band + 1];

            int kMin = Math.max(1, Math.round(fMin / binWidthHz));
            int kMax = Math.min(n - 1, Math.round(fMax / binWidthHz));

            float sum = 0f;
            int count = 0;
            for (int k = kMin; k <= kMax; k++) {
                float real = fft[2 * k];
                float imag = fft[2 * k + 1];
                float mag = (float) Math.hypot(real, imag);
                sum += mag;
                count++;
            }
            float avg = count > 0 ? (sum / count) : 0f;
            // High frequency equalization compensation
            float weight = 1.0f + (band / (float) NUM_EQ_BANDS) * 2.2f;
            float norm = Math.min(1.0f, (avg * weight) / 60f);

            // Smooth attack and decay for studio VU visualization
            if (norm > smoothBandMagnitudes[band]) {
                smoothBandMagnitudes[band] += (norm - smoothBandMagnitudes[band]) * 0.45f;
            } else {
                smoothBandMagnitudes[band] += (norm - smoothBandMagnitudes[band]) * 0.15f;
            }
            tenBandMagnitudes[band] = smoothBandMagnitudes[band];
        }

        notifySpectrumData(null);
    }

    private void notifySpectrumData(final byte[] waveform) {
        final OnSpectrumDataListener listener = this.spectrumListener;
        if (listener != null) {
            final float[] copy = Arrays.copyOf(tenBandMagnitudes, NUM_EQ_BANDS);
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    listener.onSpectrumData(copy, waveform, beatEnergy, bassLevel);
                }
            });
        }
    }

    public float[] getTenBandMagnitudes() {
        return Arrays.copyOf(tenBandMagnitudes, NUM_EQ_BANDS);
    }

    public float getBeatEnergy() {
        return beatEnergy;
    }

    public float getBassLevel() {
        return bassLevel;
    }

    // -------------------------------------------------------------
    // 10-Band Equalizer Methods
    // -------------------------------------------------------------

    public short getBandLevel(int bandIndex) {
        if (bandIndex >= 0 && bandIndex < NUM_EQ_BANDS) {
            return virtualBandLevelsDb[bandIndex];
        }
        return 0;
    }

    public void setBandLevel(int bandIndex, short levelDb) {
        if (bandIndex < 0 || bandIndex >= NUM_EQ_BANDS) return;
        virtualBandLevelsDb[bandIndex] = (short) Math.max(MIN_DB_LEVEL, Math.min(MAX_DB_LEVEL, (int) levelDb));
        applyVirtualBandsToHardware();
        saveLevels();
    }

    public void applyPreset(Preset preset) {
        if (preset == null) return;
        for (int i = 0; i < NUM_EQ_BANDS; i++) {
            virtualBandLevelsDb[i] = preset.bandLevelsDb[i];
        }
        applyVirtualBandsToHardware();
        saveLevels();
    }

    public void resetBands() {
        Arrays.fill(virtualBandLevelsDb, (short) 0);
        applyVirtualBandsToHardware();
        saveLevels();
    }

    /**
     * Maps the 10 standard audio frequency bands to the underlying hardware Equalizer.
     * Uses nearest-band Gaussian weighted interpolation if the hardware equalizer has fewer bands.
     */
    private void applyVirtualBandsToHardware() {
        if (hardwareEqualizer == null) return;
        try {
            short numHwBands = hardwareEqualizer.getNumberOfBands();
            if (numHwBands <= 0) return;

            short[] range = hardwareEqualizer.getBandLevelRange(); // usually [-1500, 1500] mB
            short minMb = range[0];
            short maxMb = range[1];

            for (short hwBand = 0; hwBand < numHwBands; hwBand++) {
                int hwCenterFreqHz = hardwareEqualizer.getCenterFreq(hwBand) / 1000;

                // Find weighted average of the 10 virtual bands around this center frequency
                float totalWeight = 0f;
                float weightedDbSum = 0f;

                for (int vBand = 0; vBand < NUM_EQ_BANDS; vBand++) {
                    int vCenterFreqHz = BAND_CENTER_FREQS_HZ[vBand];
                    // Logarithmic frequency distance
                    double logDist = Math.abs(Math.log10((double) hwCenterFreqHz / vCenterFreqHz));
                    // Gaussian bell curve kernel
                    float weight = (float) Math.exp(-(logDist * logDist) / 0.18);
                    weightedDbSum += virtualBandLevelsDb[vBand] * weight;
                    totalWeight += weight;
                }

                float targetDb = totalWeight > 0 ? (weightedDbSum / totalWeight) : 0f;
                short targetMb = (short) Math.round(targetDb * 100f);
                targetMb = (short) Math.max(minMb, Math.min(maxMb, (int) targetMb));

                hardwareEqualizer.setBandLevel(hwBand, targetMb);
            }
        } catch (Exception ignored) {}
    }

    public void setBassBoostStrength(short strength) {
        if (hardwareBassBoost != null && hardwareBassBoost.getStrengthSupported()) {
            try {
                hardwareBassBoost.setStrength(strength);
                prefs.edit().putInt(PREF_KEY_BASS, strength).apply();
            } catch (Exception ignored) {}
        }
    }

    public short getBassBoostStrength() {
        if (hardwareBassBoost != null && hardwareBassBoost.getStrengthSupported()) {
            try {
                return hardwareBassBoost.getRoundedStrength();
            } catch (Exception ignored) {}
        }
        return (short) prefs.getInt(PREF_KEY_BASS, 0);
    }

    public void setVirtualizerStrength(short strength) {
        if (hardwareVirtualizer != null && hardwareVirtualizer.getStrengthSupported()) {
            try {
                hardwareVirtualizer.setStrength(strength);
                prefs.edit().putInt(PREF_KEY_VIRT, strength).apply();
            } catch (Exception ignored) {}
        }
    }

    public short getVirtualizerStrength() {
        if (hardwareVirtualizer != null && hardwareVirtualizer.getStrengthSupported()) {
            try {
                return hardwareVirtualizer.getRoundedStrength();
            } catch (Exception ignored) {}
        }
        return (short) prefs.getInt(PREF_KEY_VIRT, 0);
    }

    private void saveLevels() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < NUM_EQ_BANDS; i++) {
            if (i > 0) sb.append(',');
            sb.append(virtualBandLevelsDb[i]);
        }
        prefs.edit().putString(PREF_KEY_10BAND, sb.toString()).apply();
    }

    private void loadSavedLevels() {
        String saved = prefs.getString(PREF_KEY_10BAND, null);
        if (saved != null) {
            String[] parts = saved.split(",");
            for (int i = 0; i < NUM_EQ_BANDS && i < parts.length; i++) {
                try {
                    virtualBandLevelsDb[i] = Short.parseShort(parts[i].trim());
                } catch (Exception ignored) {}
            }
        }
    }

    public synchronized void releaseEffects() {
        if (hardwareEqualizer != null) {
            try { hardwareEqualizer.release(); } catch (Exception ignored) {}
            hardwareEqualizer = null;
        }
        if (hardwareBassBoost != null) {
            try { hardwareBassBoost.release(); } catch (Exception ignored) {}
            hardwareBassBoost = null;
        }
        if (hardwareVirtualizer != null) {
            try { hardwareVirtualizer.release(); } catch (Exception ignored) {}
            hardwareVirtualizer = null;
        }
    }

    public synchronized void releaseVisualizer() {
        if (visualizer != null) {
            try {
                visualizer.setEnabled(false);
                visualizer.release();
            } catch (Exception ignored) {}
            visualizer = null;
        }
    }

    public synchronized void releaseAll() {
        releaseEffects();
        releaseVisualizer();
    }
}
