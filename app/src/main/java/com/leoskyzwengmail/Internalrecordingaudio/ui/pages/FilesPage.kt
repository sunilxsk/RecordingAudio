package com.leoskyzwengmail.Internalrecordingaudio.ui.pages

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.leoskyzwengmail.Internalrecordingaudio.audio.AudioConverter
import com.leoskyzwengmail.Internalrecordingaudio.audio.ConvertOp
import com.leoskyzwengmail.Internalrecordingaudio.audio.ConvertRequest
import com.leoskyzwengmail.Internalrecordingaudio.audio.QualityKind
import com.leoskyzwengmail.Internalrecordingaudio.player.Media3Player
import com.leoskyzwengmail.Internalrecordingaudio.player.RepeatMode
import com.leoskyzwengmail.Internalrecordingaudio.ui.components.AppIcon
import com.leoskyzwengmail.Internalrecordingaudio.ui.components.SectionCard
import com.leoskyzwengmail.Internalrecordingaudio.ui.components.SliderRow
import com.leoskyzwengmail.Internalrecordingaudio.util.AppFileHelper
import java.io.File
import java.util.Locale

data class RecordingGroup(
    val title: String,
    val subtitle: String,
    val files: List<File>,
)

/** 文件操作的目标目录 */
enum class FileTarget(val label: String) {
    INTERNAL("内部目录"),
    EXTERNAL("外部目录"),
    CONVERT("转换目录"),
}

/** 第 3 页：播放控制 + 录音文件（外部 / 内部 / 转换后）。 */
@Composable
fun FilesPage(
    groups: List<RecordingGroup>,
    converted: List<File>,
    playback: Media3Player.PlaybackState,
    converting: Boolean,
    convertProgress: Float,
    onRefresh: () -> Unit,
    onPlay: (File, List<File>) -> Unit,
    onSeek: (Long) -> Unit,
    onStopPlay: () -> Unit,
    onSkipPrev: () -> Unit,
    onSkipNext: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onCycleRepeat: () -> RepeatMode,
    onRename: (File, String) -> Unit,
    onDelete: (File) -> Unit,
    onPlaybackParams: (Float, Float) -> Unit,
    onConvert: (ConvertRequest) -> Unit,
    onCopyTo: (File, FileTarget) -> Unit,
    onMoveTo: (File, FileTarget) -> Unit,
    onPickCover: (File) -> Unit,
) {
    var renameTarget by remember { mutableStateOf<File?>(null) }
    var deleteTarget by remember { mutableStateOf<File?>(null) }
    var menuTarget by remember { mutableStateOf<File?>(null) }
    /** 文件名搜索（同时作用于外部 / 内部 / 转换三个列表） */
    var query by remember { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
        contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
    ) {
        item {
            PlaybackControlCard(
                playback = playback,
                converting = converting,
                convertProgress = convertProgress,
                onSeek = onSeek,
                onStopPlay = onStopPlay,
                onSkipPrev = onSkipPrev,
                onSkipNext = onSkipNext,
                onTogglePlayPause = onTogglePlayPause,
                onCycleRepeat = onCycleRepeat,
                onPlaybackParams = onPlaybackParams,
                onRefresh = onRefresh,
            )
        }

        playback.error?.let { err ->
            item {
                Text(
                    err,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
        }

        item {
            SearchField(query = query, onQueryChange = { query = it })
        }

        groups.forEach { group ->
            item {
                val filtered = remember(group.files, query) { filterFiles(group.files, query) }
                SectionCard(
                    title = "${group.title}（${filtered.size}）",
                    icon = AppIcon.Folder,
                    subtitle = group.subtitle,
                ) {
                    FileListBox(
                        files = filtered,
                        emptyText = if (query.isBlank()) "暂无录音文件" else "没有匹配「$query」的文件",
                        playback = playback,
                        onTogglePlay = { file -> onPlay(file, filtered) },
                        onSeek = onSeek,
                        onRename = { renameTarget = it },
                        onDelete = { deleteTarget = it },
                        onLongClick = { menuTarget = it },
                    )
                }
            }
        }

        item {
            val filtered = remember(converted, query) { filterFiles(converted, query) }
            SectionCard(
                title = "转换后的文件（${filtered.size}）",
                icon = AppIcon.GraphicEq,
                subtitle = "输出到 ${AudioConverter.CONVERT_DIR} 目录（无全盘权限时降级到应用私有目录）。长按录音文件可转换。",
            ) {
                FileListBox(
                    files = filtered,
                    emptyText = if (query.isBlank()) "暂无转换文件" else "没有匹配「$query」的文件",
                    playback = playback,
                    onTogglePlay = { file -> onPlay(file, filtered) },
                    onSeek = onSeek,
                    onRename = { renameTarget = it },
                    onDelete = { deleteTarget = it },
                    onLongClick = { menuTarget = it },
                )
            }
        }
    }

    renameTarget?.let { file ->
        RenameDialog(
            file = file,
            onDismiss = { renameTarget = null },
            onConfirm = { newName ->
                onRename(file, newName)
                renameTarget = null
            },
        )
    }

    deleteTarget?.let { file ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除录音") },
            text = { Text("确定删除「${file.name}」？该操作不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(file)
                    deleteTarget = null
                }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            },
        )
    }

    menuTarget?.let { file ->
        FileActionDialog(
            file = file,
            onDismiss = { menuTarget = null },
            onConvert = { req ->
                menuTarget = null
                onConvert(req)
            },
            onCopyTo = { target ->
                menuTarget = null
                onCopyTo(file, target)
            },
            onMoveTo = { target ->
                menuTarget = null
                onMoveTo(file, target)
            },
            onPickCover = {
                menuTarget = null
                onPickCover(file)
            },
        )
    }
}

// ---------------- 顶部播放控制 ----------------

@Composable
private fun PlaybackControlCard(
    playback: Media3Player.PlaybackState,
    converting: Boolean,
    convertProgress: Float,
    onSeek: (Long) -> Unit,
    onStopPlay: () -> Unit,
    onSkipPrev: () -> Unit,
    onSkipNext: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onCycleRepeat: () -> RepeatMode,
    onPlaybackParams: (Float, Float) -> Unit,
    onRefresh: () -> Unit,
) {
    SectionCard(
        title = "播放与转换",
        icon = AppIcon.Tune,
        subtitle = "音调 / 音速实时作用于正在播放的音频（含通知栏播放）。",
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CoverThumb(playback = playback)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    playback.title ?: "未在播放",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    playback.path?.let { File(it).parentFile?.name } ?: "—",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        SliderRow(
            label = "音调",
            value = playback.pitch,
            valueRange = 0.5f..2.0f,
            steps = 29,
            valueText = String.format(Locale.getDefault(), "%.2fx", playback.pitch),
            onValueChange = { onPlaybackParams(playback.speed, it) },
        )
        SliderRow(
            label = "音速",
            value = playback.speed,
            valueRange = 0.5f..2.0f,
            steps = 29,
            valueText = String.format(Locale.getDefault(), "%.2fx", playback.speed),
            onValueChange = { onPlaybackParams(it, playback.pitch) },
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { onPlaybackParams(1f, 1f) }) { Text("复位") }
            Spacer(modifier = Modifier.weight(1f))
            if (playback.path != null) {
                TextButton(onClick = onStopPlay) { Text("停止") }
            }
            TextButton(onClick = onRefresh, enabled = !converting) { Text("刷新") }
        }

        if (converting) {
            Spacer(modifier = Modifier.height(8.dp))
            if (convertProgress < 0f) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(
                    progress = { convertProgress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Row(
                modifier = Modifier.padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    if (convertProgress < 0f) {
                        "正在转换…"
                    } else {
                        "正在转换… ${progressText(convertProgress)}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // ---- 播放进度条 ----
        if (playback.path != null && playback.durationMs > 0) {
            Spacer(modifier = Modifier.height(8.dp))
            Slider(
                value = playback.positionMs.coerceIn(0L, playback.durationMs).toFloat(),
                onValueChange = { onSeek(it.toLong()) },
                valueRange = 0f..playback.durationMs.toFloat(),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    formatDuration(playback.positionMs),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    formatDuration(playback.durationMs),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // ---- 进度条下方：上一曲 / 下一曲 / 循环模式 ----
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconSquareButton(icon = AppIcon.Prev, desc = "上一曲", enabled = playback.path != null) {
                onSkipPrev()
            }
            Spacer(modifier = Modifier.width(16.dp))
            // 播放 / 暂停：放在上一曲与下一曲中间
            Surface(
                onClick = onTogglePlayPause,
                enabled = playback.path != null,
                shape = CircleShape,
                color = if (playback.path != null) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                modifier = Modifier.size(56.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    AppIcon(
                        icon = if (playback.playing) AppIcon.Pause else AppIcon.Play,
                        tint = if (playback.path != null) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        size = 28.dp,
                    )
                }
            }
            Spacer(modifier = Modifier.width(16.dp))
            IconSquareButton(icon = AppIcon.Next, desc = "下一曲", enabled = playback.path != null) {
                onSkipNext()
            }
            Spacer(modifier = Modifier.width(20.dp))
            RepeatModeButton(mode = playback.repeatMode) { onCycleRepeat() }
        }
        if (playback.queue.size > 1) {
            Text(
                "本目录共 ${playback.queue.size} 首 · 第 ${(playback.queueIndex + 1).coerceAtLeast(1)} 首",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun CoverThumb(playback: Media3Player.PlaybackState) {
    val bmp = playback.artwork
    if (bmp != null) {
        androidx.compose.foundation.Image(
            bitmap = bmp,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(12.dp)),
        )
    } else {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(56.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                AppIcon(
                    icon = AppIcon.AudioFile,
                    tint = MaterialTheme.colorScheme.primary,
                    size = 28.dp,
                )
            }
        }
    }
}

@Composable
private fun IconSquareButton(
    icon: AppIcon,
    desc: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = if (enabled) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        modifier = Modifier.size(44.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            AppIcon(
                icon = icon,
                tint = if (enabled) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                size = 22.dp,
            )
        }
    }
}

@Composable
private fun RepeatModeButton(mode: RepeatMode, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.size(44.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            AppIcon(
                icon = when (mode) {
                    RepeatMode.ALL -> AppIcon.Repeat
                    RepeatMode.SHUFFLE -> AppIcon.Shuffle
                    RepeatMode.OFF -> AppIcon.Repeat
                },
                tint = if (mode == RepeatMode.OFF) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.primary
                },
                size = 22.dp,
            )
        }
    }
}

// ---------------- 文件行 ----------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecordingRow(
    file: File,
    playback: Media3Player.PlaybackState,
    onTogglePlay: () -> Unit,
    onSeek: (Long) -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onLongClick: () -> Unit,
) {
    val isCurrent = playback.path == file.absolutePath
    val isPlaying = isCurrent && playback.playing

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (isCurrent) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
    ) {
        Column(
            modifier = Modifier
                .combinedClickable(onClick = onTogglePlay, onLongClick = onLongClick)
                .padding(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    onClick = onTogglePlay,
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(36.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (isCurrent && playback.durationMs <= 0 && isPlaying) {
                            CircularProgressIndicator(
                                color = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            AppIcon(
                                icon = if (isPlaying) AppIcon.Pause else AppIcon.Play,
                                tint = MaterialTheme.colorScheme.onPrimary,
                                size = 18.dp,
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        file.name,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${AppFileHelper.formatFileSize(file.length())} · " +
                            AppFileHelper.extensionOf(file).removePrefix(".").uppercase(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onRename) {
                    AppIcon(
                        icon = AppIcon.Edit,
                        tint = MaterialTheme.colorScheme.primary,
                        size = 18.dp,
                    )
                }
                TextButton(onClick = onDelete) {
                    AppIcon(
                        icon = AppIcon.Delete,
                        tint = MaterialTheme.colorScheme.error,
                        size = 18.dp,
                    )
                }
            }

            if (isCurrent && playback.durationMs > 0) {
                Spacer(modifier = Modifier.height(6.dp))
                val dur = playback.durationMs.coerceAtLeast(1L)
                Slider(
                    value = playback.positionMs.coerceIn(0L, dur).toFloat(),
                    onValueChange = { onSeek(it.toLong()) },
                    valueRange = 0f..dur.toFloat(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        formatDuration(playback.positionMs),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        formatDuration(dur),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// ---------------- 长按菜单 ----------------

private enum class MenuSection(val title: String) {
    CONVERT("格式转换"),
    FILE_OPS("移动 / 复制"),
    COVER("封面"),
}

@Composable
private fun FileActionDialog(
    file: File,
    onDismiss: () -> Unit,
    onConvert: (ConvertRequest) -> Unit,
    onCopyTo: (FileTarget) -> Unit,
    onMoveTo: (FileTarget) -> Unit,
    onPickCover: () -> Unit,
) {
    var pendingOp by remember { mutableStateOf<ConvertOp?>(null) }
    var pendingTarget by remember { mutableStateOf<Pair<FileTarget, Boolean>?>(null) }
    // second = true 表示移动，false 表示复制

    if (pendingOp == null && pendingTarget == null) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = {
                Text(
                    file.name,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            text = {
                val maxContentHeight = (LocalConfiguration.current.screenHeightDp * 0.6f).dp
                Column(
                    modifier = Modifier
                        .heightIn(max = maxContentHeight)
                        .verticalScroll(rememberScrollState()),
                ) {
                    MenuLabel(MenuSection.CONVERT.title)
                    ConvertOp.values().forEach { op ->
                        MenuItem(text = op.label) { pendingOp = op }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                    MenuLabel(MenuSection.FILE_OPS.title)
                    FileTarget.values().forEach { target ->
                        MenuItem(text = "移动 / 复制到${target.label}") {
                            pendingTarget = target to true
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                    MenuLabel(MenuSection.COVER.title)
                    MenuItem(
                        text = if (AudioConverter.coverSupported(file)) {
                            "写入封面图"
                        } else {
                            "写入封面图（该格式不支持）"
                        },
                        enabled = AudioConverter.coverSupported(file),
                    ) { onPickCover() }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        )
        return
    }

    // 第二层：选择复制还是移动
    pendingTarget?.let { (target, _) ->
        AlertDialog(
            onDismissRequest = { pendingTarget = null },
            title = { Text("到${target.label}") },
            text = { Text("选择操作方式：复制会保留原文件，移动会把原文件移走。") },
            confirmButton = {
                TextButton(onClick = {
                    pendingTarget = null
                    onMoveTo(target)
                }) { Text("移动") }
            },
            dismissButton = {
                TextButton(onClick = {
                    pendingTarget = null
                    onCopyTo(target)
                }) { Text("复制") }
            },
        )
        return
    }

    val op = pendingOp ?: return

    if (op.quality == QualityKind.NONE) {
        LaunchedEffect(op) {
            onConvert(ConvertRequest(file, op))
        }
        return
    }

    when (op.quality) {
        QualityKind.BITRATE, QualityKind.FLAC_LEVEL, QualityKind.VORBIS_Q -> {
            QualityDialog(
                op = op,
                onDismiss = { pendingOp = null },
                onConfirm = { q -> onConvert(ConvertRequest(file, op, quality = q)) },
            )
        }

        QualityKind.PITCH_TEMPO -> {
            PitchTempoDialog(
                onDismiss = { pendingOp = null },
                onConfirm = { pitch, tempo ->
                    onConvert(ConvertRequest(file, op, pitch = pitch, tempo = tempo))
                },
            )
        }

        QualityKind.VOLUME_DB -> {
            VolumeDialog(
                onDismiss = { pendingOp = null },
                onConfirm = { db -> onConvert(ConvertRequest(file, op, gainDb = db)) },
            )
        }

        QualityKind.NONE -> Unit
    }
}

@Composable
private fun MenuLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}

@Composable
private fun MenuItem(
    text: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text,
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

private data class QualitySpec(
    val label: String,
    val min: Float,
    val max: Float,
    val def: Float,
)

private fun qualitySpec(kind: QualityKind): QualitySpec = when (kind) {
    QualityKind.BITRATE -> QualitySpec("码率 (kbps)", 64f, 320f, 192f)
    QualityKind.FLAC_LEVEL -> QualitySpec("压缩等级（0 最快 · 12 最小）", 0f, 12f, 5f)
    QualityKind.VORBIS_Q -> QualitySpec("质量（0 最低 · 10 最高）", 0f, 10f, 5f)
    else -> QualitySpec("参数", 0f, 10f, 5f)
}

@Composable
private fun QualityDialog(
    op: ConvertOp,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    val spec = remember(op) { qualitySpec(op.quality) }
    var value by remember(op) { mutableStateOf(spec.def) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(op.label) },
        text = {
            SliderRow(
                label = spec.label,
                value = value,
                valueRange = spec.min..spec.max,
                steps = ((spec.max - spec.min).toInt()).coerceAtLeast(1) - 1,
                valueText = "${value.toInt()}",
                onValueChange = { value = it },
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(value.toInt()) },
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.primary,
                ),
            ) { Text("开始转换") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun PitchTempoDialog(
    onDismiss: () -> Unit,
    onConfirm: (Float, Float) -> Unit,
) {
    var pitch by remember { mutableStateOf(1f) }
    var tempo by remember { mutableStateOf(1f) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("音调音速改变") },
        text = {
            val maxContentHeight = (LocalConfiguration.current.screenHeightDp * 0.6f).dp
            Column(
                modifier = Modifier
                    .heightIn(max = maxContentHeight)
                    .verticalScroll(rememberScrollState()),
            ) {
                SliderRow(
                    label = "音调",
                    value = pitch,
                    valueRange = 0.5f..2.0f,
                    steps = 29,
                    valueText = String.format(Locale.getDefault(), "%.2fx", pitch),
                    onValueChange = { pitch = it },
                )
                SliderRow(
                    label = "音速",
                    value = tempo,
                    valueRange = 0.5f..2.0f,
                    steps = 29,
                    valueText = String.format(Locale.getDefault(), "%.2fx", tempo),
                    onValueChange = { tempo = it },
                )
                Text(
                    "音调改变音高，音速改变快慢；两者都为 1.00x 即原样。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(pitch, tempo) },
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.primary,
                ),
            ) { Text("开始转换") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun VolumeDialog(
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    var db by remember { mutableStateOf(6f) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("增大音量") },
        text = {
            val maxContentHeight = (LocalConfiguration.current.screenHeightDp * 0.6f).dp
            Column(
                modifier = Modifier
                    .heightIn(max = maxContentHeight)
                    .verticalScroll(rememberScrollState()),
            ) {
                SliderRow(
                    label = "增益",
                    value = db,
                    valueRange = 0f..30f,
                    steps = 29,
                    valueText = "+${db.toInt()} dB",
                    onValueChange = { db = it },
                )
                Text(
                    "超过约 +12 dB 可能出现削波失真，建议先试听。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(db.toInt()) },
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.primary,
                ),
            ) { Text("开始转换") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

// ---------------- 重命名 ----------------

/** 重命名对话框：输入框自动排除后缀，只让用户改主文件名 */
@Composable
private fun RenameDialog(
    file: File,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember(file) { mutableStateOf(AppFileHelper.baseName(file)) }
    val ext = remember(file) { AppFileHelper.extensionOf(file) }
    val error = text.isBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名") },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    label = { Text("文件名") },
                    suffix = { Text(ext) },
                    isError = error,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    "只需填写主文件名，扩展名 $ext 会自动保留，无需重复输入。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(text) },
                enabled = !error,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.primary,
                ),
            ) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 按文件名过滤（忽略大小写） */
private fun filterFiles(files: List<File>, query: String): List<File> {
    if (query.isBlank()) return files
    val q = query.trim()
    return files.filter { it.name.contains(q, ignoreCase = true) }
}

/**
 * 限高的文件列表：超过高度就在内部滚动，不再把整个页面撑长。
 */
@Composable
private fun FileListBox(
    files: List<File>,
    emptyText: String,
    playback: Media3Player.PlaybackState,
    onTogglePlay: (File) -> Unit,
    onSeek: (Long) -> Unit,
    onRename: (File) -> Unit,
    onDelete: (File) -> Unit,
    onLongClick: (File) -> Unit,
) {
    if (files.isEmpty()) {
        Text(
            emptyText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    val maxHeight = maxOf(200f, LocalConfiguration.current.screenHeightDp * 0.40f).dp
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = maxHeight)
            .verticalScroll(rememberScrollState()),
    ) {
        files.forEach { file ->
            RecordingRow(
                file = file,
                playback = playback,
                onTogglePlay = { onTogglePlay(file) },
                onSeek = onSeek,
                onRename = { onRename(file) },
                onDelete = { onDelete(file) },
                onLongClick = { onLongClick(file) },
            )
        }
    }
}

@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        placeholder = { Text("搜索文件名") },
        leadingIcon = {
            AppIcon(
                icon = AppIcon.Search,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                size = 18.dp,
            )
        },
        trailingIcon = {
            if (query.isNotEmpty()) {
                TextButton(onClick = { onQueryChange("") }) { Text("清空") }
            }
        },
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}
