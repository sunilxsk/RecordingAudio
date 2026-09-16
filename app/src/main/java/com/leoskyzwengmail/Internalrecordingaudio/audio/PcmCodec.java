package com.leoskyzwengmail.Internalrecordingaudio.audio;

/**
 * PCM 工具：字节 ↔ 归一化浮点（-1.0 ~ 1.0）互转，以及 RMS/dBFS 计算。
 *
 * 整个录音管线内部统一用 float 处理，
 * 这样「内录 + 麦克风」可以直接相加混音，位深只在落盘那一刻决定。
 */
public final class PcmCodec {

    private PcmCodec() {
    }

    /**
     * 把 PCM 字节转成归一化浮点。
     *
     * @param src        原始字节
     * @param offset     起始偏移
     * @param bytesPerSample 2=s16le / 3=s24le / 4=f32le
     * @param sampleCount    采样点个数（= 帧数 × 声道数）
     */
    public static void bytesToFloat(byte[] src, int offset, int bytesPerSample,
                                    int sampleCount, float[] dst) {
        int i = offset;
        for (int s = 0; s < sampleCount; s++) {
            switch (bytesPerSample) {
                case 4: {
                    int bits = (src[i] & 0xFF)
                            | ((src[i + 1] & 0xFF) << 8)
                            | ((src[i + 2] & 0xFF) << 16)
                            | ((src[i + 3] & 0xFF) << 24);
                    dst[s] = Float.intBitsToFloat(bits);
                    i += 4;
                    break;
                }
                case 3: {
                    int v = (src[i] & 0xFF)
                            | ((src[i + 1] & 0xFF) << 8)
                            | ((src[i + 2] & 0xFF) << 16);
                    if ((v & 0x800000) != 0) v |= 0xFF000000; // 符号扩展
                    dst[s] = v / 8388608.0f;
                    i += 3;
                    break;
                }
                default: {
                    int v = (short) ((src[i] & 0xFF) | ((src[i + 1] & 0xFF) << 8));
                    dst[s] = v / 32768.0f;
                    i += 2;
                    break;
                }
            }
        }
    }

    /** 把归一化浮点按目标位深编码成字节，返回写入长度 */
    public static int floatToBytes(float[] src, int sampleCount, int bytesPerSample, byte[] dst) {
        int p = 0;
        for (int s = 0; s < sampleCount; s++) {
            float v = src[s];
            if (v > 1.0f) v = 1.0f;
            else if (v < -1.0f) v = -1.0f;

            switch (bytesPerSample) {
                case 4: {
                    int bits = Float.floatToRawIntBits(v);
                    dst[p++] = (byte) (bits & 0xFF);
                    dst[p++] = (byte) ((bits >> 8) & 0xFF);
                    dst[p++] = (byte) ((bits >> 16) & 0xFF);
                    dst[p++] = (byte) ((bits >> 24) & 0xFF);
                    break;
                }
                case 3: {
                    int v24 = Math.round(v * 8388607.0f);
                    if (v24 > 8388607) v24 = 8388607;
                    if (v24 < -8388608) v24 = -8388608;
                    dst[p++] = (byte) (v24 & 0xFF);
                    dst[p++] = (byte) ((v24 >> 8) & 0xFF);
                    dst[p++] = (byte) ((v24 >> 16) & 0xFF);
                    break;
                }
                default: {
                    int v16 = Math.round(v * 32767.0f);
                    if (v16 > 32767) v16 = 32767;
                    if (v16 < -32768) v16 = -32768;
                    dst[p++] = (byte) (v16 & 0xFF);
                    dst[p++] = (byte) ((v16 >> 8) & 0xFF);
                    break;
                }
            }
        }
        return p;
    }

    /** 计算一段浮点的 RMS 并换算成 dBFS（全静音返回 -120） */
    public static float rmsDb(float[] buf, int length) {
        if (buf == null || length <= 0) return -120f;
        double sum = 0.0;
        for (int i = 0; i < length; i++) {
            double v = buf[i];
            sum += v * v;
        }
        double rms = Math.sqrt(sum / length);
        if (rms <= 1e-9) return -120f;
        double db = 20.0 * Math.log10(rms);
        if (db < -120.0) db = -120.0;
        return (float) db;
    }

    public static void applyGain(float[] buf, float gain) {
        if (gain == 1.0f) return;
        for (int i = 0; i < buf.length; i++) buf[i] *= gain;
    }

    /**
     * 应用增益并做软限幅。
     * 单纯的 applyGain 在 gain > 1 时会直接削顶（硬削波 = 刺耳失真），
     * 所以放大后统一走一次软限幅。
     */
    public static void applyGainSafe(float[] buf, float gain) {
        if (gain == 1.0f) return;
        for (int i = 0; i < buf.length; i++) {
            buf[i] = softLimit(buf[i] * gain);
        }
    }

    /** |v| < 0.95 线性通过，之上平滑压到 1.0（与 AudioMixer 同一套曲线） */
    private static final float KNEE = 0.95f;

    public static float softLimit(float v) {
        if (v > KNEE) return KNEE + (1.0f - KNEE) * (float) Math.tanh((v - KNEE) / (1.0f - KNEE));
        if (v < -KNEE) return -KNEE - (1.0f - KNEE) * (float) Math.tanh((-v - KNEE) / (1.0f - KNEE));
        return v;
    }
}
