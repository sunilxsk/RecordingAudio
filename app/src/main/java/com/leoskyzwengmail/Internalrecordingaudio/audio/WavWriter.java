package com.leoskyzwengmail.Internalrecordingaudio.audio;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * WAV（RIFF / PCM）兜底编码器。
 *
 * 用途：当 FFmpegKit 因任何原因不可用（例如 smart-exception 缺失导致
 * FFmpegKitConfig 类初始化失败）时，至少把已录的 PCM 封装成标准 WAV 保存下来，
 * 保证「点了结束不会丢录音」。
 *
 * WAV 头结构（44 字节）：
 *   0  "RIFF"      4  12 "WAVE"
 *  12  "fmt "     16  20 音频格式=1(PCM)
 *                     22 声道数
 *                     24 采样率
 *                     28 字节率 = 采样率 × 声道数 × 每采样字节
 *                     32 块对齐 = 声道数 × 每采样字节
 *                     34 位深
 *  36  "data"     40  数据长度
 */
public final class WavWriter {

    private static final int HEADER_SIZE = 44;

    private WavWriter() {
    }

    /**
     * 把裸 PCM 文件封装成 WAV。
     *
     * @param pcmFile   裸 PCM（s16le / s24le / f32le）
     * @param outFile   输出 .wav
     * @param sampleRate 采样率
     * @param channels   声道数
     * @param bitsPerSample 位深（16 / 24 / 32）
     */
    public static void write(File pcmFile, File outFile, int sampleRate,
                             int channels, int bitsPerSample) throws IOException {
        if (pcmFile == null || !pcmFile.exists()) {
            throw new IOException("PCM 文件不存在");
        }
        long pcmLen = pcmFile.length();

        InputStream in = null;
        OutputStream out = null;
        try {
            in = new FileInputStream(pcmFile);
            out = new BufferedOutputStream(new FileOutputStream(outFile, false), 64 * 1024);

            out.write(buildHeader(sampleRate, channels, bitsPerSample, pcmLen));

            byte[] buf = new byte[64 * 1024];
            int read;
            while ((read = in.read(buf)) > 0) {
                out.write(buf, 0, read);
            }
            out.flush();
        } finally {
            closeQuietly(in);
            closeQuietly(out);
        }
    }

    private static byte[] buildHeader(int sampleRate, int channels,
                                      int bitsPerSample, long pcmLen) {
        int byteRate = sampleRate * channels * bitsPerSample / 8;
        int blockAlign = channels * bitsPerSample / 8;

        ByteBuffer b = ByteBuffer.allocate(HEADER_SIZE);
        b.order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes());
        b.putInt((int) (36 + pcmLen));
        b.put("WAVE".getBytes());
        b.put("fmt ".getBytes());
        b.putInt(16);                 // fmt chunk 大小
        b.putShort((short) 1);        // PCM
        b.putShort((short) channels);
        b.putInt(sampleRate);
        b.putInt(byteRate);
        b.putShort((short) blockAlign);
        b.putShort((short) bitsPerSample);
        b.put("data".getBytes());
        b.putInt((int) pcmLen);
        return b.array();
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (IOException ignore) {
        }
    }
}
