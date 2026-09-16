package com.leoskyzwengmail.Internalrecordingaudio.audio;

import com.leoskyzwengmail.Internalrecordingaudio.audio.PcmCodec;

/**
 * 静音检测状态机。
 *
 * 只负责「判断当前帧是否静音」以及「记录/结算一段连续静音」，
 * 具体怎么处理（停止 or 剪掉）交给 AudioCaptureManager 决定。
 */
public class SilenceDetector {

    /** 一帧时长内未达到阈值 */
    public static final int EV_NONE = 0;
    /** 静音刚开始 */
    public static final int EV_SILENCE_START = 1;
    /** 静音持续中，且已超过设定时长 */
    public static final int EV_SILENCE_REACHED = 2;
    /** 静音结束（未达阈值，属于正常音频的一部分） */
    public static final int EV_SILENCE_END_SHORT = 3;
    /** 静音结束（静音时长已达到阈值） */
    public static final int EV_SILENCE_END_LONG = 4;

    private float thresholdDb;
    private long triggerMs;

    private long silenceStartFrame = -1;
    /** 最近一段静音的起始帧（静音结束后依然可读，用于裁剪） */
    private long lastSilenceStartFrame = -1;

    // ---------- 麦克风混录时的自适应门限 ----------
    /** 底噪估计（dBFS），快降慢升地跟随最小值 */
    private float noiseFloorDb = -120f;
    private boolean inSilenceNow = false;

    /** 底噪之上多少 dB 才算「有声」——开麦克风时用来忽略环境底噪 */
    private static final float MIC_GATE_DB = 12f;
    /** 回差：已进入静音时要比门限再高 3 dB 才算重新有声，避免在门限附近抖动 */
    private static final float HYSTERESIS_DB = 3f;

    public void configure(float thresholdDb, long triggerMs) {
        this.thresholdDb = thresholdDb;
        this.triggerMs = triggerMs;
    }

    public void reset() {
        silenceStartFrame = -1;
        lastSilenceStartFrame = -1;
        inSilenceNow = false;
    }

    /** 重置底噪估计（每次开始录音时调用） */
    public void resetFloor() {
        noiseFloorDb = -120f;
        inSilenceNow = false;
    }

    /** 当前底噪估计（UI 可显示，便于调阈值） */
    public float noiseFloorDb() {
        return noiseFloorDb;
    }

    /**
     * 判断当前帧是否为静音。
     *
     * 开启麦克风混录后，环境底噪会被一起录进来，固定阈值会不停在
     * 「有声/静音」之间抖动，导致静音检测失效。这里做两件事：
     *   1. 自适应底噪门限：实时跟踪底噪，只有高出底噪 MIC_GATE_DB 才算有声；
     *   2. 回差：已进入静音段要再高 HYSTERESIS_DB 才算恢复有声。
     * 未开麦克风时完全走原逻辑，行为不变。
     */
    public boolean isSilent(float db, boolean micEnabled, float configuredThreshold) {
        float gate = configuredThreshold;

        if (micEnabled) {
            // 快降慢升：瞬时向下跟到底噪，向上则极慢，等价于「近期最小值」
            if (db < noiseFloorDb) {
                noiseFloorDb = db;
            } else {
                noiseFloorDb += (db - noiseFloorDb) * 0.0004f;
            }
            gate = Math.max(configuredThreshold, noiseFloorDb + MIC_GATE_DB);
            if (inSilenceNow) {
                gate += HYSTERESIS_DB;
            }
        }

        boolean silent = db < gate;
        inSilenceNow = silent;
        return silent;
    }

    /** 最近一段静音的起始帧（静音结束后依然可读） */
    public long lastSilenceStartFrame() {
        return lastSilenceStartFrame;
    }

    /** 当前是否处于静音段中 */
    public boolean inSilence() {
        return silenceStartFrame >= 0;
    }

    public long silenceStartFrame() {
        return silenceStartFrame;
    }

    /**
     * @param db        当前帧的 RMS（dBFS）
     * @param framePos  当前帧位置（本帧起始帧号）
     * @param framesToMs 帧号 → 毫秒 的换算函数所需的每毫秒帧数
     * @return 事件
     */
    public int onFrame(float db, long framePos, float framesPerMs) {
        boolean silent = db < thresholdDb;

        if (silent) {
            if (silenceStartFrame < 0) {
                silenceStartFrame = framePos;
                return EV_SILENCE_START;
            }
            long silenceMs = framesToMs(framePos - silenceStartFrame, framesPerMs);
            return silenceMs >= triggerMs ? EV_SILENCE_REACHED : EV_NONE;
        }

        if (silenceStartFrame < 0) {
            return EV_NONE;
        }
        long silenceMs = framesToMs(framePos - silenceStartFrame, framesPerMs);
        lastSilenceStartFrame = silenceStartFrame;
        silenceStartFrame = -1;
        return silenceMs >= triggerMs ? EV_SILENCE_END_LONG : EV_SILENCE_END_SHORT;
    }

    private static long framesToMs(long frames, float framesPerMs) {
        if (framesPerMs <= 0) return 0;
        return (long) (frames / framesPerMs);
    }

    /** 便捷方法：判断一段数据是否静音 */
    public static boolean isSilent(float[] data, float thresholdDb) {
        return PcmCodec.rmsDb(data, data.length) < thresholdDb;
    }
}
