package com.example;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.media.audiofx.Visualizer;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;
import java.util.Random;

public class SpectrumView extends View {

    private static final int NUM_BANDS = 7;
    private static final int NUM_POINTS = 35;

    private static final float SHADER_SHIFT_SPEED = 0.03f;
    private static final long MAX_FRAME_DELTA_MS = 64L;

    private static final float MIN_FREQ = 35f;
    private static final float MAX_FREQ = 12000f;

    private static final float DEFAULT_CUTOFF_HZ = 120f;
    private static final float BEAT_SENSITIVITY = 1.30f;
    private static final float BEAT_MIN_RMS = 0.015f;
    private static final long BEAT_MIN_INTERVAL_MS = 120L;

    private final Paint ribbonPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Path[] bandPaths = new Path[NUM_BANDS];
    private final Matrix shaderMatrix = new Matrix();
    private final Random random = new Random();

    private final float[] bandSpeeds = new float[NUM_BANDS];
    private final float[] bandOffsets = new float[NUM_BANDS];
    private final float[] bandFrequencies = new float[NUM_BANDS];
    private final float[] lastX = new float[NUM_BANDS];
    private final float[] lastY = new float[NUM_BANDS];

    private Visualizer visualizer;

    private final Object lock = new Object();
    private final float[] targetValues = new float[NUM_POINTS];
    private final float[] cachedValues = new float[NUM_POINTS];
    private final float[] targetBassWave = new float[NUM_POINTS];
    private final float[] cachedBassWave = new float[NUM_POINTS];
    private volatile float bassLevel = 0f;
    private volatile float pendingBeat = 0f;
    private volatile long lastDataTime = 0L;

    private final Biquad lp1 = new Biquad();
    private final Biquad lp2 = new Biquad();
    private volatile float cutoffHz = DEFAULT_CUTOFF_HZ;
    private float filterCutoffApplied = -1f;
    private float filterFsApplied = -1f;
    private float[] filtered;

    private float[] mags;
    private float agcPeak = 60f;
    private float bassPeak = 0.1f;
    private float bassAvg = 0f;
    private float prevBass = 0f;
    private long lastBeatTime = 0L;

    private float beatEnv = 0f;
    private float bassSmooth = 0f;

    private LinearGradient rgbShader;
    private float animationPhase = 0f;
    private float shaderTranslate = 0f;
    private long lastFrameTime = 0L;
    private boolean attached = false;
    private boolean animating = true;

    private static final class Biquad {
        float b0, b1, b2, a1, a2, z1, z2;

        void lowPass(float fc, float fs, float q) {
            float w0 = (float) (2.0 * Math.PI * fc / fs);
            float cos = (float) Math.cos(w0);
            float alpha = (float) Math.sin(w0) / (2f * q);
            float a0 = 1f + alpha;
            b0 = ((1f - cos) / 2f) / a0;
            b1 = (1f - cos) / a0;
            b2 = b0;
            a1 = (-2f * cos) / a0;
            a2 = (1f - alpha) / a0;
        }

        float process(float x) {
            float y = b0 * x + z1;
            z1 = b1 * x - a1 * y + z2;
            z2 = b2 * x - a2 * y;
            return y;
        }

        void prime(float x) {
            z1 = z2 = 0f;
            for (int i = 0; i < 600; i++) process(x);
        }
    }

    public SpectrumView(Context context) {
        super(context);
        init();
    }

    public SpectrumView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public SpectrumView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        ribbonPaint.setStyle(Paint.Style.STROKE);
        ribbonPaint.setStrokeWidth(2f);
        ribbonPaint.setStrokeCap(Paint.Cap.ROUND);
        ribbonPaint.setStrokeJoin(Paint.Join.ROUND);

        glowPaint.setStyle(Paint.Style.STROKE);
        glowPaint.setStrokeWidth(4f);
        glowPaint.setStrokeCap(Paint.Cap.ROUND);
        glowPaint.setStrokeJoin(Paint.Join.ROUND);
        glowPaint.setAlpha(50);

        for (int i = 0; i < NUM_BANDS; i++) {
            bandPaths[i] = new Path();
            bandSpeeds[i] = 0.0005f + random.nextFloat() * 0.001f;
            bandOffsets[i] = random.nextFloat() * 10f;
            bandFrequencies[i] = 0.15f + random.nextFloat() * 0.25f;
        }
    }

    public void setBassCutoffHz(float hz) {
        cutoffHz = Math.max(30f, Math.min(400f, hz));
    }

    public void setAnimating(boolean animating) {
        if (this.animating == animating) return;
        this.animating = animating;
        lastFrameTime = 0L;
        if (animating && attached) postInvalidateOnAnimation();
    }

    public void linkMediaPlayer(int audioSessionId) {
        releaseVisualizer();

        Visualizer candidate = null;
        try {
            candidate = new Visualizer(audioSessionId);
            candidate.setCaptureSize(Visualizer.getCaptureSizeRange()[1]);

            candidate.setDataCaptureListener(new Visualizer.OnDataCaptureListener() {
                @Override
                public void onWaveFormDataCapture(Visualizer v, byte[] waveform, int samplingRate) {
                    processWaveform(waveform, samplingRate);
                }

                @Override
                public void onFftDataCapture(Visualizer v, byte[] fft, int samplingRate) {
                    processFft(fft, samplingRate);
                }
            }, Visualizer.getMaxCaptureRate(), true, true);

            candidate.setEnabled(true);
            visualizer = candidate;
        } catch (Exception ignored) {
            if (candidate != null) {
                try { candidate.release(); } catch (Exception ignoredRelease) {}
            }
            visualizer = null;
        }
    }

    private void processWaveform(byte[] wave, int samplingRateMilliHz) {
        if (wave == null || wave.length < 64 || samplingRateMilliHz <= 0) return;

        final float fs = samplingRateMilliHz / 1000f;
        final float fc = cutoffHz;

        if (fc != filterCutoffApplied || fs != filterFsApplied) {
            lp1.lowPass(fc, fs, 0.7071f);
            lp2.lowPass(fc, fs, 0.7071f);
            filterCutoffApplied = fc;
            filterFsApplied = fs;
        }

        final int len = wave.length;
        if (filtered == null || filtered.length != len) filtered = new float[len];

        float first = (wave[0] & 0xFF) - 128f;
        lp1.prime(first);
        lp2.prime(first);

        float sumSq = 0f;
        float peak = 0f;
        for (int i = 0; i < len; i++) {
            float x = (wave[i] & 0xFF) - 128f;
            float y = lp2.process(lp1.process(x));
            filtered[i] = y;
            sumSq += y * y;
            float ay = Math.abs(y);
            if (ay > peak) peak = ay;
        }

        float rms = (float) Math.sqrt(sumSq / len) / 128f;
        peak /= 128f;

        bassPeak = Math.max(Math.max(peak, 0.06f), bassPeak * 0.992f);
        bassLevel = Math.min(1f, rms / (bassPeak * 0.707f));

        synchronized (lock) {
            for (int i = 0; i < NUM_POINTS; i++) {
                int idx = Math.round(i * (len - 1) / (float) (NUM_POINTS - 1));
                float v = filtered[idx] / 128f / bassPeak;
                targetBassWave[i] = Math.max(-1f, Math.min(1f, v));
            }
        }

        long now = SystemClock.uptimeMillis();
        if (bassAvg == 0f) bassAvg = rms;
        float threshold = Math.max(bassAvg * BEAT_SENSITIVITY, BEAT_MIN_RMS);

        if (rms > threshold && rms > prevBass && (now - lastBeatTime) > BEAT_MIN_INTERVAL_MS) {
            float strength = 0.6f + (rms / threshold - 1f) * 1.2f;
            pendingBeat = Math.max(0.6f, Math.min(1f, strength));
            lastBeatTime = now;
        }

        bassAvg += (rms - bassAvg) * 0.10f;
        prevBass = rms;
        lastDataTime = now;
    }

    private void processFft(byte[] fft, int samplingRateMilliHz) {
        if (fft == null || fft.length < 8 || samplingRateMilliHz <= 0) return;

        final int n = fft.length / 2;
        final float binHz = (samplingRateMilliHz / 2000f) / n;

        if (mags == null || mags.length != n) mags = new float[n];
        for (int k = 1; k < n; k++) {
            mags[k] = (float) Math.hypot(fft[2 * k], fft[2 * k + 1]);
        }

        final float ratio = MAX_FREQ / MIN_FREQ;
        final float[] raw = new float[NUM_POINTS];
        float frameMax = 0f;

        for (int i = 0; i < NUM_POINTS; i++) {
            float fLo = MIN_FREQ * (float) Math.pow(ratio, i / (float) NUM_POINTS);
            float fHi = MIN_FREQ * (float) Math.pow(ratio, (i + 1) / (float) NUM_POINTS);
            int kLo = Math.max(1, Math.round(fLo / binHz));
            int kHi = Math.max(kLo, Math.min(n - 1, Math.round(fHi / binHz)));

            float sum = 0f;
            for (int k = kLo; k <= kHi; k++) sum += mags[k];
            float v = sum / (kHi - kLo + 1);
            v *= 1f + (i / (float) NUM_POINTS) * 1.5f;

            raw[i] = v;
            if (v > frameMax) frameMax = v;
        }

        agcPeak = Math.max(Math.max(frameMax, 60f), agcPeak * 0.995f);

        synchronized (lock) {
            for (int i = 0; i < NUM_POINTS; i++) {
                targetValues[i] = Math.min(1f, raw[i] / agcPeak);
            }
        }
        lastDataTime = SystemClock.uptimeMillis();
    }

    public float getBeatEnergy() {
        return beatEnv;
    }

    public float getBassLevel() {
        return bassSmooth;
    }

    public void releaseVisualizer() {
        try {
            if (visualizer != null) {
                visualizer.setEnabled(false);
                visualizer.release();
                visualizer = null;
            }
        } catch (Exception e) {
            visualizer = null;
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        attached = true;
        lastFrameTime = 0L;
        postInvalidateOnAnimation();
    }

    @Override
    protected void onDetachedFromWindow() {
        attached = false;
        super.onDetachedFromWindow();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w > 0 && h > 0) {
            int[] colors = new int[]{
                0xFF00FFFF, 0xFF0055FF, 0xFFFF00FF, 0xFFFF2244,
                0xFFFFAA00, 0xFF00FF55, 0xFF00FFFF
            };
            rgbShader = new LinearGradient(0, 0, w * 2, 0, colors, null, Shader.TileMode.REPEAT);
            rgbShader.setLocalMatrix(shaderMatrix);
            ribbonPaint.setShader(rgbShader);
            glowPaint.setShader(rgbShader);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        final int width = getWidth();
        final int height = getHeight();
        if (width <= 0 || height <= 0) return;

        long now = SystemClock.uptimeMillis();
        if (lastFrameTime == 0L) lastFrameTime = now;
        long delta = Math.min(now - lastFrameTime, MAX_FRAME_DELTA_MS);
        lastFrameTime = now;

        float pb = pendingBeat;
        if (pb > 0f) {
            beatEnv = Math.max(beatEnv, pb);
            pendingBeat = 0f;
        }
        beatEnv *= (float) Math.exp(-delta / 110f);
        if (beatEnv < 0.01f) beatEnv = 0f;

        boolean stale = (now - lastDataTime) > 300L;

        float bl = stale ? 0f : bassLevel;
        bassSmooth += (bl - bassSmooth) * (1f - (float) Math.exp(-delta / 80f));

        if (animating) {
            float boost = 1f + beatEnv * 3f;
            animationPhase += delta * 0.0008f * boost;
            shaderTranslate = (shaderTranslate + delta * SHADER_SHIFT_SPEED * boost) % (width * 2f);
        }

        if (rgbShader != null) {
            shaderMatrix.setTranslate(shaderTranslate, 0);
            rgbShader.setLocalMatrix(shaderMatrix);
        }

        glowPaint.setStrokeWidth(4f + beatEnv * 8f);
        glowPaint.setAlpha((int) (50 + beatEnv * 110));
        ribbonPaint.setStrokeWidth(2f + beatEnv * 1.5f);

        for (int i = 0; i < NUM_BANDS; i++) bandPaths[i].reset();

        final float centerY = height / 2f;
        final float segmentWidth = (float) width / (NUM_POINTS - 1);

        final float attack = 1f - (float) Math.exp(-delta / 35f);
        final float release = 1f - (float) Math.exp(-delta / 150f);
        final float waveK = 1f - (float) Math.exp(-delta / 22f);

        final float punch = 1f + beatEnv * 0.45f + bassSmooth * 0.2f;

        final float gate = Math.min(1f, bassSmooth * 1.5f);
        final float vibAmp = height * 0.10f * Math.min(1f, 0.45f * gate + 0.75f * beatEnv);

        synchronized (lock) {
            for (int i = 0; i < NUM_POINTS; i++) {
                float target = stale ? 0f : targetValues[i];
                float k = target > cachedValues[i] ? attack : release;
                cachedValues[i] += (target - cachedValues[i]) * k;

                float tw = stale ? 0f : targetBassWave[i];
                cachedBassWave[i] += (tw - cachedBassWave[i]) * waveK;
            }
        }

        for (int i = 0; i < NUM_POINTS; i++) {
            float x = i * segmentWidth;
            float edgeFactor = 0.85f + 0.15f * (float) Math.sin((i / (float) (NUM_POINTS - 1)) * Math.PI);

            for (int b = 0; b < NUM_BANDS; b++) {
                float bandPhase = animationPhase * (1f + bandSpeeds[b] * 300f)
                    + bandOffsets[b] + (i * bandFrequencies[b]);

                float waveOffset = (float) Math.sin(bandPhase) * (6f + b * 1.2f);
                float amplitude = cachedValues[i] * (height * (0.18f + b * 0.025f)) * punch;
                float verticalShift = (b - (NUM_BANDS / 2f)) * 6f;

                float vibration = cachedBassWave[i] * vibAmp * (0.7f + b * 0.1f);

                float y = centerY + verticalShift - ((amplitude + waveOffset) * edgeFactor) + vibration;

                if (i == 0) {
                    bandPaths[b].moveTo(x, y);
                } else {
                    float mx = (lastX[b] + x) / 2f;
                    float my = (lastY[b] + y) / 2f;
                    bandPaths[b].quadTo(lastX[b], lastY[b], mx, my);
                }
                lastX[b] = x;
                lastY[b] = y;
            }
        }
        for (int b = 0; b < NUM_BANDS; b++) {
            bandPaths[b].lineTo(lastX[b], lastY[b]);
        }

        for (int b = 0; b < NUM_BANDS; b++) canvas.drawPath(bandPaths[b], glowPaint);
        for (int b = 0; b < NUM_BANDS; b++) canvas.drawPath(bandPaths[b], ribbonPaint);

        if (attached && animating) postInvalidateOnAnimation();
    }
}
