package com.leoskyzwengmail.Internalrecordingaudio.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.leoskyzwengmail.Internalrecordingaudio.audio.RecordingConfig
import com.leoskyzwengmail.Internalrecordingaudio.data.AUTO_STOP_OPTIONS
import com.leoskyzwengmail.Internalrecordingaudio.data.FORMAT_OPTIONS
import com.leoskyzwengmail.Internalrecordingaudio.data.SILENCE_MODE_OPTIONS
import com.leoskyzwengmail.Internalrecordingaudio.data.Settings
import com.leoskyzwengmail.Internalrecordingaudio.ui.theme.PrimaryBlue
import com.leoskyzwengmail.Internalrecordingaudio.util.AppFileHelper
import java.util.Locale

// ---------------- 权限 ----------------

@Composable
fun PermissionCard(
    onRequestPermissions: () -> Unit,
    onOpenManageStorage: () -> Unit,
    onOpenOverlaySettings: () -> Unit,
) {
    SectionCard(title = "权限设置", icon = AppIcon.Tune) {
        Text(
            "需要：录音、通知、所有文件访问（保存录音）、悬浮窗（悬浮球）。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = onRequestPermissions) { Text("基础权限") }
            TextButton(onClick = onOpenManageStorage) { Text("文件访问") }
            TextButton(onClick = onOpenOverlaySettings) { Text("悬浮窗") }
        }
    }
}

// ---------------- 格式 ----------------

@Composable
fun FormatSection(settings: Settings, onUpdate: (Settings) -> Unit) {
    SectionCard(
        title = "输出格式",
        icon = AppIcon.AudioFile,
        subtitle = "录音结束后由 FFmpeg 编码，AAC/MP3/Opus 使用下方码率；FLAC/WAV/PCM 为无损，码率无效。",
    ) {
        ChipGroupRow(
            options = FORMAT_OPTIONS,
            selected = settings.format,
            onSelected = { onUpdate(settings.copy(format = it)) },
        )
    }
}

// ---------------- 音质 ----------------

@Composable
fun QualitySection(settings: Settings, onUpdate: (Settings) -> Unit) {
    SectionCard(
        title = "音质参数",
        icon = AppIcon.GraphicEq,
        subtitle = "采样率与位深同时作用于采集与编码；码率仅对有损格式生效。",
    ) {
        val rates = AppFileHelper.SAMPLE_RATES.map { "$it Hz" }
        DropdownRow(
            label = "采样率",
            options = rates,
            selectedIndex = AppFileHelper.SAMPLE_RATES.indexOf(settings.sampleRate).coerceAtLeast(0),
            onSelected = { onUpdate(settings.copy(sampleRate = AppFileHelper.SAMPLE_RATES[it])) },
        )

        Spacer(modifier = Modifier.height(4.dp))
        Text("位深", style = MaterialTheme.typography.bodyMedium)
        ChipGroupRow(
            options = listOf(16 to "16 bit", 24 to "24 bit", 32 to "32 bit float"),
            selected = settings.bitDepth,
            onSelected = { onUpdate(settings.copy(bitDepth = it)) },
        )

        val lossless = settings.format == RecordingConfig.FORMAT_FLAC
                || settings.format == RecordingConfig.FORMAT_WAV
                || settings.format == RecordingConfig.FORMAT_PCM
        SliderRow(
            label = "码率",
            value = settings.bitRate / 1000f,
            valueRange = 32f..320f,
            steps = 17,
            enabled = !lossless,
            valueText = if (lossless) "无损（无效）" else "${settings.bitRate / 1000} kbps",
            onValueChange = { onUpdate(settings.copy(bitRate = it.toInt() * 1000)) },
        )
    }
}

// ---------------- 音源（第 2 页）----------------

@Composable
fun SourceSection(
    settings: Settings,
    apps: List<AppFileHelper.AppInfo>,
    listHeight: androidx.compose.ui.unit.Dp = 560.dp,
    onUpdate: (Settings) -> Unit,
) {
    var query by remember { mutableStateOf("") }

    SectionCard(
        title = "录制来源",
        icon = AppIcon.Apps,
        subtitle = "默认全局内录；关闭后可只录指定 App 或指定音频类型。",
    ) {
        SwitchRow(
            label = "全局内录",
            description = "录制设备播放的全部声音",
            checked = settings.global,
            onCheckedChange = { onUpdate(settings.copy(global = it)) },
        )

        if (!settings.global) {
            Text("音频类型", style = MaterialTheme.typography.bodyMedium)
            ChipGroupRow(
                options = listOf(
                    0 to "全部",
                    android.media.AudioAttributes.USAGE_MEDIA to "媒体",
                    android.media.AudioAttributes.USAGE_GAME to "游戏",
                    android.media.AudioAttributes.USAGE_ALARM to "闹钟",
                    android.media.AudioAttributes.USAGE_NOTIFICATION to "通知",
                ),
                selected = settings.usage,
                onSelected = { onUpdate(settings.copy(usage = it)) },
            )

            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("搜索应用") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(8.dp))

            val filtered = apps.filter {
                query.isBlank() || it.appName.contains(query, ignoreCase = true)
                        || it.packageName.contains(query, ignoreCase = true)
            }

            // 外层已是 LazyColumn，这里用限高 Column，避免同方向嵌套懒加载布局
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(listHeight)
                    .verticalScroll(rememberScrollState()),
            ) {
                filtered.take(80).forEach { app ->
                    val selected = settings.selectedUids.contains(app.uid)
                    val bmp = remember(app.icon) {
                        try {
                            app.icon?.toBitmap(48, 48)?.asImageBitmap()
                        } catch (e: Exception) {
                            null
                        }
                    }
                    Surface(
                        onClick = {
                            val next = if (selected) {
                                settings.selectedUids - app.uid
                            } else {
                                settings.selectedUids + app.uid
                            }
                            onUpdate(settings.copy(selectedUids = next))
                        },
                        color = if (selected) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surface
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (bmp != null) {
                                Image(
                                    bitmap = bmp,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(RoundedCornerShape(8.dp)),
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant),
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(app.appName, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    app.packageName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (selected) {
                                AppIcon(
                                    icon = AppIcon.Bubble,
                                    tint = MaterialTheme.colorScheme.primary,
                                    size = 20.dp,
                                )
                            }
                        }
                    }
                }
            }
            Text(
                "已选 ${settings.selectedUids.size} 个应用",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ---------------- 静音检测 ----------------

@Composable
fun SilenceSection(settings: Settings, onUpdate: (Settings) -> Unit) {
    SectionCard(
        title = "静音检测",
        icon = AppIcon.Bubble,
        subtitle = "实时计算 RMS(dBFS)。「剪掉静音段」在内存中丢弃超时静音，不截断文件，接缝无爆音。",
    ) {
        ChipGroupRow(
            options = SILENCE_MODE_OPTIONS,
            selected = settings.silenceMode,
            onSelected = { onUpdate(settings.copy(silenceMode = it)) },
        )

        val enabled = settings.silenceMode != RecordingConfig.SILENCE_OFF
        SliderRow(
            label = "静音阈值",
            value = settings.silenceThresholdDb,
            valueRange = -90f..-20f,
            steps = 13,
            enabled = enabled,
            valueText = String.format(Locale.getDefault(), "%.0f dBFS", settings.silenceThresholdDb),
            onValueChange = { onUpdate(settings.copy(silenceThresholdDb = it)) },
        )
        SliderRow(
            label = "持续静音时长",
            value = settings.silenceDurationSec.toFloat(),
            valueRange = 1f..10f,
            steps = 8,
            enabled = enabled,
            valueText = "${settings.silenceDurationSec} 秒",
            onValueChange = { onUpdate(settings.copy(silenceDurationSec = it.toInt())) },
        )
    }
}

// ---------------- 自动停止 ----------------

@Composable
fun AutoStopSection(settings: Settings, onUpdate: (Settings) -> Unit) {
    SectionCard(title = "自动停止", icon = AppIcon.Timer) {
        ChipGroupRow(
            options = AUTO_STOP_OPTIONS,
            selected = settings.autoStopMode,
            onSelected = { onUpdate(settings.copy(autoStopMode = it)) },
        )
        if (settings.autoStopMode == RecordingConfig.AUTO_STOP_CUSTOM) {
            SliderRow(
                label = "自定义时长",
                value = settings.autoStopCustomMin.toFloat(),
                valueRange = 1f..120f,
                steps = 118,
                valueText = "${settings.autoStopCustomMin} 分钟",
                onValueChange = { onUpdate(settings.copy(autoStopCustomMin = it.toInt())) },
            )
        }
    }
}

// ---------------- 麦克风 ----------------

@Composable
fun MicSection(settings: Settings, onUpdate: (Settings) -> Unit) {
    SectionCard(
        title = "麦克风与其他",
        icon = AppIcon.Mic,
        subtitle = "开启后：内录音轨与麦克风音轨在浮点域实时混合，可分别调节增益。",
    ) {
        SwitchRow(
            label = "同时录制麦克风",
            description = "内录 + 麦克风混合为同一条立体声",
            checked = settings.micEnabled,
            onCheckedChange = { onUpdate(settings.copy(micEnabled = it)) },
        )
        SliderRow(
            label = "麦克风增益",
            value = settings.micGain,
            valueRange = 0f..3f,
            steps = 11,
            enabled = settings.micEnabled,
            valueText = String.format(Locale.getDefault(), "%.1fx", settings.micGain),
            onValueChange = { onUpdate(settings.copy(micGain = it)) },
        )
        SliderRow(
            label = "内录音量",
            value = settings.internalGain,
            valueRange = 0f..3f,
            steps = 11,
            valueText = String.format(Locale.getDefault(), "%.1fx", settings.internalGain),
            onValueChange = { onUpdate(settings.copy(internalGain = it)) },
        )
    }
}

// ---------------- 悬浮球 ----------------

@Composable
fun FloatSection(
    isRunning: Boolean,
    onToggleFloat: () -> Unit,
    onOpenOverlaySettings: () -> Unit,
) {
    SectionCard(
        title = "悬浮窗",
        icon = AppIcon.Bubble,
        subtitle = "长方形半透明面板：计时 · 波形 · 开始/暂停 · 结束。可在后台继续录音。",
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = onToggleFloat) {
                Text(if (isRunning) "关闭悬浮窗" else "开启悬浮窗")
            }
            TextButton(onClick = onOpenOverlaySettings) { Text("悬浮窗权限") }
        }
    }
}

// ---------------- 通用控件 ----------------

@Composable
fun CircleActionButton(
    icon: AppIcon,
    description: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val scale by androidx.compose.animation.core.animateFloatAsState(
        if (enabled) 1f else 0.85f,
        label = "btn",
    )
    val contentColor = if (enabled) PrimaryBlue else Color.Gray
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = Color.White,
        modifier = Modifier
            .size(64.dp)
            .scale(scale),
    ) {
        Box(contentAlignment = Alignment.Center) {
            AppIcon(icon = icon, tint = contentColor, size = 30.dp)
        }
    }
}
