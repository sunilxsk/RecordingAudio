package com.leoskyzwengmail.Internalrecordingaudio.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.leoskyzwengmail.Internalrecordingaudio.data.Settings
import com.leoskyzwengmail.Internalrecordingaudio.ui.components.AppIcon
import com.leoskyzwengmail.Internalrecordingaudio.ui.components.SectionCard
import com.leoskyzwengmail.Internalrecordingaudio.ui.components.SourceSection
import com.leoskyzwengmail.Internalrecordingaudio.util.AppFileHelper

/** 第 2 页：录制来源配置。 */
@Composable
fun SourcePage(
    settings: Settings,
    apps: List<AppFileHelper.AppInfo>,
    recording: Boolean,
    onUpdateSettings: (Settings) -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item { SourceSection(settings, apps, listHeight = 560.dp, onUpdate = onUpdateSettings) }
        item {
            SectionCard(title = "说明", icon = AppIcon.Info) {
                Text(
                    if (recording) {
                        "正在录音中。音源类参数（全局内录 / 音频类型 / 指定应用）需要重新授权并重启录音才会生效，" +
                            "为避免 PCM 前后不一致，本次录音不会中途切换。"
                    } else {
                        "全局内录会采集设备播放的全部声音；关闭后可按音频类型或指定应用筛选。" +
                            "按 UID 过滤依赖系统 AudioPlaybackCapture，部分 ROM 可能忽略该配置。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
