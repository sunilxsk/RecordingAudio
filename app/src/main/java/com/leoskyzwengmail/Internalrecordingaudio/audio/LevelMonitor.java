package com.leoskyzwengmail.Internalrecordingaudio.audio;

/**
 * 电平监测：循环缓冲最近若干帧的音量幅度，供波形/频谱可视化读取。
 *
 * 存的是「归一化 RMS 幅度」（0 ~ 1），与静音检测的 dBFS 判定同源：
 * amp = 10^(dBFS / 20)，因此波形上画一条阈值线，
 * 就能直观看出静音检测是否触发。
 */
public final class LevelMonitor {

    /** 波形柱数量 */
    public static final int BARS = 64;

    private final float[] bars = new float[BARS];
    private final float[] peaks = new float[BARS];
    private int index = 0;
    private int filled = 0;

    private volatile float db = -120f;
    private volatile boolean silent = false;

    public synchronized void reset() {
        for (int i = 0; i < BARS; i++) {
            bars[i] = 0f;
            peaks[i] = 0f;
        }
        index = 0;
        filled = 0;
        db = -120f;
        silent = false;
    }

    /**
     * @param rmsDb   当前帧 RMS（dBFS）
     * @param rmsAmp  当前帧 RMS 归一化幅度（0~1）
     * @param peakAmp 当前帧峰值幅度（0~1）
     * @param isSilent 是否判定为静音
     */
    public synchronized void push(float rmsDb, float rmsAmp, float peakAmp, boolean isSilent) {
        db = rmsDb;
        silent = isSilent;
        bars[index] = clamp(rmsAmp);
        peaks[index] = clamp(peakAmp);
        index = (index + 1) % BARS;
        if (filled < BARS) filled++;
    }

    /** 按时间顺序导出波形（最旧 → 最新），长度固定为 BARS */
    public synchronized float[] snapshotBars() {
        float[] out = new float[BARS];
        for (int i = 0; i < BARS; i++) {
            int src = (index - BARS + i + BARS * 2) % BARS;
            out[i] = bars[src];
        }
        return out;
    }

    public synchronized float[] snapshotPeaks() {
        float[] out = new float[BARS];
        for (int i = 0; i < BARS; i++) {
            int src = (index - BARS + i + BARS * 2) % BARS;
            out[i] = peaks[src];
        }
        return out;
    }

    public float db() {
        return db;
    }

    public boolean silent() {
        return silent;
    }

    /**
     * 单独更新「静音命中」标志。
     * 判定要跟 SilenceDetector 的状态机保持一致（进入静音段才算命中），
     * 否则瞬时电平抖动会让 UI 上的「命中」不停闪烁、且暂停后卡在命中状态。
     */
    public synchronized void markSilent(boolean s) {
        silent = s;
    }

    /** dBFS → 归一化幅度（用于画阈值线） */
    public static float dbToAmp(float db) {
        if (db <= -120f) return 0f;
        float amp = (float) Math.pow(10.0, db / 20.0);
        return clamp(amp);
    }

    private static float clamp(float v) {
        if (v < 0f) return 0f;
        if (v > 1f) return 1f;
        return v;
    }
}
