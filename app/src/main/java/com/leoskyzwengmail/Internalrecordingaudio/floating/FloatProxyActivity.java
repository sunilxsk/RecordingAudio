package com.leoskyzwengmail.Internalrecordingaudio.floating;

import android.app.Activity;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;

import com.leoskyzwengmail.Internalrecordingaudio.audio.AudioCaptureManager;
import com.leoskyzwengmail.Internalrecordingaudio.util.AppFileHelper;

/**
 * 透明代理 Activity：只为在没法弹系统授权对话框的悬浮窗场景下，
 * 代跑一次 MediaProjection 授权，拿到 resultData 后立刻把结果交给悬浮窗服务。
 *
 * 1. 重建（横竖屏/内存回收）时不再重复弹授权对话框；
 * 2. 授权取消/失败时也会回调 Proxy，避免悬浮球一直卡在「等待授权」；
 * 3. taskAffinity 隔离，避免把主任务栈带到前台。
 */
public class FloatProxyActivity extends Activity {

    private static final int REQUEST_MEDIA_PROJECTION = 100;
    private static final String KEY_REQUESTED = "key_requested";

    private MediaProjectionManager projectionManager;
    private volatile boolean isResultHandled = false;
    private volatile boolean hasRequested = false;
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL);
        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH);

        setContentView(new android.view.View(this) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                setMeasuredDimension(1, 1);
            }
        });

        if (savedInstanceState != null) {
            hasRequested = savedInstanceState.getBoolean(KEY_REQUESTED, false);
        }

        projectionManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);

        // 重建后不再重复弹授权，把「已取消」交回去，让用户重新点悬浮球
        if (hasRequested) {
            finishWithResult(RESULT_CANCELED, null);
            return;
        }

        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                requestProjection();
            }
        }, 150);
    }

    private void requestProjection() {
        if (isFinishing() || hasRequested) return;
        try {
            Intent intent = projectionManager.createScreenCaptureIntent();
            hasRequested = true;
            startActivityForResult(intent, REQUEST_MEDIA_PROJECTION);
        } catch (Throwable e) {
            AppFileHelper.writeErrorLog("FloatProxyActivity.requestProjection", e);
            finishWithResult(RESULT_CANCELED, null);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_MEDIA_PROJECTION) {
            finishWithResult(resultCode, data);
        }
    }

    private void finishWithResult(final int resultCode, final Intent data) {
        if (isResultHandled) return;
        isResultHandled = true;

        // 趁本 Activity 还在前台，先把 mediaProjection 类型的前台服务拉起来
        if (resultCode == RESULT_OK && data != null) {
            AudioCaptureManager.preStartProjectionService(this);
        }

        FloatWindowServiceProxy.getInstance().onMediaProjectionResult(resultCode, data);

        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    finish();
                    overridePendingTransition(0, 0);
                } catch (Throwable ignore) {
                }
            }
        }, 200);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(KEY_REQUESTED, hasRequested);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        if (!isResultHandled) {
            FloatWindowServiceProxy.getInstance()
                    .onMediaProjectionResult(RESULT_CANCELED, null);
        }
    }
}
