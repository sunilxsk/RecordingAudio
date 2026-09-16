package com.leoskyzwengmail.Internalrecordingaudio.floating;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

/**
 * 悬浮窗里的实时波形条（纯 Canvas 自绘，不依赖任何图标库）。
 *
 * 中间对称柱状：柱高 = RMS 归一化幅度，顶部细线 = 峰值包络，
 * 上下虚线 = 静音阈值位置（低于它即判定静音）。
 */
public class WaveformBarView extends View {

    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint peakPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thresholdPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint axisPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private volatile float[] bars = new float[64];
    private volatile float[] peaks = new float[64];
    private volatile float threshold = 0f;   // 归一化幅度 0~1，<=0 表示不画
    private volatile boolean active = false;
    private volatile boolean silent = false;

    private int barColor = Color.WHITE;
    private int silentColor = Color.parseColor("#FFD54F");

    public WaveformBarView(Context context) {
        super(context);
        init();
    }

    public WaveformBarView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        barPaint.setStyle(Paint.Style.FILL);
        peakPaint.setStyle(Paint.Style.STROKE);
        peakPaint.setStrokeWidth(dp(1));
        thresholdPaint.setStyle(Paint.Style.STROKE);
        thresholdPaint.setStrokeWidth(dp(1));
        axisPaint.setStyle(Paint.Style.STROKE);
        axisPaint.setStrokeWidth(dp(1));
    }

    public void setData(float[] bars, float[] peaks, float thresholdDb, boolean active,
                        boolean silent) {
        this.bars = bars != null ? bars : new float[64];
        this.peaks = peaks != null ? peaks : new float[64];
        this.threshold = thresholdDb <= -120f ? 0f
                : (float) Math.pow(10.0, thresholdDb / 20.0);
        this.active = active;
        this.silent = silent;
        postInvalidateOnAnimationCompat();
    }

    public void setBarColor(int color) {
        barColor = color;
    }

    public void setSilentColor(int color) {
        silentColor = color;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        int count = bars.length;
        if (count == 0) return;

        float slot = (float) w / count;
        float bw = Math.max(1f, Math.min(dp(3), slot - dp(0.5f)));
        float centerY = h / 2f;
        float maxHalf = h / 2f - dp(1);

        // 阈值线
        if (threshold > 0f) {
            float off = threshold * maxHalf;
            thresholdPaint.setColor(silent ? silentColor : Color.WHITE);
            thresholdPaint.setAlpha(90);
            canvas.drawLine(0, centerY - off, w, centerY - off, thresholdPaint);
            canvas.drawLine(0, centerY + off, w, centerY + off, thresholdPaint);
        }

        // 中轴
        axisPaint.setColor(Color.WHITE);
        axisPaint.setAlpha(40);
        canvas.drawLine(0, centerY, w, centerY, axisPaint);

        int color = silent ? silentColor : barColor;
        float alpha = active ? 1f : 0.4f;
        barPaint.setColor(color);
        barPaint.setAlpha((int) (235 * alpha));
        peakPaint.setColor(Color.WHITE);
        peakPaint.setAlpha((int) (150 * alpha));

        for (int i = 0; i < count; i++) {
            float v = clamp(bars[i]);
            if (v <= 0.0005f) continue;
            float half = Math.max(dp(1), v * maxHalf);
            float x = i * slot + (slot - bw) / 2f;
            canvas.drawRoundRect(x, centerY - half, x + bw, centerY + half,
                    bw / 2f, bw / 2f, barPaint);

            if (peaks.length == count) {
                float p = Math.max(half, clamp(peaks[i]) * maxHalf);
                if (p > half + 0.5f) {
                    canvas.drawLine(x, centerY - p, x + bw, centerY - p, peakPaint);
                }
            }
        }
    }

    private static float clamp(float v) {
        if (v < 0f) return 0f;
        if (v > 1f) return 1f;
        return v;
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    private void postInvalidateOnAnimationCompat() {
        postInvalidate();
    }
}
