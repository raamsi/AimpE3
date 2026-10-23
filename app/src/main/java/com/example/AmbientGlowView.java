package com.example;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

public class AmbientGlowView extends View {

    private static final int PALETTE_SIZE = 72;
    private static final float UNIT_RADIUS = 256f;
    private static final float MAX_ALPHA = 0.50f;

    private static final float[][] BLOBS = {
        {0f,   0.22f, 0.15f, 1.0f,   0f, 0.62f},   // bord gauche haut
        {1f,   0.78f, 0.15f, 0.8f, 120f, 0.62f},   // bord droit bas
        {0f,   0.85f, 0.10f, 1.3f, 240f, 0.50f},   // bord gauche bas
        {1f,   0.18f, 0.10f, 1.1f,  60f, 0.50f},   // bord droit haut
        {0.5f, 0.00f, 0.00f, 0.0f, 180f, 0.55f}    // derrière le spectre
    };

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final PorterDuffColorFilter[] palette = new PorterDuffColorFilter[PALETTE_SIZE];

    private SpectrumView source;
    private float hue = 0f;
    private float t = 0f;
    private float beat = 0f;
    private float bass = 0f;
    private float prevSrcBeat = 0f;
    private long lastFrame = 0L;
    private boolean attached = false;
    private boolean animating = true;

    public AmbientGlowView(Context context) {
        super(context);
        init();
    }

    public AmbientGlowView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public AmbientGlowView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        paint.setShader(new RadialGradient(0f, 0f, UNIT_RADIUS,
                                           new int[]{0xFFFFFFFF, 0x80FFFFFF, 0x00FFFFFF},
                                           new float[]{0f, 0.45f, 1f},
                                           Shader.TileMode.CLAMP));

        float[] hsv = new float[]{0f, 0.85f, 1f};
        for (int i = 0; i < PALETTE_SIZE; i++) {
            hsv[0] = i * 360f / PALETTE_SIZE;
            palette[i] = new PorterDuffColorFilter(Color.HSVToColor(hsv), PorterDuff.Mode.SRC_IN);
        }
    }

    public void setSource(SpectrumView source) {
        this.source = source;
    }

    public void setAnimating(boolean animating) {
        if (this.animating == animating) return;
        this.animating = animating;
        lastFrame = 0L;
        if (animating && attached) postInvalidateOnAnimation();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        attached = true;
        lastFrame = 0L;
        postInvalidateOnAnimation();
    }

    @Override
    protected void onDetachedFromWindow() {
        attached = false;
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        final int w = getWidth();
        final int h = getHeight();
        if (w <= 0 || h <= 0) return;

        long now = SystemClock.uptimeMillis();
        if (lastFrame == 0L) lastFrame = now;
        long delta = Math.min(now - lastFrame, 64L);
        lastFrame = now;

        float srcBeat = 0f;
        float srcBass = 0f;
        if (source != null) {
            srcBeat = source.getBeatEnergy();
            srcBass = source.getBassLevel();
        }

        if (srcBeat > 0.85f && prevSrcBeat <= 0.85f) hue = (hue + 35f) % 360f;
        prevSrcBeat = srcBeat;

        if (srcBeat > beat) {
            beat = srcBeat;
        } else {
            beat += (srcBeat - beat) * (1f - (float) Math.exp(-delta / 140f));
        }
        bass += (srcBass - bass) * (1f - (float) Math.exp(-delta / 100f));

        if (animating) {
            t += delta * 0.0004f * (1f + beat * 2f);
            hue = (hue + delta * 0.006f * (1f + beat * 3f)) % 360f;
        }

        final float alphaBase = Math.min(MAX_ALPHA, 0.10f + 0.30f * beat + 0.14f * bass);
        final float scale = 1f + 0.28f * beat + 0.12f * bass;

        for (int i = 0; i < BLOBS.length; i++) {
            float[] d = BLOBS[i];
            float cx = w * d[0];
            float cy = h * (d[1] + d[2] * (float) Math.sin(t * d[3] + i * 1.7f));
            float radius = w * d[5] * scale;

            float alpha = alphaBase * (i == 4 ? 0.6f : 1f);
            int idx = (int) (((hue + d[4]) % 360f) / 360f * PALETTE_SIZE) % PALETTE_SIZE;

            paint.setColorFilter(palette[idx]);
            paint.setAlpha((int) (alpha * 255f));

            canvas.save();
            canvas.translate(cx, cy);
            float s = radius / UNIT_RADIUS;
            canvas.scale(s, s);
            canvas.drawCircle(0f, 0f, UNIT_RADIUS, paint);
            canvas.restore();
        }

        if (attached && animating) postInvalidateOnAnimation();
    }
}
