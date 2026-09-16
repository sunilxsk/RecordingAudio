package com.leoskyzwengmail.Internalrecordingaudio.floating;

import android.content.Intent;

/**
 * 悬浮窗服务与授权结果的桥接层。
 * 采用静态引用，保证 Activity / Service / Proxy 之间可以跨生命周期拿到彼此。
 */
public class FloatWindowServiceProxy {

    private static volatile FloatWindowServiceProxy instance;

    private volatile FloatWindowService service;

    private static volatile int pendingResultCode = -1;
    private static volatile Intent pendingData = null;

    private FloatWindowServiceProxy() {
    }

    public static FloatWindowServiceProxy getInstance() {
        if (instance == null) {
            synchronized (FloatWindowServiceProxy.class) {
                if (instance == null) {
                    instance = new FloatWindowServiceProxy();
                }
            }
        }
        return instance;
    }

    public void setService(FloatWindowService service) {
        this.service = service;
    }

    public FloatWindowService getService() {
        return service;
    }

    public boolean isServiceAlive() {
        return service != null;
    }

    public void onMediaProjectionResult(int resultCode, Intent data) {
        pendingResultCode = resultCode;
        pendingData = data;

        FloatWindowService s = service;
        if (s != null) {
            s.handleMediaProjectionResult(resultCode, data);
        }
    }

    public static void clearPendingResult() {
        pendingResultCode = -1;
        pendingData = null;
    }

    public static boolean hasPendingResult() {
        return pendingResultCode == android.app.Activity.RESULT_OK && pendingData != null;
    }

    public static int getPendingResultCode() {
        return pendingResultCode;
    }

    public static Intent getPendingData() {
        return pendingData;
    }

    public void clear() {
        service = null;
    }
}
