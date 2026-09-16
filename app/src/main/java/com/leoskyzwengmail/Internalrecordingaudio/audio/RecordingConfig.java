package com.leoskyzwengmail.Internalrecordingaudio.audio;

import android.media.AudioFormat;
import android.os.Build;

import java.util.ArrayList;
import java.util.List;

/**
 * 一次录音的全部参数。UI（Compose）与悬浮球都从这里取值，
 * 保证「设置项真正生效」：采样率 / 位深 / 码率 会同时作用于
 * AudioRecord 采集 与 FFmpeg 编码命令。
 */
public class RecordingConfig {

    // ---- 静音检测模式 ----
    public static final int SILENCE_OFF = 0;   // 关闭
    public static final int SILENCE_STOP = 1;  // 检测到静音自动停止
    public static final int SILENCE_TRIM = 2;  // 检测到静音剪掉该段

    // ---- 自动停止模式 ----
    public static final int AUTO_STOP_OFF = 0;
    public static final int AUTO_STOP_3 = 1;
    public static final int AUTO_STOP_5 = 2;
    public static final int AUTO_STOP_15 = 3;
    public static final int AUTO_STOP_CUSTOM = 4;

    // ---- 输出格式 ----
    public static final String FORMAT_AAC = "aac";
    public static final String FORMAT_MP3 = "mp3";
    public static final String FORMAT_OPUS = "opus";
    public static final String FORMAT_FLAC = "flac";
    public static final String FORMAT_WAV = "wav";
    public static final String FORMAT_PCM = "pcm";

    // ---- 采集参数 ----
    public int sampleRate = 44100;
    public int bitDepth = 16;              // 16 / 24 / 32(float)
    public int bitRate = 128000;           // 有损格式有效
    public int channelCount = 2;

    // ---- 采集来源 ----
    public boolean global = true;          // true=全局内录
    public int usage = 0;                  // 0 = 全部常见 usage
    public List<Integer> targetUids = new ArrayList<Integer>();

    // ---- 输出格式 ----
    public String format = FORMAT_AAC;

    // ---- 静音检测 ----
    public int silenceMode = SILENCE_OFF;
    public float silenceThresholdDb = -55f;   // dBFS
    public int silenceDurationSec = 2;        // 持续静音时长（秒）

    // ---- 自动停止 ----
    public int autoStopMode = AUTO_STOP_OFF;
    public int autoStopCustomMin = 10;        // 自定义分钟

    // ---- 麦克风混录 ----
    public boolean micEnabled = false;
    public float micGain = 1.0f;              // 麦克风增益
    public float internalGain = 1.0f;         // 内录增益

    /** 自动停止上限（毫秒），0 表示不限制 */
    public long autoStopLimitMs() {
        switch (autoStopMode) {
            case AUTO_STOP_3:
                return 3 * 60_000L;
            case AUTO_STOP_5:
                return 5 * 60_000L;
            case AUTO_STOP_15:
                return 15 * 60_000L;
            case AUTO_STOP_CUSTOM:
                return Math.max(1, autoStopCustomMin) * 60_000L;
            case AUTO_STOP_OFF:
            default:
                return 0L;
        }
    }

    /** 静音判定阈值（毫秒） */
    public long silenceTriggerMs() {
        return Math.max(1, silenceDurationSec) * 1000L;
    }

    public boolean isLossless() {
        return FORMAT_WAV.equals(format)
                || FORMAT_FLAC.equals(format)
                || FORMAT_PCM.equals(format);
    }

    /** 输出文件扩展名 */
    public String extension() {
        if (FORMAT_AAC.equals(format)) return "m4a";
        if (FORMAT_MP3.equals(format)) return "mp3";
        if (FORMAT_OPUS.equals(format)) return "ogg";
        if (FORMAT_FLAC.equals(format)) return "flac";
        if (FORMAT_WAV.equals(format)) return "wav";
        if (FORMAT_PCM.equals(format)) return "pcm";
        return "m4a";
    }

    /** 每个采样点字节数（写入 PCM 临时文件用） */
    public int bytesPerSample() {
        if (bitDepth >= 32) return 4;
        if (bitDepth == 24) return 3;
        return 2;
    }

    /** 每帧字节数 */
    public int frameBytes() {
        return bytesPerSample() * Math.max(1, channelCount);
    }

    /**
     * AudioRecord 采集编码。位深 24/32 时优先用 ENCODING_PCM_FLOAT 采集
     * （系统混音器内部就是浮点管线，精度更高），
     * 采集后再按目标位深落盘，位深设置才真正有意义。
     */
    public int captureEncoding() {
        if (bitDepth >= 24 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return AudioFormat.ENCODING_PCM_FLOAT;
        }
        return AudioFormat.ENCODING_PCM_16BIT;
    }

    /** 采集编码对应的每采样字节数 */
    public int captureBytesPerSample() {
        return captureEncoding() == AudioFormat.ENCODING_PCM_FLOAT ? 4 : 2;
    }

    /** FFmpeg -f 参数（PCM 临时文件格式） */
    public String pcmInputFormat() {
        if (bitDepth >= 32) return "f32le";
        if (bitDepth == 24) return "s24le";
        return "s16le";
    }

    public int channelConfig() {
        return channelCount <= 1
                ? AudioFormat.CHANNEL_IN_MONO
                : AudioFormat.CHANNEL_IN_STEREO;
    }

    public RecordingConfig copy() {
        RecordingConfig c = new RecordingConfig();
        c.sampleRate = sampleRate;
        c.bitDepth = bitDepth;
        c.bitRate = bitRate;
        c.channelCount = channelCount;
        c.global = global;
        c.usage = usage;
        c.targetUids = new ArrayList<Integer>(targetUids);
        c.format = format;
        c.silenceMode = silenceMode;
        c.silenceThresholdDb = silenceThresholdDb;
        c.silenceDurationSec = silenceDurationSec;
        c.autoStopMode = autoStopMode;
        c.autoStopCustomMin = autoStopCustomMin;
        c.micEnabled = micEnabled;
        c.micGain = micGain;
        c.internalGain = internalGain;
        return c;
    }
}
