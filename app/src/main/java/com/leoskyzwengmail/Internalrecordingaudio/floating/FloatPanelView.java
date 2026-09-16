package com.leoskyzwengmail.Internalrecordingaudio.floating;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 悬浮窗面板（长方形半透明组件）。
 *
 * 布局：[计时] [实时波形] [开始/暂停] [结束]
 * 拖动空白处可移动，按钮单独响应点击。
 */
public class FloatPanelView extends LinearLayout {

    public interface PanelListener {
        void onToggleClick();

        void onStopClick();
    }

    private TextView timeView;
    private WaveformBarView waveform;
    private TextView toggleView;
    private TextView stopView;

    private PanelListener listener;

    public FloatPanelView(Context context) {
        super(context);
        build(context);
    }

    private void build(Context context) {
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        int padH = dp(10);
        int padV = dp(6);
        setPadding(padH, padV, padH, padV);
        setBackgroundDrawable(makeBackground());

        // ---- 计时 ----
        timeView = new TextView(context);
        timeView.setText("00:00");
        timeView.setTextColor(Color.WHITE);
        timeView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        timeView.setTypeface(android.graphics.Typeface.MONOSPACE);
        timeView.setMinWidth(dp(44));
        timeView.setGravity(Gravity.CENTER_VERTICAL);
        addView(timeView, new LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));

        // ---- 波形 ----
        waveform = new WaveformBarView(context);
        LayoutParams waveLp = new LayoutParams(0, dp(30), 1f);
        waveLp.leftMargin = dp(8);
        waveLp.rightMargin = dp(8);
        addView(waveform, waveLp);

        // ---- 开始 / 暂停 ----
        toggleView = makeIconButton(context, "▶");
        addView(toggleView);

        // ---- 结束 ----
        stopView = makeIconButton(context, "■");
        LayoutParams stopLp = (LayoutParams) stopView.getLayoutParams();
        stopLp.leftMargin = dp(6);
        stopView.setLayoutParams(stopLp);
        addView(stopView);

        toggleView.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (listener != null) listener.onToggleClick();
            }
        });
        stopView.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (listener != null) listener.onStopClick();
            }
        });
    }

    private TextView makeIconButton(Context context, String glyph) {
        TextView tv = new TextView(context);
        tv.setText(glyph);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        tv.setGravity(Gravity.CENTER);
        int size = dp(30);
        tv.setBackgroundDrawable(makeCircle(Color.parseColor("#33FFFFFF")));
        LayoutParams lp = new LayoutParams(size, size);
        tv.setLayoutParams(lp);
        return tv;
    }

    private GradientDrawable makeBackground() {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(Color.parseColor("#CC1F2A38"));          // 半透明深蓝灰
        d.setCornerRadius(dp(18));
        d.setStroke(dp(1), Color.parseColor("#4DFFFFFF"));
        return d;
    }

    private GradientDrawable makeCircle(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        return d;
    }

    public void setPanelListener(PanelListener l) {
        this.listener = l;
    }

    public void setTimeText(String text) {
        timeView.setText(text);
    }

    /** @param recording 正在录制；@param paused 已暂停 */
    public void setToggleState(boolean recording, boolean paused) {
        toggleView.setText(recording ? "⏸" : (paused ? "▶" : "●"));
        stopView.setEnabled(recording || paused);
        stopView.setAlpha((recording || paused) ? 1f : 0.4f);
    }

    public void setWaveform(float[] bars, float[] peaks, float thresholdDb,
                            boolean active, boolean silent) {
        waveform.setData(bars, peaks, thresholdDb, active, silent);
    }

    /** 录制中面板整体偏红，一眼可辨 */
    public void setAccent(boolean recording) {
        GradientDrawable d = makeBackground();
        d.setColor(recording
                ? Color.parseColor("#D63A2F2F")
                : Color.parseColor("#CC1F2A38"));
        setBackgroundDrawable(d);
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }
}
