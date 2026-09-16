package com.leoskyzwengmail.Internalrecordingaudio.floating;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.core.app.ServiceCompat;

import com.leoskyzwengmail.Internalrecordingaudio.MainActivity;
import com.leoskyzwengmail.Internalrecordingaudio.R;
import com.leoskyzwengmail.Internalrecordingaudio.audio.AudioCaptureManager;
import com.leoskyzwengmail.Internalrecordingaudio.audio.RecordingConfig;
import com.leoskyzwengmail.Internalrecordingaudio.util.AppFileHelper;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 悬浮窗服务（长方形半透明面板版）。
 *
 * 面板内容：计时 · 实时波形 · 开始/暂停 · 结束。
 * 与 MainActivity 共享 AudioCaptureManager 单例，切后台、Activity 被回收都不会中断录音。
 */
public class FloatWindowService extends Service {

    public static final String ACTION_TOGGLE_RECORD =
            "com.leoskyzwengmail.Internalrecordingaudio.ACTION_FLOAT_TOGGLE";
    public static final String ACTION_EXIT_FLOAT =
            "com.leoskyzwengmail.Internalrecordingaudio.ACTION_FLOAT_EXIT";

    private static final int NOTIFICATION_ID = 1002;
    private static final String CHANNEL_ID = "float_window_channel";

    private WindowManager windowManager;
    private WindowManager.LayoutParams floatParams;
    private FloatPanelView floatPanel;
    private boolean isFloatViewShowing = false;

    private AudioCaptureManager audioCaptureManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /** 启动录制的防抖时间戳 */
    private volatile long lastStartRequestTime = 0;

    private final AudioCaptureManager.OnRecordStateListener stateListener =
            new AudioCaptureManager.OnRecordStateListener() {

                @Override
                public void onStateChanged(final int state) {
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (state != AudioCaptureManager.STATE_PAUSED) {
                                lastStartRequestTime = 0;
                            }
                            updateFloatState(state);
                            refreshNotification();
                        }
                    });
                }

                @Override
                public void onError(final String error) {
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(FloatWindowService.this, error, Toast.LENGTH_SHORT).show();
                        }
                    });
                }

                @Override
                public void onRecordFinished(final File file, final long fileSize) {
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(FloatWindowService.this,
                                    "已保存\n" + file.getName() + "\n"
                                            + AppFileHelper.formatFileSize(fileSize),
                                    Toast.LENGTH_LONG).show();
                            updateFloatState(AudioCaptureManager.STATE_IDLE);
                            refreshNotification();
                        }
                    });
                }

                @Override
                public void onDurationUpdate(final long durationMs) {
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (floatPanel == null || audioCaptureManager == null) return;
                            int state = audioCaptureManager.getState();
                            if (state == AudioCaptureManager.STATE_RECORDING
                                    || state == AudioCaptureManager.STATE_PAUSED) {
                                floatPanel.setTimeText(formatDuration(durationMs));
                            }
                        }
                    });
                }
            };

    /** 波形数据（来自录音核心的电平回调） */
    private final AudioCaptureManager.OnLevelListener levelListener =
            new AudioCaptureManager.OnLevelListener() {
                @Override
                public void onLevel(float dbFs, boolean silent, float[] bars, float[] peaks) {
                    if (floatPanel == null || audioCaptureManager == null) return;
                    int state = audioCaptureManager.getState();
                    RecordingConfig cfg = audioCaptureManager.getConfig();
                    float th = cfg.silenceMode == RecordingConfig.SILENCE_OFF
                            ? -120f : cfg.silenceThresholdDb;
                    floatPanel.setWaveform(bars, peaks, th,
                            state == AudioCaptureManager.STATE_RECORDING, silent);
                }
            };

    @Override
    public void onCreate() {
        super.onCreate();
        FloatWindowServiceProxy.getInstance().setService(this);

        createNotificationChannel();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            } else {
                startForeground(NOTIFICATION_ID, buildNotification());
            }
        } catch (Throwable e) {
            AppFileHelper.writeErrorLog("FloatWindowService.startForeground", e);
        }

        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        audioCaptureManager = AudioCaptureManager.get(this);
        audioCaptureManager.addOnRecordStateListener(stateListener);
        audioCaptureManager.addOnLevelListener(levelListener);

        showFloatView();
        updateFloatState(audioCaptureManager.getState());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if (ACTION_EXIT_FLOAT.equals(action)) {
                stopSelf();
                return START_NOT_STICKY;
            }
            if (ACTION_TOGGLE_RECORD.equals(action)) {
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        onToggleClick();
                    }
                });
            }
        }
        return START_STICKY;
    }

    // ============ 悬浮面板 ============

    private void showFloatView() {
        if (isFloatViewShowing) return;
        if (floatPanel != null) {
            try {
                windowManager.removeView(floatPanel);
            } catch (Exception ignore) {
            }
            floatPanel = null;
        }
        if (windowManager == null) {
            windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        }

        floatPanel = new FloatPanelView(this);
        floatPanel.setPanelListener(new FloatPanelView.PanelListener() {
            @Override
            public void onToggleClick() {
                // 必须显式限定外部类：接口方法也叫 onToggleClick，
                // 直接写 onToggleClick() 会解析成匿名内部类自己 → 无限递归 →
                // StackOverflowError（点悬浮窗开始录制必崩就是这个）。
                FloatWindowService.this.onToggleClick();
            }

            @Override
            public void onStopClick() {
                if (audioCaptureManager.isBusy()) {
                    audioCaptureManager.stopRecording();
                    Toast.makeText(FloatWindowService.this, "已停止，正在编码保存",
                            Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(FloatWindowService.this, "当前没有在录音",
                            Toast.LENGTH_SHORT).show();
                }
            }
        });

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        floatParams = new WindowManager.LayoutParams(
                dp(244), dp(52), type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT);
        floatParams.gravity = Gravity.TOP | Gravity.START;
        floatParams.x = 0;
        floatParams.y = dp(160);

        try {
            windowManager.addView(floatPanel, floatParams);
            isFloatViewShowing = true;
        } catch (Throwable e) {
            AppFileHelper.writeErrorLog("showFloatView", e);
            isFloatViewShowing = false;
            floatPanel = null;
            Toast.makeText(this, "悬浮窗显示失败，请检查悬浮窗权限", Toast.LENGTH_SHORT).show();
            return;
        }

        // 拖动空白处移动面板；按钮由子 View 自行消费点击
        floatPanel.setOnTouchListener(new View.OnTouchListener() {
            private int initialX;
            private int initialY;
            private float initialTouchX;
            private float initialTouchY;
            private boolean moved;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        // 返回 true 才能持续收到后续 MOVE。
                        // 按钮区域内的 DOWN 会先被子 View 消费，不会走到这里。
                        initialX = floatParams.x;
                        initialY = floatParams.y;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        moved = false;
                        return true;

                    case MotionEvent.ACTION_MOVE:
                        float dx = event.getRawX() - initialTouchX;
                        float dy = event.getRawY() - initialTouchY;
                        if (Math.abs(dx) < 4 && Math.abs(dy) < 4 && !moved) {
                            return true;
                        }
                        moved = true;
                        floatParams.x = initialX + (int) dx;
                        floatParams.y = initialY + (int) dy;
                        try {
                            windowManager.updateViewLayout(floatPanel, floatParams);
                        } catch (Throwable ignore) {
                        }
                        return true;

                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        return true;
                }
                return false;
            }
        });
    }

    /** 开始 / 暂停 / 继续 */
    private void onToggleClick() {
        int state = audioCaptureManager.getState();

        if (state == AudioCaptureManager.STATE_IDLE) {
            if (!checkAudioPermission()) {
                Toast.makeText(this, "请先授予录音权限", Toast.LENGTH_SHORT).show();
                return;
            }
            if (!AppFileHelper.ensureRecordDir(this)) {
                Toast.makeText(this, "存储目录不可用，请检查存储权限", Toast.LENGTH_SHORT).show();
                return;
            }

            if (FloatWindowServiceProxy.hasPendingResult()) {
                startRecordingWithPendingResult();
                return;
            }

            syncConfigToManager();

            long now = System.currentTimeMillis();
            if (now - lastStartRequestTime < 1500) return;
            lastStartRequestTime = now;

            Intent intent = new Intent(this, FloatProxyActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                startActivity(intent);
            } catch (Throwable e) {
                AppFileHelper.writeErrorLog("startFloatProxy", e);
                Toast.makeText(this, "无法弹出授权界面", Toast.LENGTH_SHORT).show();
                lastStartRequestTime = 0;
            }

        } else if (state == AudioCaptureManager.STATE_RECORDING) {
            audioCaptureManager.pauseRecording();
            Toast.makeText(this, "已暂停", Toast.LENGTH_SHORT).show();
        } else if (state == AudioCaptureManager.STATE_PAUSED) {
            audioCaptureManager.resumeRecording();
            Toast.makeText(this, "继续录制", Toast.LENGTH_SHORT).show();
        }
        refreshNotification();
    }

    /** 由 Proxy 调用 */
    public void handleMediaProjectionResult(final int resultCode, final Intent data) {
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (resultCode == Activity.RESULT_OK && data != null) {
                    startRecordingWithPendingResult();
                } else {
                    Toast.makeText(FloatWindowService.this, "已取消授权", Toast.LENGTH_SHORT).show();
                    FloatWindowServiceProxy.clearPendingResult();
                }
            }
        }, 300);
    }

    private void startRecordingWithPendingResult() {
        if (!FloatWindowServiceProxy.hasPendingResult()) {
            Toast.makeText(this, "授权无效，请重新点击开始", Toast.LENGTH_SHORT).show();
            return;
        }
        syncConfigToManager();
        AppFileHelper.ensureRecordDir(this);

        audioCaptureManager.startRecording(generateFileName(),
                FloatWindowServiceProxy.getPendingResultCode(),
                FloatWindowServiceProxy.getPendingData());
        Toast.makeText(this, "开始录音", Toast.LENGTH_SHORT).show();
        FloatWindowServiceProxy.clearPendingResult();
    }

    private void syncConfigToManager() {
        RecordingConfig config = AppFileHelper.loadConfig(this);
        config.channelCount = 2;
        audioCaptureManager.setConfig(config);
    }

    private String generateFileName() {
        SimpleDateFormat dateFormat =
                new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault());
        return "Float_" + dateFormat.format(new Date());
    }

    private void updateFloatState(int state) {
        if (floatPanel == null) return;
        boolean recording = state == AudioCaptureManager.STATE_RECORDING;
        boolean paused = state == AudioCaptureManager.STATE_PAUSED;

        floatPanel.setAccent(recording);
        floatPanel.setToggleState(recording, paused);
        if (state == AudioCaptureManager.STATE_IDLE) {
            floatPanel.setTimeText("00:00");
            floatPanel.setWaveform(new float[64], new float[64], -120f, false, false);
        } else if (state == AudioCaptureManager.STATE_ENCODING) {
            floatPanel.setTimeText("编码…");
        }
        floatPanel.invalidate();
    }

    private boolean checkAudioPermission() {
        return checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    // ============ 通知 ============

    private Notification buildNotification() {
        int state = audioCaptureManager != null
                ? audioCaptureManager.getState() : AudioCaptureManager.STATE_IDLE;

        String text;
        if (state == AudioCaptureManager.STATE_RECORDING) {
            text = "正在录制：" + formatDuration(audioCaptureManager.getCurrentDurationMs());
        } else if (state == AudioCaptureManager.STATE_PAUSED) {
            text = "已暂停";
        } else if (state == AudioCaptureManager.STATE_ENCODING) {
            text = "正在编码保存…";
        } else {
            text = getString(R.string.notification_float_text);
        }

        Intent contentIntent = new Intent(this, MainActivity.class);
        contentIntent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 10, contentIntent, pendingFlags());

        Intent stopIntent = new Intent(this, FloatWindowService.class)
                .setAction(ACTION_TOGGLE_RECORD);
        PendingIntent stopPi = PendingIntent.getService(this, 11, stopIntent, pendingFlags());

        Intent exitIntent = new Intent(this, FloatWindowService.class)
                .setAction(ACTION_EXIT_FLOAT);
        PendingIntent exitPi = PendingIntent.getService(this, 12, exitIntent, pendingFlags());

        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.notification_float_title))
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentIntent(pi)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .addAction(android.R.drawable.ic_media_pause, "开始/停止", stopPi)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "退出悬浮窗", exitPi)
                .build();
    }

    private void refreshNotification() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTIFICATION_ID, buildNotification());
        } catch (Throwable ignore) {
        }
    }

    private static int pendingFlags() {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return flags;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null || nm.getNotificationChannel(CHANNEL_ID) != null) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "悬浮窗", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("悬浮面板控制录音");
        channel.setSound(null, null);
        nm.createNotificationChannel(channel);
    }

    // ============ 工具 ============

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private static String formatDuration(long ms) {
        long totalSec = ms / 1000;
        long min = totalSec / 60;
        long sec = totalSec % 60;
        return String.format(Locale.getDefault(), "%02d:%02d", min, sec);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (audioCaptureManager != null) {
            audioCaptureManager.removeOnRecordStateListener(stateListener);
            audioCaptureManager.removeOnLevelListener(levelListener);
        }
        if (floatPanel != null && isFloatViewShowing) {
            try {
                windowManager.removeView(floatPanel);
            } catch (Throwable ignore) {
            }
        }
        isFloatViewShowing = false;
        floatPanel = null;
        FloatWindowServiceProxy.getInstance().clear();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
