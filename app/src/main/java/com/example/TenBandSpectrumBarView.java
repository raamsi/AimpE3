package com.example;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

/**
 * 10-Band Live Studio Spectrum Analyzer Bar View.
 * Displays real-time frequency analysis data across the 10 ISO audio bands.
 */
public class TenBandSpectrumBarView extends View {

    private static final int BANDS = AudioEffectManager.NUM_EQ_BANDS;
    private final float[] bandLevels = new float[BANDS];
    private final float[] peakLevels = new float[BANDS];
    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint peakPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bgBarPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private LinearGradient gradient;
    private int lastW = 0, lastH = 0;

    public TenBandSpectrumBarView(Context context) {
        super(context);
        init();
    }

    public TenBandSpectrumBarView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public TenBandSpectrumBarView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        barPaint.setStyle(Paint.Style.FILL);
        peakPaint.setStyle(Paint.Style.FILL);
        peakPaint.setColor(0xFFFF5555);

        bgBarPaint.setStyle(Paint.Style.FILL);
        bgBarPaint.setColor(0x22FFFFFF);
    }

    public void updateBands(float[] magnitudes) {
        if (magnitudes == null) return;
        for (int i = 0; i < BANDS && i < magnitudes.length; i++) {
            float val = Math.max(0f, Math.min(1.0f, magnitudes[i]));
            bandLevels[i] = val;
            if (val >= peakLevels[i]) {
                peakLevels[i] = val;
            } else {
                peakLevels[i] = Math.max(0f, peakLevels[i] - 0.035f);
            }
        }
        postInvalidateOnAnimation();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w > 0 && h > 0) {
            lastW = w;
            lastH = h;
            int[] colors = new int[]{0xFFFF2255, 0xFFFFCC00, 0xFF1DB954, 0xFF00E5FF};
            float[] positions = new float[]{0.0f, 0.35f, 0.70f, 1.0f};
            gradient = new LinearGradient(0, 0, 0, h, colors, positions, Shader.TileMode.CLAMP);
            barPaint.setShader(gradient);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        float barWidth = (w / (float) BANDS) * 0.72f;
        float gap = (w - (barWidth * BANDS)) / (float) (BANDS + 1);
        float radius = barWidth * 0.25f;

        for (int i = 0; i < BANDS; i++) {
            float left = gap + i * (barWidth + gap);
            float right = left + barWidth;

            // Background slot
            rect.set(left, 0, right, h);
            canvas.drawRoundRect(rect, radius, radius, bgBarPaint);

            // Active level bar
            float level = bandLevels[i];
            float top = h * (1.0f - level);
            if (top < h) {
                rect.set(left, top, right, h);
                canvas.drawRoundRect(rect, radius, radius, barPaint);
            }

            // Peak indicator line
            float peakTop = h * (1.0f - peakLevels[i]);
            if (peakTop < h - 4) {
                rect.set(left, peakTop, right, peakTop + 3f);
                canvas.drawRect(rect, peakPaint);
            }
        }
    }
}
