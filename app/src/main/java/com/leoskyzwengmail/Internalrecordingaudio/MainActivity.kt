package com.leoskyzwengmail.Internalrecordingaudio

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.leoskyzwengmail.Internalrecordingaudio.audio.AudioCaptureManager
import com.leoskyzwengmail.Internalrecordingaudio.ui.AppNav
import com.leoskyzwengmail.Internalrecordingaudio.ui.RecordingViewModel
import com.leoskyzwengmail.Internalrecordingaudio.ui.SplashScreen
import com.leoskyzwengmail.Internalrecordingaudio.ui.theme.ConfigureStatusBar
import com.leoskyzwengmail.Internalrecordingaudio.ui.theme.RecordingAudioTheme
import com.leoskyzwengmail.Internalrecordingaudio.ui.theme.StatusBarLavender
import com.leoskyzwengmail.Internalrecordingaudio.ui.theme.StatusBarLavenderDark
import com.leoskyzwengmail.Internalrecordingaudio.util.AppFileHelper

class MainActivity : ComponentActivity() {

    private val viewModel: RecordingViewModel by viewModels()

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            // 借授权回调还在前台的时机，先拉起 mediaProjection 类型的前台服务
            AudioCaptureManager.preStartProjectionService(this)
            viewModel.startRecording(result.resultCode, result.data!!)
        } else {
            toast("需要允许屏幕捕获才能内录")
        }
    }

    /** 写入封面图：拉起系统图片选择 */
    private val imagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) viewModel.applyCover(uri)
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        // 只在「有被拒绝的权限」时提示，全部授予时不再弹 Toast
        val denied = result.filter { !it.value }.keys
        if (denied.isNotEmpty()) {
            toast("缺少权限：${denied.joinToString()}")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            RecordingAudioTheme {
                val darkTheme = androidx.compose.foundation.isSystemInDarkTheme()
                ConfigureStatusBar(
                    color = if (darkTheme) StatusBarLavenderDark else StatusBarLavender,
                    darkIcons = !darkTheme,
                )

                var showSplash by remember { mutableStateOf(true) }
                if (showSplash) {
                    SplashScreen(onFinished = { showSplash = false })
                } else {
                    AppNav(
                        viewModel = viewModel,
                        onRequestProjection = { requestProjection() },
                        onRequestPermissions = { requestBasicPermissions() },
                        onOpenManageStorage = { openManageStorageSettings() },
                        onOpenOverlaySettings = { openOverlaySettings() },
                        onShowToast = { toast(it) },
                        onLaunchImagePicker = {
                            runCatching { imagePickerLauncher.launch("image/*") }
                                .onFailure { toast("无法打开图片选择") }
                        },
                    )
                }
            }
        }

        requestBasicPermissions()
    }

    // ---------- 授权与权限 ----------

    private fun requestProjection() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestBasicPermissions()
            return
        }
        if (!AppFileHelper.ensureRecordDir(this)) {
            toast("存储目录不可用，请先授予文件访问权限")
            openManageStorageSettings()
            return
        }
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(manager.createScreenCaptureIntent())
    }

    private fun requestBasicPermissions() {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            // 应用内播放「最近录音」需要读取公共音频目录
            permissions.add(Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        permissionLauncher.launch(permissions.toTypedArray())
    }

    private fun openManageStorageSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                toast("已拥有全部文件访问权限")
                return
            }
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:$packageName"),
                    )
                )
            } catch (e: Exception) {
                toast("无法打开设置页")
            }
        } else {
            toast("Android 11 以下使用普通存储权限即可")
        }
    }

    private fun openOverlaySettings() {
        if (Settings.canDrawOverlays(this)) {
            toast("已拥有悬浮窗权限")
            return
        }
        try {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName"),
                )
            )
        } catch (e: Exception) {
            toast("无法打开悬浮窗设置页")
        }
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }
}
