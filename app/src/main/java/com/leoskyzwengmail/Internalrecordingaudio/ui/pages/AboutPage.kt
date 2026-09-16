package com.leoskyzwengmail.Internalrecordingaudio.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import androidx.core.graphics.drawable.toBitmap
import com.leoskyzwengmail.Internalrecordingaudio.ui.components.AppIcon
import com.leoskyzwengmail.Internalrecordingaudio.ui.components.PermissionCard
import com.leoskyzwengmail.Internalrecordingaudio.ui.components.SectionCard

/** 第 4 页：应用介绍 —— 图标 / 权限 / 使用指导 / 开源许可 / 恢复默认。 */
@Composable
fun AboutPage(
    onRequestPermissions: () -> Unit,
    onOpenManageStorage: () -> Unit,
    onOpenOverlaySettings: () -> Unit,
    onResetDefaults: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item { AppHeader() }
        item {
            PermissionCard(
                onRequestPermissions = onRequestPermissions,
                onOpenManageStorage = onOpenManageStorage,
                onOpenOverlaySettings = onOpenOverlaySettings,
            )
        }
        item { UsageGuide() }
        item { LicenseList() }
        item {
            SectionCard(title = "其他", icon = AppIcon.Refresh) {
                Text(
                    "恢复默认设置：采样率 44100 Hz、16 bit、128 kbps、AAC、全局内录，" +
                        "静音检测与自动停止全部关闭。已录制的音频不会被删除。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))
                FilledTonalButton(onClick = onResetDefaults) {
                    AppIcon(
                        icon = AppIcon.Refresh,
                        tint = MaterialTheme.colorScheme.primary,
                        size = 18.dp,
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("恢复默认设置")
                }
            }
        }
    }
}

@Composable
private fun AppHeader() {
    val context = LocalContext.current
    val icon = remember(context) {
        try {
            context.packageManager.getApplicationIcon(context.packageName)
                .toBitmap(96, 96).asImageBitmap()
        } catch (e: Exception) {
            null
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (icon != null) {
            androidx.compose.foundation.Image(
                bitmap = icon,
                contentDescription = null,
                modifier = Modifier
                    .size(88.dp)
                    .clip(RoundedCornerShape(20.dp)),
            )
        } else {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(88.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    AppIcon(
                        icon = AppIcon.Mic,
                        tint = MaterialTheme.colorScheme.primary,
                        size = 44.dp,
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            "音频内录",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            "v${versionName()} · 内部录音 / 内录 + 麦克风",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "https://github.com/sunilxsk/RecordingAudio",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable {
                val intent = android.content.Intent(
                    android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse("https://github.com/sunilxsk/RecordingAudio")
                )
                context.startActivity(intent)
            },
        )
    }
}

@Composable
private fun versionName(): String {
    val context = LocalContext.current
    return remember(context) {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "-"
        } catch (e: Exception) {
            "-"
        }
    }
}

@Composable
private fun UsageGuide() {
    SectionCard(title = "介绍与使用指导", icon = AppIcon.Info) {
        GuideItem("1. 授权", "首次录音会弹出屏幕捕获授权，允许后才能采集内部音频。Android 14+ 会自动拉起前台服务。")
        GuideItem("2. 开始录音", "第 1 页点圆形按钮开始；通知栏和悬浮窗都能暂停 / 结束。")
        GuideItem("3. 编码", "录音过程只写原始 PCM，结束后由 FFmpeg 编码成所选格式。若 FFmpeg 不可用会自动降级为 WAV，不会丢录音。")
        GuideItem("4. 静音检测", "「剪掉静音段」在内存中丢弃超时静音，不截断文件，接缝无爆音；也可设为静音自动停止。")
        GuideItem("5. 播放", "第 3 页点播放后会启动媒体会话，状态栏 / 锁屏会出现播放控件，退出应用也继续播放。")
        GuideItem("6. 存储", "优先保存到 内部存储/Music/Internalrecording；没有全盘权限时自动降级到应用私有目录，第 3 页两个目录都会列出。")
    }
}

@Composable
private fun GuideItem(title: String, body: String) {
    Column(modifier = Modifier.padding(vertical = 5.dp)) {
        Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        Text(
            body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LicenseList() {
    SectionCard(title = "开源许可", icon = AppIcon.AudioFile) {
        LicenseItem("AndroidX / Jetpack Compose", "Apache License 2.0")
        HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
        LicenseItem("Jetpack Media3 (ExoPlayer / MediaSession)", "Apache License 2.0")
        HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
        LicenseItem("FFmpegKit", "LGPL 3.0（full 包含 GPL 组件，仅用于本地编码）")
        HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
        LicenseItem("smart-exception", "MIT License")
        HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
        LicenseItem("FFmpeg", "LGPL 2.1 / GPL 2.0（依构建配置）")
    }
}

@Composable
private fun LicenseItem(name: String, license: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyMedium)
            Text(
                license,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
