package com.example;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.SweepGradient;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.widget.ImageView;

public class RgbBorderImageView extends ImageView {

    private static final float ROTATION_PERIOD_MS = 4000f; // Un tour complet en 4 s
    private static final float PULSE_PERIOD_MS = 2400f;    // Cycle de respiration du halo
    private static final int GLOW_LAYERS = 6;              // Qualité du halo
    private static final long MAX_FRAME_DELTA_MS = 64L;    // Évite les sauts après une pause

    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint corePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Matrix matrix = new Matrix();
    private final RectF borderRect = new RectF();
    private final RectF imageBounds = new RectF();
    private final Path imageClipPath = new Path();

    private SweepGradient sweepShader;

    private float borderWidth;
    private float cornerRadius;
    private float innerPadding;
    private float glowSize;

    private boolean attached = false;
    private boolean animating = true;

    private float angle = 0f;
    private float pulseTime = 0f;
    private long lastFrameTime = 0L;

    public RgbBorderImageView(Context context) {
        super(context);
        init();
    }

    public RgbBorderImageView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public RgbBorderImageView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    private void init() {
        borderWidth = dp(3f);
        cornerRadius = dp(16f);
        innerPadding = dp(6f);
        glowSize = dp(10f);

        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(borderWidth);
        borderPaint.setStrokeCap(Paint.Cap.ROUND);

        glowPaint.setStyle(Paint.Style.STROKE);

        corePaint.setStyle(Paint.Style.STROKE);
        corePaint.setStrokeWidth(borderWidth * 0.35f);
        corePaint.setColor(Color.WHITE);
        corePaint.setAlpha(150);
    }

    public void setAnimating(boolean animating) {
        if (this.animating == animating) return;
        this.animating = animating;
        lastFrameTime = 0L;
        if (animating && attached) postInvalidateOnAnimation();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        int size = Math.min(getMeasuredWidth(), getMeasuredHeight());
        size = Math.min(size, (int) dp(420f));
        setMeasuredDimension(size, size);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w <= 0 || h <= 0) return;

        float margin = glowSize * 0.6f;
        float half = borderWidth / 2f;

        borderRect.set(margin + half, margin + half, w - margin - half, h - margin - half);

        float inset = borderWidth + innerPadding;
        imageBounds.set(borderRect.left + inset - half, borderRect.top + inset - half,
                        borderRect.right - inset + half, borderRect.bottom - inset + half);

        float imgRadius = Math.max(0f, cornerRadius - inset + half);
        imageClipPath.reset();
        imageClipPath.addRoundRect(imageBounds, imgRadius, imgRadius, Path.Direction.CW);

        int[] colors = new int[]{
            0xFF00FFFF, // Cyan
            0xFF0055FF, // Bleu électrique
            0xFFAA00FF, // Violet
            0xFFFF00FF, // Magenta
            0xFFFF2244, // Rouge vif
            0xFFFFAA00, // Orange
            0xFFFFFF00, // Jaune
            0xFF00FF55, // Vert néon
            0xFF00FFFF  // Cyan (boucle)
        };

        sweepShader = new SweepGradient(w / 2f, h / 2f, colors, null);
        borderPaint.setShader(sweepShader);
        glowPaint.setShader(sweepShader);
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
    protected void onDraw(Canvas canvas) {
        canvas.save();
        canvas.clipPath(imageClipPath);
        super.onDraw(canvas);
        canvas.restore();

        if (sweepShader == null) return;

        long now = SystemClock.uptimeMillis();
        if (lastFrameTime == 0L) lastFrameTime = now;
        long delta = Math.min(now - lastFrameTime, MAX_FRAME_DELTA_MS);
        lastFrameTime = now;

        if (animating) {
            angle = (angle + delta / ROTATION_PERIOD_MS * 360f) % 360f;
            pulseTime = (pulseTime + delta) % PULSE_PERIOD_MS;
        }

        matrix.setRotate(angle, getWidth() / 2f, getHeight() / 2f);
        sweepShader.setLocalMatrix(matrix);

        float pulse = (float) (0.8 + 0.2 * Math.sin(2 * Math.PI * pulseTime / PULSE_PERIOD_MS));

        for (int i = GLOW_LAYERS; i >= 1; i--) {
            float t = i / (float) GLOW_LAYERS;
            glowPaint.setStrokeWidth(borderWidth + glowSize * t * pulse);
            glowPaint.setAlpha((int) (255 * 0.10f * (1.2f - t) * pulse));
            canvas.drawRoundRect(borderRect, cornerRadius, cornerRadius, glowPaint);
        }

        canvas.drawRoundRect(borderRect, cornerRadius, cornerRadius, borderPaint);
        canvas.drawRoundRect(borderRect, cornerRadius, cornerRadius, corePaint);

        if (attached && animating) postInvalidateOnAnimation();
    }
}
