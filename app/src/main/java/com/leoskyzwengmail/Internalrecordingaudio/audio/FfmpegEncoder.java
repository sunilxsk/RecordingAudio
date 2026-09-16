package com.leoskyzwengmail.Internalrecordingaudio.audio;

import android.os.Handler;
import android.os.Looper;

import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.FFmpegSession;
import com.arthenica.ffmpegkit.FFmpegSessionCompleteCallback;
import com.arthenica.ffmpegkit.ReturnCode;

import java.io.File;

/**
 * 真正的音频编码器：主路径交给 FFmpeg（AAC / MP3 / Opus / FLAC / WAV）。
 *
 * 与上一版相比增加两道保险：
 * 1. 提交命令前先做「FFmpegKit 可用性预检」（Class.forName 触发静态初始化）。
 *    缺 smart-exception 时 FFmpegKitConfig.<clinit> 会抛 NoClassDefFoundError，
 *    这里在后台线程提前捕获，避免它炸到用户点击「结束」的那个瞬间。
 * 2. 任何一步失败（预检失败 / 执行抛异常 / 返回码非 0）都自动回退到
 *    {@link WavWriter} 包装成标准 WAV —— 绝不让录音白录一场。
 */
public final class FfmpegEncoder {

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** 预检结果缓存：null=未检测，TRUE=可用，FALSE=不可用 */
    private static volatile Boolean available = null;
    private static volatile String unavailableReason = null;

    private FfmpegEncoder() {
    }

    public interface EncodeCallback {
        void onSuccess(File output, long sizeBytes);

        void onFailure(String message);

        void onLog(String line);
    }

    /** 构造 FFmpeg 命令（可见即可调，方便排查） */
    public static String buildCommand(String pcmPath, RecordingConfig cfg, String outPath) {
        StringBuilder sb = new StringBuilder();
        sb.append("-hide_banner -y ");
        sb.append("-f ").append(cfg.pcmInputFormat());
        sb.append(" -ar ").append(cfg.sampleRate);
        sb.append(" -ac ").append(Math.max(1, cfg.channelCount));
        sb.append(" -i ").append(q(pcmPath)).append(' ');

        String fmt = cfg.format == null ? RecordingConfig.FORMAT_AAC : cfg.format;

        if (RecordingConfig.FORMAT_AAC.equals(fmt)) {
            sb.append("-c:a aac -b:a ").append(kbps(cfg.bitRate)).append("k -movflags +faststart ");
        } else if (RecordingConfig.FORMAT_MP3.equals(fmt)) {
            sb.append("-c:a libmp3lame -b:a ").append(kbps(cfg.bitRate)).append("k ");
        } else if (RecordingConfig.FORMAT_OPUS.equals(fmt)) {
            sb.append("-c:a libopus -b:a ").append(kbps(cfg.bitRate)).append("k -application audio ");
        } else if (RecordingConfig.FORMAT_FLAC.equals(fmt)) {
            if (cfg.bitDepth >= 32) {
                sb.append("-af aformat=sample_fmts=s32 ");
            }
            sb.append("-c:a flac -compression_level 8 ");
        } else if (RecordingConfig.FORMAT_WAV.equals(fmt)) {
            sb.append("-c:a ").append(wavCodec(cfg)).append(' ');
        } else {
            // PCM：直接落盘，不需要编码命令
            return null;
        }

        sb.append(q(outPath));
        return sb.toString();
    }

    private static String wavCodec(RecordingConfig cfg) {
        if (cfg.bitDepth >= 32) return "pcm_f32le";
        if (cfg.bitDepth == 24) return "pcm_s24le";
        return "pcm_s16le";
    }

    private static int kbps(int bitRate) {
        int k = bitRate / 1000;
        if (k < 32) k = 32;
        if (k > 1024) k = 1024;
        return k;
    }

    private static String q(String path) {
        return "'" + path.replace("'", "'\\''") + "'";
    }

    /**
     * 预检 FFmpegKit 是否真的可用。
     * 必须在后台线程调用（可能触发 native 库加载）。
     */
    public static boolean isAvailable() {
        Boolean cached = available;
        if (cached != null) return cached;

        boolean ok;
        String reason = null;
        try {
            // 触发 FFmpegKitConfig 静态初始化：缺 smart-exception 时就是这一步炸
            Class.forName("com.arthenica.ffmpegkit.FFmpegKitConfig");
            Class.forName("com.arthenica.smartexception.java.Exceptions");
            ok = true;
        } catch (Throwable t) {
            ok = false;
            reason = t.getClass().getSimpleName()
                    + (t.getMessage() == null ? "" : ": " + t.getMessage());
        }
        available = ok;
        unavailableReason = reason;
        return ok;
    }

    /** 最近一次预检失败原因（仅用于提示） */
    public static String unavailableReason() {
        return unavailableReason;
    }

    /** 供开发排查：手动重置预检缓存 */
    public static void resetProbe() {
        available = null;
        unavailableReason = null;
    }

    /**
     * 异步编码。PCM 格式不做任何编码，直接重命名返回。
     * FFmpeg 不可用时自动回退 WAV。
     */
    public static void encodeAsync(final String pcmPath,
                                   final RecordingConfig cfg,
                                   final File output,
                                   final EncodeCallback cb) {
        if (RecordingConfig.FORMAT_PCM.equals(cfg.format)) {
            File raw = new File(pcmPath);
            boolean ok = raw.renameTo(output);
            if (ok || (output.exists() && output.length() > 0)) {
                postSuccess(cb, output, output.length(), null);
            } else {
                postFail(cb, "PCM 输出失败");
            }
            return;
        }

        // ---- 预检：不可用直接走 WAV 兜底，不去碰 FFmpegKit ----
        if (!isAvailable()) {
            fallbackWav(pcmPath, cfg, output, cb,
                    "FFmpeg 不可用（" + unavailableReason() + "）");
            return;
        }

        final String cmd;
        try {
            cmd = buildCommand(pcmPath, cfg, output.getAbsolutePath());
        } catch (Throwable t) {
            fallbackWav(pcmPath, cfg, output, cb, "构造命令失败：" + t);
            return;
        }
        if (cmd == null) {
            postFail(cb, "不支持的输出格式: " + cfg.format);
            return;
        }

        try {
            FFmpegKit.executeAsync(cmd, new FFmpegSessionCompleteCallback() {
                @Override
                public void apply(FFmpegSession session) {
                    if (ReturnCode.isSuccess(session.getReturnCode())) {
                        postSuccess(cb, output, output.length(), cmd);
                    } else if (ReturnCode.isCancel(session.getReturnCode())) {
                        postFail(cb, "编码已取消");
                    } else {
                        // 编码失败也回退 WAV，绝不丢录音
                        fallbackWav(pcmPath, cfg, output, cb,
                                "FFmpeg 编码失败：" + session.getFailStackTrace()
                                        + "\n命令：" + cmd);
                    }
                }
            });
        } catch (Throwable t) {
            // executeAsync 自身（含 FFmpegKitConfig 初始化）抛错
            fallbackWav(pcmPath, cfg, output, cb, "FFmpeg 启动失败：" + t);
        }
    }

    /** WAV 兜底：把临时 PCM 封装成 .wav */
    private static void fallbackWav(String pcmPath, RecordingConfig cfg, File preferredOut,
                                    EncodeCallback cb, String reason) {
        File raw = new File(pcmPath);
        File target = preferredOut;

        String name = target.getName();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        File wav = new File(target.getParentFile(), base + ".wav");

        try {
            WavWriter.write(raw, wav, cfg.sampleRate, Math.max(1, cfg.channelCount),
                    cfg.bytesPerSample() * 8);
            if (!wav.exists() || wav.length() <= 44) {
                postFail(cb, reason + "，且 WAV 兜底失败（录音数据为空）\n原始 PCM："
                        + raw.getAbsolutePath());
                return;
            }
            if (!wav.equals(target)) {
                deleteQuietly(target);
            }
            deleteQuietly(raw);
            postSuccess(cb, wav, wav.length(),
                    reason + " → 已自动改用 WAV 保存：" + wav.getName());
        } catch (Throwable t) {
            postFail(cb, reason + "，且 WAV 兜底失败：" + t
                    + "\n原始 PCM 已保留：" + raw.getAbsolutePath());
        }
    }

    private static void deleteQuietly(File f) {
        if (f == null) return;
        try {
            f.delete();
        } catch (Throwable ignore) {
        }
    }

    private static void postSuccess(final EncodeCallback cb, final File out,
                                    final long size, final String notice) {
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                if (cb == null) return;
                if (notice != null) cb.onLog(notice);
                cb.onSuccess(out, size);
            }
        });
    }

    private static void postFail(final EncodeCallback cb, final String msg) {
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                if (cb != null) cb.onFailure(msg);
            }
        });
    }
}
