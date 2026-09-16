package com.leoskyzwengmail.Internalrecordingaudio.audio;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import androidx.core.content.ContextCompat;

import com.leoskyzwengmail.Internalrecordingaudio.util.AppFileHelper;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 录音核心（进程内单例）。
 *
 * 本次重构要点：
 * 1. 去掉 MediaCodec 手写编码，录音阶段只写 PCM，结束后交给 FFmpeg 转码；
 *    采样率 / 位深 / 码率 三个设置值在采集与编码两端同时生效。
 * 2. 新增静音检测（关闭 / 静音自动停止 / 剪掉静音段）。
 *    「剪掉」通过 RandomAccessFile 截断实现：PCM 定长，
 *    所以「时间 → 字节偏移」可以精确换算。
 * 3. 新增自动停止（关闭 / 3 / 5 / 15 / 自定义分钟）。
 * 4. 新增「麦克风 + 内录」同时录制：两条独立采集线程 → float 域混音 → 落盘。
 * 5. 时长按「已写入的 PCM 帧数」计算，暂停与剪掉静音后时间轴依然准确。
 */
public class AudioCaptureManager {

    public static final int STATE_IDLE = 0;
    public static final int STATE_RECORDING = 1;
    public static final int STATE_PAUSED = 2;
    public static final int STATE_ENCODING = 3;

    public static final String ACTION_STOP_CAPTURE =
            "com.leoskyzwengmail.Internalrecordingaudio.ACTION_STOP_CAPTURE";

    /** 剪掉静音时保留的呼吸间隔。150ms 太明显，听感像「顿了一下」，压到 80ms */
    private static final long SILENCE_KEEP_MS = 80L;
    /** 剪掉静音后恢复处的淡入时长，配合上面的保留段抹平接缝 */
    private static final long FADE_MS = 30L;

    private static volatile AudioCaptureManager sInstance;

    /** 前台服务已真正执行 startForeground() 的信号 */
    private static volatile CountDownLatch sForegroundReadyLatch;
    private static volatile boolean sForegroundReady = false;

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Object lock = new Object();
    private final CopyOnWriteArrayList<OnRecordStateListener> listeners =
            new CopyOnWriteArrayList<OnRecordStateListener>();

    private volatile MediaProjection mediaProjection;
    private MediaProjection.Callback projectionCallback;

    private volatile AudioRecord internalRecord;
    private volatile AudioRecord micRecord;

    private volatile Thread internalThread;
    private volatile Thread micThread;
    private volatile Thread processThread;

    private volatile RandomAccessFile raf;
    private volatile File tmpFile;
    private volatile File outputFile;

    private volatile int state = STATE_IDLE;
    private volatile RecordingConfig config = new RecordingConfig();
    /** 本次录制实际使用的配置快照，避免录制过程中改设置导致前后不一致 */
    private volatile RecordingConfig activeConfig = new RecordingConfig();
    private volatile boolean running = false;
    private volatile boolean hwStarted = false;
    private volatile long totalFrames = 0;

    private final AtomicBoolean stopping = new AtomicBoolean(false);
    private final SilenceDetector silenceDetector = new SilenceDetector();
    private final LevelMonitor levelMonitor = new LevelMonitor();
    private final CopyOnWriteArrayList<OnLevelListener> levelListeners =
            new CopyOnWriteArrayList<OnLevelListener>();
    /** 电平回调节流时间戳 */
    private volatile long lastLevelDispatch = 0L;
    private static final long LEVEL_INTERVAL_MS = 50L;

    private final LinkedBlockingQueue<float[]> internalQueue = new LinkedBlockingQueue<float[]>(128);
    private final LinkedBlockingQueue<float[]> micQueue = new LinkedBlockingQueue<float[]>(128);

    private Handler timerHandler;
    private Runnable timerRunnable;
    private final StopReceiver stopReceiver = new StopReceiver();

    // ============ 监听接口 ============

    public interface OnRecordStateListener {
        void onStateChanged(int state);

        void onError(String message);

        void onRecordFinished(File file, long sizeBytes);

        void onDurationUpdate(long durationMs);
    }

    /** 实时电平回调（用于波形 / 频谱可视化），已节流到约 20fps */
    public interface OnLevelListener {
        /**
         * @param dbFs   当前 RMS（dBFS）
         * @param silent 是否低于静音阈值
         * @param bars   波形柱（0~1，最旧→最新）
         * @param peaks  峰值包络（0~1）
         */
        void onLevel(float dbFs, boolean silent, float[] bars, float[] peaks);
    }

    // ============ 单例 ============

    public AudioCaptureManager(Context context) {
        this.context = context.getApplicationContext();
        registerStopReceiver();
    }

    public static AudioCaptureManager get(Context context) {
        if (sInstance == null) {
            synchronized (AudioCaptureManager.class) {
                if (sInstance == null) {
                    sInstance = new AudioCaptureManager(context);
                }
            }
        }
        return sInstance;
    }

    private void registerStopReceiver() {
        try {
            IntentFilter filter = new IntentFilter(ACTION_STOP_CAPTURE);
            ContextCompat.registerReceiver(context, stopReceiver, filter,
                    ContextCompat.RECEIVER_NOT_EXPORTED);
        } catch (Throwable ignore) {
        }
    }

    /** 供 CaptureService 回调：前台服务已就绪 */
    public static void notifyForegroundReady() {
        sForegroundReady = true;
        CountDownLatch latch = sForegroundReadyLatch;
        if (latch != null) latch.countDown();
    }

    /** 提前拉起 mediaProjection 类型的前台服务（授权界面还在前台时调用） */
    public static void preStartProjectionService(Context context) {
        sForegroundReady = false;
        sForegroundReadyLatch = new CountDownLatch(1);
        CaptureService.start(context);
    }

    // ============ 配置 ============

    public void setConfig(RecordingConfig config) {
        RecordingConfig incoming = config != null ? config : new RecordingConfig();
        this.config = incoming;

        // 录制过程中也要能改的部分：增益、静音检测、自动停止。
        // 采样率 / 位深 / 格式 / 声道数 / 音源 属于硬件与编码结构参数，
        // 中途改会导致 PCM 前后不一致，只能下次录音生效。
        RecordingConfig active = activeConfig;
        if (active != null && (state == STATE_RECORDING || state == STATE_PAUSED)) {
            active.micGain = incoming.micGain;
            active.internalGain = incoming.internalGain;
            active.silenceMode = incoming.silenceMode;
            active.silenceThresholdDb = incoming.silenceThresholdDb;
            active.silenceDurationSec = incoming.silenceDurationSec;
            active.autoStopMode = incoming.autoStopMode;
            active.autoStopCustomMin = incoming.autoStopCustomMin;

            silenceDetector.configure(active.silenceThresholdDb, active.silenceTriggerMs());
            if (active.silenceMode == RecordingConfig.SILENCE_OFF) {
                silenceDetector.reset();
                levelMonitor.markSilent(false);
                dispatchLevel(true);
            }
        }
    }

    /** 录制中改变音源类参数是否安全 */
    public boolean canApplyLive(RecordingConfig a, RecordingConfig b) {
        if (a == null || b == null) return false;
        return a.sampleRate == b.sampleRate
                && a.bitDepth == b.bitDepth
                && a.channelCount == b.channelCount
                && a.format.equals(b.format);
    }

    public RecordingConfig getConfig() {
        return config;
    }

    public int getSampleRate() {
        return config.sampleRate;
    }

    public int getBitDepth() {
        return config.bitDepth;
    }

    public int getChannelCount() {
        return config.channelCount;
    }

    public int getState() {
        return state;
    }

    public boolean isBusy() {
        return state == STATE_RECORDING || state == STATE_PAUSED;
    }

    public long getCurrentDurationMs() {
        RecordingConfig c = (state == STATE_IDLE) ? config : activeConfig;
        int sr = c.sampleRate;
        if (sr <= 0) return 0L;
        return totalFrames * 1000L / sr;
    }

    // ============ 监听管理 ============

    public void addOnRecordStateListener(OnRecordStateListener listener) {
        if (listener != null && !listeners.contains(listener)) listeners.add(listener);
    }

    public void removeOnRecordStateListener(OnRecordStateListener listener) {
        if (listener != null) listeners.remove(listener);
    }

    public void addOnLevelListener(OnLevelListener listener) {
        if (listener != null && !levelListeners.contains(listener)) levelListeners.add(listener);
    }

    public void removeOnLevelListener(OnLevelListener listener) {
        if (listener != null) levelListeners.remove(listener);
    }

    /** 当前电平快照，供悬浮窗等直接轮询 */
    public LevelMonitor levelMonitor() {
        return levelMonitor;
    }

    /** 后台线程调用：按固定间隔把电平推到主线程；force=true 立即推一次 */
    private void dispatchLevel(boolean force) {
        long now = SystemClock.elapsedRealtime();
        if (!force && now - lastLevelDispatch < LEVEL_INTERVAL_MS) return;
        lastLevelDispatch = now;

        final float db = levelMonitor.db();
        final boolean silent = levelMonitor.silent();
        final float[] bars = levelMonitor.snapshotBars();
        final float[] peaks = levelMonitor.snapshotPeaks();

        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                for (OnLevelListener l : levelListeners) {
                    l.onLevel(db, silent, bars, peaks);
                }
            }
        });
    }

    /** 一段数据的峰值幅度（0~1） */
    private static float peakAmp(float[] data, int length) {
        if (data == null || length <= 0) return 0f;
        float max = 0f;
        for (int i = 0; i < length; i++) {
            float v = data[i] < 0 ? -data[i] : data[i];
            if (v > max) max = v;
        }
        return max > 1f ? 1f : max;
    }

    /** 结束/中断时清空波形 */
    private void resetLevel() {
        levelMonitor.reset();
        lastLevelDispatch = 0L;
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                for (OnLevelListener l : levelListeners) {
                    l.onLevel(-120f, false, new float[LevelMonitor.BARS],
                            new float[LevelMonitor.BARS]);
                }
            }
        });
    }

    private void postState(final int s) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                for (OnRecordStateListener l : listeners) l.onStateChanged(s);
            }
        });
    }

    private void postError(final String msg) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                for (OnRecordStateListener l : listeners) l.onError(msg);
            }
        });
    }

    private void postFinished(final File file, final long size) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                for (OnRecordStateListener l : listeners) l.onRecordFinished(file, size);
            }
        });
    }

    private void postDuration(final long ms) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                for (OnRecordStateListener l : listeners) l.onDurationUpdate(ms);
            }
        });
    }

    // ============ 开始录制 ============

    public void startRecording(final String fileName, final int resultCode, final Intent data) {
        if (state != STATE_IDLE) {
            postError("当前正在录音，请先结束");
            return;
        }
        if (data == null) {
            postError("授权数据无效，请重新授权");
            return;
        }
        if (!AppFileHelper.ensureRecordDir(context)) {
            postError("存储目录创建失败，请检查存储权限");
            return;
        }

        cleanupStaleThread();

        final String name = fileName;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    startRecordInternal(name, resultCode, data);
                } catch (Throwable e) {
                    AppFileHelper.writeErrorLog("startRecording", e);
                    abortRecording("启动录音失败：" + e.getMessage());
                }
            }
        }, "record-starter").start();
    }

    private void cleanupStaleThread() {
        Thread old = processThread;
        if (old == null) return;
        AppFileHelper.writeErrorLog("cleanupStaleThread",
                new RuntimeException("发现残留录音线程，强制清理"));
        running = false;
        processThread = null;
        old.interrupt();
        try {
            old.join(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        releaseResources();
    }

    private void startRecordInternal(String fileName, int resultCode, Intent data) throws Throwable {
        // Android 14+：先起 mediaProjection 类型的前台服务，再取 MediaProjection
        startForegroundServiceAndWait();

        MediaProjectionManager pm =
                (MediaProjectionManager) context.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        MediaProjection projection = pm.getMediaProjection(resultCode, data);
        if (projection == null) {
            throw new java.io.IOException("获取 MediaProjection 失败，请重新授权");
        }
        mediaProjection = projection;
        if (projectionCallback == null) {
            projectionCallback = new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    AppFileHelper.writeErrorLog("MediaProjectionStop",
                            new RuntimeException("用户或系统停止了屏幕捕获"));
                    stopRecording();
                }
            };
        }
        try {
            projection.registerCallback(projectionCallback, null);
        } catch (Throwable ignore) {
        }

        final RecordingConfig cfg = config.copy();
        activeConfig = cfg;

        File dir = AppFileHelper.getRecordDirectory(context);
        outputFile = new File(dir, fileName + "." + cfg.extension());
        tmpFile = new File(AppFileHelper.getTempDir(context), fileName + ".pcm");
        if (tmpFile.exists()) tmpFile.delete();
        raf = new RandomAccessFile(tmpFile, "rw");
        raf.setLength(0);
        totalFrames = 0;

        int captureBps = cfg.captureBytesPerSample();
        int encoding = cfg.captureEncoding();
        int channels = Math.max(1, cfg.channelCount);
        int minBuffer = AudioRecord.getMinBufferSize(cfg.sampleRate, cfg.channelConfig(), encoding);
        if (minBuffer <= 0) {
            // 该设备不支持所设参数，降级到 16bit 再试一次
            encoding = AudioFormat.ENCODING_PCM_16BIT;
            captureBps = 2;
            minBuffer = AudioRecord.getMinBufferSize(cfg.sampleRate, cfg.channelConfig(), encoding);
            if (minBuffer <= 0) {
                throw new java.io.IOException("音频参数不被设备支持：" + cfg.sampleRate + "Hz / "
                        + channels + "ch");
            }
        }

        // ---- 内录 AudioRecord ----
        AudioPlaybackCaptureConfiguration.Builder configBuilder =
                new AudioPlaybackCaptureConfiguration.Builder(mediaProjection);
        boolean hasTargetApp = !cfg.global && cfg.targetUids != null && !cfg.targetUids.isEmpty();
        if (hasTargetApp) {
            List<Integer> uids = cfg.targetUids;
            for (int i = 0; i < uids.size(); i++) {
                configBuilder.addMatchingUid(uids.get(i));
            }
        } else if (cfg.usage != 0) {
            configBuilder.addMatchingUsage(cfg.usage);
        } else {
            configBuilder.addMatchingUsage(AudioAttributes.USAGE_MEDIA);
            configBuilder.addMatchingUsage(AudioAttributes.USAGE_GAME);
            configBuilder.addMatchingUsage(AudioAttributes.USAGE_ALARM);
            configBuilder.addMatchingUsage(AudioAttributes.USAGE_NOTIFICATION);
            configBuilder.addMatchingUsage(AudioAttributes.USAGE_UNKNOWN);
        }

        internalRecord = new AudioRecord.Builder()
                .setAudioFormat(new AudioFormat.Builder()
                        .setSampleRate(cfg.sampleRate)
                        .setChannelMask(cfg.channelConfig())
                        .setEncoding(encoding)
                        .build())
                .setBufferSizeInBytes(minBuffer * 2)
                .setAudioPlaybackCaptureConfig(configBuilder.build())
                .build();

        if (internalRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            throw new java.io.IOException("内录 AudioRecord 初始化失败");
        }

        // ---- 麦克风 AudioRecord（可选） ----
        if (cfg.micEnabled) {
            micRecord = new AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.MIC)
                    .setAudioFormat(new AudioFormat.Builder()
                            .setSampleRate(cfg.sampleRate)
                            .setChannelMask(cfg.channelConfig())
                            .setEncoding(encoding)
                            .build())
                    .setBufferSizeInBytes(minBuffer * 2)
                    .build();
            if (micRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                micRecord.release();
                micRecord = null;
                throw new java.io.IOException("麦克风 AudioRecord 初始化失败，请检查录音权限");
            }
        }

        silenceDetector.configure(cfg.silenceThresholdDb, cfg.silenceTriggerMs());
        silenceDetector.reset();
        silenceDetector.resetFloor();   // 每次录音重新开始估计底噪
        levelMonitor.reset();
        lastLevelDispatch = 0L;

        running = true;
        stopping.set(false);
        internalQueue.clear();
        micQueue.clear();

        startHw();

        startTimer();

        state = STATE_RECORDING;
        postState(STATE_RECORDING);

        int chunkFrames = chunkFrames(cfg.sampleRate);
        internalThread = new Thread(new CaptureRunnable(internalRecord, internalQueue,
                chunkFrames, channels, captureBps), "capture-internal");
        internalThread.start();

        if (micRecord != null) {
            micThread = new Thread(new CaptureRunnable(micRecord, micQueue,
                    chunkFrames, channels, captureBps), "capture-mic");
            micThread.start();
        }

        processThread = new Thread(new ProcessRunnable(cfg), "record-process");
        processThread.start();
    }

    private static int chunkFrames(int sampleRate) {
        return Math.max(1, sampleRate / 50); // 20ms
    }

    private void startForegroundServiceAndWait() {
        sForegroundReady = false;
        sForegroundReadyLatch = new CountDownLatch(1);
        CaptureService.start(context);
        try {
            sForegroundReadyLatch.await(4, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void startHw() {
        synchronized (lock) {
            try {
                if (internalRecord != null
                        && internalRecord.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                    internalRecord.startRecording();
                }
                if (micRecord != null
                        && micRecord.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                    micRecord.startRecording();
                }
                hwStarted = true;
            } catch (Throwable t) {
                hwStarted = false;
                AppFileHelper.writeErrorLog("startHw", new RuntimeException(t));
            }
        }
    }

    private void stopHw() {
        synchronized (lock) {
            hwStarted = false;
            try {
                if (internalRecord != null) internalRecord.stop();
            } catch (Throwable ignore) {
            }
            try {
                if (micRecord != null) micRecord.stop();
            } catch (Throwable ignore) {
            }
        }
    }

    // ============ 暂停 / 继续 ============

    public void pauseRecording() {
        if (state != STATE_RECORDING) return;
        state = STATE_PAUSED;
        stopHw();
        // 暂停后不再有数据流入，必须主动复位：
        // 否则「静音命中」会一直停在暂停前那一刻的状态（UI 卡在命中）。
        silenceDetector.reset();
        levelMonitor.reset();
        dispatchLevel(true);
        postState(STATE_PAUSED);
    }

    public void resumeRecording() {
        if (state != STATE_PAUSED) return;
        startHw();
        state = STATE_RECORDING;
        postState(STATE_RECORDING);
    }

    // ============ 停止 ============

    public void stopRecording() {
        if (state == STATE_IDLE) return;
        if (!stopping.compareAndSet(false, true)) return;
        new Thread(new Runnable() {
            @Override
            public void run() {
                stopInternal();
            }
        }, "record-stop").start();
    }

    private void stopInternal() {
        running = false;

        joinQuietly(internalThread);
        joinQuietly(micThread);
        joinQuietly(processThread);
        internalThread = null;
        micThread = null;
        processThread = null;

        stopHw();

        final RecordingConfig cfg = activeConfig;

        // 收尾：结尾的静音一直扣在处理线程的「待定区」里，从未落盘，
        // 所以这里什么都不用做，天然就把尾部静音去掉了。
        // （以前用 setLength 截断文件，既会卡顿又容易在接缝处爆音，已废弃）

        long frames = totalFrames;
        File raw = tmpFile;
        File out = outputFile;

        try {
            if (raf != null) {
                raf.close();
            }
        } catch (Throwable ignore) {
        }
        raf = null;

        releaseResources();
        CaptureService.stop(context);
        stopTimer();
        resetLevel();

        if (raw == null || frames <= 0 || !raw.exists() || raw.length() <= 0) {
            if (raw != null && raw.exists()) raw.delete();
            state = STATE_IDLE;
            postState(STATE_IDLE);
            postError("没有录到任何声音（目标应用可能没有播放音频，或静音段被全部剪掉）");
            stopping.set(false);
            return;
        }

        state = STATE_ENCODING;
        postState(STATE_ENCODING);

        final File rawFinal = raw;
        final File outFinal = out;
        FfmpegEncoder.encodeAsync(raw.getAbsolutePath(), cfg, out,
                new FfmpegEncoder.EncodeCallback() {
                    @Override
                    public void onSuccess(File output, long sizeBytes) {
                        deleteQuietly(rawFinal);
                        state = STATE_IDLE;
                        postState(STATE_IDLE);
                        postFinished(output, sizeBytes);
                        stopping.set(false);
                    }

                    @Override
                    public void onFailure(String message) {
                        state = STATE_IDLE;
                        postState(STATE_IDLE);
                        postError(message + "\n原始 PCM 已保留：" + rawFinal.getAbsolutePath());
                        stopping.set(false);
                    }

                    @Override
                    public void onLog(String line) {
                        AppFileHelper.writeErrorLog("ffmpeg", new RuntimeException(line));
                    }
                });
    }

    private void abortRecording(String message) {
        running = false;
        joinQuietly(processThread);
        joinQuietly(internalThread);
        joinQuietly(micThread);
        processThread = null;
        internalThread = null;
        micThread = null;
        stopHw();
        try {
            if (raf != null) raf.close();
        } catch (Throwable ignore) {
        }
        raf = null;
        deleteQuietly(tmpFile);
        releaseResources();
        CaptureService.stop(context);
        stopTimer();
        resetLevel();
        state = STATE_IDLE;
        postState(STATE_IDLE);
        postError(message);
        stopping.set(false);
    }

    private void releaseResources() {
        try {
            if (internalRecord != null) internalRecord.release();
        } catch (Throwable ignore) {
        }
        internalRecord = null;
        try {
            if (micRecord != null) micRecord.release();
        } catch (Throwable ignore) {
        }
        micRecord = null;
        try {
            if (mediaProjection != null) {
                if (projectionCallback != null) {
                    try {
                        mediaProjection.unregisterCallback(projectionCallback);
                    } catch (Throwable ignore) {
                    }
                }
                mediaProjection.stop();
            }
        } catch (Throwable ignore) {
        }
        mediaProjection = null;
    }

    // 注：原先「剪掉静音」靠 RandomAccessFile.setLength() 截断文件实现，
    // 既会在裁剪瞬间卡住处理线程，又会在接缝处留下硬切爆音，
    // 现已改为在处理线程用「待定缓冲区」在内存中丢弃静音，此方法已删除。

    private static void joinQuietly(Thread t) {
        if (t == null) return;
        try {
            t.join(1500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void deleteQuietly(File f) {
        if (f == null) return;
        try {
            f.delete();
        } catch (Throwable ignore) {
        }
    }

    // ============ 计时 ============

    private void startTimer() {
        if (timerHandler == null) {
            timerHandler = new Handler(Looper.getMainLooper());
        }
        stopTimer();
        timerRunnable = new Runnable() {
            @Override
            public void run() {
                if (state == STATE_IDLE) return;
                postDuration(getCurrentDurationMs());
                timerHandler.postDelayed(this, 250);
            }
        };
        timerHandler.postDelayed(timerRunnable, 250);
    }

    private void stopTimer() {
        if (timerHandler != null && timerRunnable != null) {
            timerHandler.removeCallbacks(timerRunnable);
        }
        timerRunnable = null;
    }

    // ============ 采集线程 ============

    /** 一条音轨的采集：按固定帧长产出数据块，没有数据时补静音块，保证两路时间轴对齐 */
    private final class CaptureRunnable implements Runnable {

        private final AudioRecord record;
        private final LinkedBlockingQueue<float[]> queue;
        private final int chunkFrames;
        private final int channels;
        private final int bytesPerSample;

        CaptureRunnable(AudioRecord record, LinkedBlockingQueue<float[]> queue,
                        int chunkFrames, int channels, int bytesPerSample) {
            this.record = record;
            this.queue = queue;
            this.chunkFrames = chunkFrames;
            this.channels = channels;
            this.bytesPerSample = bytesPerSample;
        }

        @Override
        public void run() {
            int frameBytes = bytesPerSample * channels;
            byte[] buf = new byte[chunkFrames * frameBytes];
            int samples = chunkFrames * channels;
            int filled = 0;          // buf 中已累积的有效字节
            int errCount = 0;        // 连续错误计数，防止死循环

            while (running) {
                if (!hwStarted) {
                    // 暂停中：硬件已 stop，等待恢复
                    sleepQuietly(20);
                    continue;
                }

                int read;
                try {
                    // 【关键】必须用阻塞读，且一直读到攒满一整块才产出。
                    // 之前用 READ_NON_BLOCKING：一次只读到几十帧却被当成完整的一块
                    // （20ms）交给处理线程计时，导致时间轴被放大十几倍，
                    // 同时每块尾部都是空白 → 听感「放一下空一下」。
                    read = record.read(buf, filled, buf.length - filled);
                } catch (Throwable t) {
                    AppFileHelper.writeErrorLog("CaptureRunnable.read", new RuntimeException(t));
                    if (++errCount > 5) break;
                    sleepQuietly(10);
                    continue;
                }

                if (read > 0) {
                    errCount = 0;
                    filled += read;
                    if (filled >= buf.length) {
                        float[] chunk = new float[samples];
                        PcmCodec.bytesToFloat(buf, 0, bytesPerSample, samples, chunk);
                        filled = 0;
                        offer(chunk);
                    }
                } else if (read == 0) {
                    // 极少见：没读到也没出错，稍等再试
                    sleepQuietly(2);
                } else {
                    // read < 0：绝大多数是暂停/停止时 stop() 打断了阻塞读。
                    // 丢掉半块，回到循环等 hwStarted 恢复；running 为 false 才真正退出。
                    filled = 0;
                    if (!running) break;
                    if (++errCount > 200) {   // 约 4 秒仍未恢复，判定为硬件异常
                        AppFileHelper.writeErrorLog("CaptureRunnable.read",
                                new RuntimeException("AudioRecord 持续返回错误码 " + read));
                        break;
                    }
                    sleepQuietly(20);
                }
            }
        }

        private void offer(float[] chunk) {
            if (!queue.offer(chunk)) {
                queue.poll();
                queue.offer(chunk);
            }
        }
    }

    // ============ 混音 / 静音 / 落盘 线程 ============

    private final class ProcessRunnable implements Runnable {

        private final RecordingConfig cfg;
        private final int chunkFrames;
        private final int samples;
        private final float framesPerMs;

        // ---- 静音「待定区」：静音块先不落盘，等确认是否要剪 ----
        private final ArrayDeque<float[]> pending = new ArrayDeque<float[]>();
        private long pendingFrames = 0;
        /** 缓冲上限，超出后退化成「直接落盘」（放弃裁剪），防止内存无上限增长 */
        private final long pendingCapFrames;
        /** 已超出上限：本次静音放弃裁剪 */
        private boolean pendingOverflow = false;
        private boolean needFadeIn = false;
        /** 是否已经写过至少一个音频块（用于判定「起始静音」） */
        private boolean wroteAny = false;

        private byte[] flushBytes;

        ProcessRunnable(RecordingConfig cfg) {
            this.cfg = cfg;
            this.chunkFrames = chunkFrames(cfg.sampleRate);
            this.samples = chunkFrames * Math.max(1, cfg.channelCount);
            this.framesPerMs = cfg.sampleRate / 1000.0f;
            // 上限取「设定时长 + 2 秒」与 12 秒中的较大者，够用又可控
            long need = (cfg.silenceTriggerMs() + 2000L) * cfg.sampleRate / 1000L;
            this.pendingCapFrames = Math.max(12L * cfg.sampleRate, need);
        }

        private byte[] flushBuffer() {
            if (flushBytes == null) flushBytes = new byte[samples * cfg.bytesPerSample()];
            return flushBytes;
        }

        @Override
        public void run() {
            byte[] outBytes = new byte[samples * cfg.bytesPerSample()];

            while (running) {
                float[] main;
                try {
                    main = internalQueue.poll(200, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                if (main == null) continue;

                // ---- 混音 / 增益（每次都读 cfg，改设置即时生效） ----
                if (cfg.micEnabled) {
                    float[] mic = micQueue.poll();
                    long waited = 0;
                    while (mic == null && running && waited < 40) {
                        try {
                            mic = micQueue.poll(5, TimeUnit.MILLISECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                        waited += 5;
                    }
                    if (mic != null) {
                        AudioMixer.mixInto(main, mic, cfg.internalGain, cfg.micGain);
                    } else {
                        PcmCodec.applyGainSafe(main, cfg.internalGain);
                    }
                } else if (cfg.internalGain != 1.0f) {
                    PcmCodec.applyGainSafe(main, cfg.internalGain);
                }

                // ---- 电平统计（波形可视化） ----
                float db = PcmCodec.rmsDb(main, Math.min(main.length, samples));
                float peak = peakAmp(main, Math.min(main.length, samples));
                levelMonitor.push(db, LevelMonitor.dbToAmp(db), peak, false);

                boolean detect = cfg.silenceMode != RecordingConfig.SILENCE_OFF;
                // 开麦克风时走自适应门限（自动忽略环境底噪），否则用固定阈值
                boolean silent = detect
                        && silenceDetector.isSilent(db, cfg.micEnabled, cfg.silenceThresholdDb);

                if (detect) {
                    levelMonitor.markSilent(silent);

                    if (silent) {
                        // 【起始静音】还没写过任何音频就遇到静音 → 直接丢弃，不进缓冲。
                        // 这样开头无论静音多久都能干净地去掉，不受缓冲上限限制。
                        if (!wroteAny && cfg.silenceMode == RecordingConfig.SILENCE_TRIM) {
                            dispatchLevel(false);
                            continue;
                        }

                        // 静音块先扣在手里，不写文件
                        pending.add(main);
                        pendingFrames += chunkFrames;

                        if (pendingFrames > pendingCapFrames) {
                            // 静音太长，超出缓冲：老老实实写出来，不再裁剪这一段
                            pendingOverflow = true;
                            flushPending();
                        }

                        // 「静音自动停止」：达到设定时长直接收尾
                        if (cfg.silenceMode == RecordingConfig.SILENCE_STOP
                                && silenceMs() >= cfg.silenceTriggerMs()) {
                            stopRecording();
                            break;
                        }
                        dispatchLevel(false);
                        continue;   // 静音期间不落盘
                    }

                    // ---- 有声：结算刚才那段静音 ----
                    if (pendingFrames > 0) {
                        if (cfg.silenceMode == RecordingConfig.SILENCE_TRIM
                                && silenceMs() >= cfg.silenceTriggerMs()
                                && !pendingOverflow) {
                            // 剪掉：只保留尾部一小段作呼吸间隔，其余丢弃。
                            // 全程内存操作，不截断文件 → 不会卡、不会有爆音
                            discardPendingKeepTail();
                            needFadeIn = true;
                        } else {
                            flushPending();
                        }
                        pendingOverflow = false;
                    }
                } else if (pendingFrames > 0) {
                    // 录制中途关掉静音检测：把手里的缓冲补写回去，避免丢音频
                    flushPending();
                    pendingOverflow = false;
                }

                if (needFadeIn) {
                    fadeIn(main, Math.min(main.length, samples));
                    needFadeIn = false;
                }

                dispatchLevel(false);

                // ---- 落盘 ----
                if (!writeChunk(main, outBytes)) break;

                // ---- 自动停止（每次重算，改设置即时生效） ----
                long limitMs = cfg.autoStopLimitMs();
                if (limitMs > 0 && getCurrentDurationMs() >= limitMs) {
                    stopRecording();
                    break;
                }
            }
        }

        /** 当前待定静音的时长（毫秒） */
        private long silenceMs() {
            if (framesPerMs <= 0) return 0;
            return (long) (pendingFrames / framesPerMs);
        }

        private boolean writeChunk(float[] data, byte[] outBytes) {
            int len = PcmCodec.floatToBytes(data, Math.min(data.length, samples),
                    cfg.bytesPerSample(), outBytes);
            try {
                if (raf != null) raf.write(outBytes, 0, len);
            } catch (Throwable t) {
                AppFileHelper.writeErrorLog("ProcessRunnable.write", new RuntimeException(t));
                return false;
            }
            totalFrames += chunkFrames;
            wroteAny = true;
            return true;
        }

        /** 把待定静音原样补写进文件（未达阈值 / 放弃裁剪时） */
        private void flushPending() {
            while (!pending.isEmpty()) {
                float[] chunk = pending.poll();
                if (chunk == null) continue;
                if (!writeChunk(chunk, flushBuffer())) break;
            }
            pendingFrames = 0;
        }

        /**
         * 丢弃待定静音，仅保留末尾约 SILENCE_KEEP_MS 毫秒作呼吸间隔。
         * 纯内存丢弃，绝不做 RandomAccessFile.setLength()——那正是以前
         * 「每次断开卡一下」的元凶。
         */
        private void discardPendingKeepTail() {
            int keepChunks = 0;
            if (framesPerMs > 0 && chunkFrames > 0) {
                long keepFrames = (long) (SILENCE_KEEP_MS * framesPerMs);
                keepChunks = (int) (keepFrames / chunkFrames);
            }
            int size = pending.size();
            int drop = Math.max(0, size - keepChunks);
            for (int i = 0; i < drop; i++) pending.poll();
            pendingFrames = 0;
            // 保留的尾段照常写出
            flushPending();
        }

        /** 剪掉静音后，对恢复的第一块做短淡入，避免波形突变产生「啪」声 */
        private void fadeIn(float[] data, int length) {
            int fadeFrames = (int) (FADE_MS * framesPerMs);
            if (fadeFrames <= 0) return;
            int ch = Math.max(1, cfg.channelCount);
            int fadeSamples = Math.min(length, fadeFrames * ch);
            for (int i = 0; i < fadeSamples; i++) {
                data[i] *= (float) i / fadeSamples;
            }
        }
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ============ 通知栏停止 ============

    private final class StopReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            if (ACTION_STOP_CAPTURE.equals(intent != null ? intent.getAction() : null)) {
                stopRecording();
            }
        }
    }
}
