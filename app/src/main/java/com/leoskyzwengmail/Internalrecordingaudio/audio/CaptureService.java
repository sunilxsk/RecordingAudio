package com.leoskyzwengmail.Internalrecordingaudio.audio;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.ServiceCompat;

import com.leoskyzwengmail.Internalrecordingaudio.MainActivity;
import com.leoskyzwengmail.Internalrecordingaudio.R;

/**
 * MediaProjection 专用前台服务。
 *
 * Android 14（API 34）硬性要求：必须先有一个「已启动且类型为 mediaProjection」的
 * 前台服务，之后才能调用 getMediaProjection()，
 * 否则直接抛 "Media projections require a foreground service of type ...MEDIA_PROJECTION"。
 */
public class CaptureService extends Service {

    public static final String ACTION_START =
            "com.leoskyzwengmail.Internalrecordingaudio.ACTION_CAPTURE_START";
    public static final String ACTION_STOP =
            "com.leoskyzwengmail.Internalrecordingaudio.ACTION_CAPTURE_STOP";

    private static final String CHANNEL_ID = "audio_capture_channel";
    private static final int NOTIFICATION_ID = 1001;

    public static void start(Context context) {
        Intent intent = new Intent(context, CaptureService.class);
        intent.setAction(ACTION_START);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (Throwable t) {
            android.util.Log.w("CaptureService", "start failed", t);
        }
    }

    public static void stop(Context context) {
        try {
            Intent intent = new Intent(context, CaptureService.class);
            intent.setAction(ACTION_STOP);
            context.startService(intent);
        } catch (Throwable ignore) {
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        Notification notification = buildNotification();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(
                        this,
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
        } catch (Throwable t) {
            android.util.Log.w("CaptureService", "startForeground failed", t);
        }
        AudioCaptureManager.notifyForegroundReady();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopForegroundCompat();
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    private void stopForegroundCompat() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE);
            } else {
                stopForeground(true);
            }
        } catch (Throwable ignore) {
        }
    }

    private Notification buildNotification() {
        Intent contentIntent = new Intent(this, MainActivity.class);
        contentIntent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 1, contentIntent,
                pendingFlags());

        Intent stopIntent = new Intent(AudioCaptureManager.ACTION_STOP_CAPTURE)
                .setPackage(getPackageName());
        PendingIntent stopPi = PendingIntent.getBroadcast(this, 2, stopIntent, pendingFlags());

        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.notification_capture_title))
                .setContentText(getString(R.string.notification_capture_text))
                .setSmallIcon(android.R.drawable.stat_notify_call_mute)
                .setContentIntent(pi)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .addAction(android.R.drawable.ic_media_pause, "停止并保存", stopPi)
                .build();
    }

    private static int pendingFlags() {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return flags;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null || nm.getNotificationChannel(CHANNEL_ID) != null) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "音频内录", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("内录进行中的常驻通知");
        channel.setSound(null, null);
        nm.createNotificationChannel(channel);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
