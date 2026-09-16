package com.leoskyzwengmail.Internalrecordingaudio.audio;

/**
 * 内录音轨 + 麦克风音轨 的实时混音。
 *
 * 两路都在 float 域（-1.0 ~ 1.0）相加，各自带独立增益，
 * 输出做软限幅，避免过载爆音。
 */
public final class AudioMixer {

    private AudioMixer() {
    }

    /**
     * 把 src 叠加到 dst 上（就地修改 dst）。
     *
     * @param dst        主音轨（内录）
     * @param src        副音轨（麦克风）
     * @param dstGain    主音轨增益
     * @param srcGain    副音轨增益
     */
    public static void mixInto(float[] dst, float[] src, float dstGain, float srcGain) {
        int len = Math.min(dst.length, src.length);
        for (int i = 0; i < len; i++) {
            float v = dst[i] * dstGain + src[i] * srcGain;
            dst[i] = softClip(v);
        }
        if (len < dst.length) {
            for (int i = len; i < dst.length; i++) {
                dst[i] = softClip(dst[i] * dstGain);
            }
        }
    }

    /**
     * 软限幅：|v| < 0.95 完全线性通过，只有真正要溢出时才平滑压到 1.0。
     *
     * 以前阈值取 0.7，导致稍微响一点的音乐就被 tanh 压缩 → 谐波失真，
     * 听感就是「开了麦克风混录后声音变杂」。阈值提到 0.95 后对正常音量全透明，
     * 只在混合后真会削波时才介入。
     */
    private static final float KNEE = 0.95f;

    private static float softClip(float v) {
        if (v > KNEE) {
            return KNEE + (1.0f - KNEE) * (float) Math.tanh((v - KNEE) / (1.0f - KNEE));
        }
        if (v < -KNEE) {
            return -KNEE - (1.0f - KNEE) * (float) Math.tanh((-v - KNEE) / (1.0f - KNEE));
        }
        return v;
    }
}
