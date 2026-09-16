package com.leoskyzwengmail.Internalrecordingaudio.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.leoskyzwengmail.Internalrecordingaudio.audio.AudioCaptureManager
import com.leoskyzwengmail.Internalrecordingaudio.player.RepeatMode
import com.leoskyzwengmail.Internalrecordingaudio.ui.components.AppIcon
import com.leoskyzwengmail.Internalrecordingaudio.ui.pages.AboutPage
import com.leoskyzwengmail.Internalrecordingaudio.ui.pages.FilesPage
import com.leoskyzwengmail.Internalrecordingaudio.ui.pages.RecordPage
import com.leoskyzwengmail.Internalrecordingaudio.ui.pages.SourcePage

private enum class Tab(val label: String, val icon: AppIcon) {
    RECORD("录音", AppIcon.Mic),
    SOURCE("来源", AppIcon.Apps),
    FILES("文件", AppIcon.AudioFile),
    ABOUT("关于", AppIcon.Info),
}

/**
 * 主界面：底部 4 个页面。
 * 页面切换只换内容，ViewModel 与录音核心都在 Activity 作用域，切页不会打断录音。
 */
@Composable
fun AppNav(
    viewModel: RecordingViewModel,
    onRequestProjection: () -> Unit,
    onRequestPermissions: () -> Unit,
    onOpenManageStorage: () -> Unit,
    onOpenOverlaySettings: () -> Unit,
    onLaunchImagePicker: () -> Unit,
    onShowToast: (String) -> Unit,
) {
    val settings by viewModel.settings.collectAsState()
    val state by viewModel.state.collectAsState()
    val durationMs by viewModel.durationMs.collectAsState()
    val levelFrame by viewModel.level.collectAsState()
    val apps by viewModel.apps.collectAsState()
    val groups by viewModel.groups.collectAsState()
    val converted by viewModel.converted.collectAsState()
    val converting by viewModel.converting.collectAsState()
    val convertProgress by viewModel.convertProgress.collectAsState()
    val playback by viewModel.playback.collectAsState()

    var tabIndex by rememberSaveable { mutableIntStateOf(0) }
    var floatRunning by remember { mutableStateOf(viewModel.isFloatRunning()) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { snackbarHostState.showSnackbar(it) }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                Tab.entries.forEachIndexed { index, tab ->
                    val selected = tabIndex == index
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            tabIndex = index
                            if (tab == Tab.RECORD) floatRunning = viewModel.isFloatRunning()
                        },
                        icon = {
                            AppIcon(
                                icon = tab.icon,
                                tint = if (selected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                size = 22.dp,
                            )
                        },
                        label = { Text(tab.label) },
                        colors = NavigationBarItemDefaults.colors(
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                }
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when (Tab.entries[tabIndex]) {
                Tab.RECORD -> RecordPage(
                    settings = settings,
                    state = state,
                    durationMs = durationMs,
                    levelFrame = levelFrame,
                    floatRunning = floatRunning,
                    onRequestProjection = onRequestProjection,
                    onPause = viewModel::pause,
                    onResume = viewModel::resume,
                    onStop = viewModel::stop,
                    onToggleFloat = {
                        if (viewModel.isFloatRunning()) viewModel.stopFloat() else viewModel.startFloat()
                        floatRunning = viewModel.isFloatRunning()
                    },
                    onOpenOverlaySettings = onOpenOverlaySettings,
                    onUpdateSettings = viewModel::updateSettings,
                )

                Tab.SOURCE -> SourcePage(
                    settings = settings,
                    apps = apps,
                    recording = state == AudioCaptureManager.STATE_RECORDING
                            || state == AudioCaptureManager.STATE_PAUSED,
                    onUpdateSettings = viewModel::updateSettings,
                )

                Tab.FILES -> FilesPage(
                    groups = groups,
                    converted = converted,
                    playback = playback,
                    converting = converting,
                    convertProgress = convertProgress,
                    onRefresh = { viewModel.refreshRecordings() },
                    onPlay = { file, list -> viewModel.play(file, list) },
                    onSeek = { ms -> viewModel.seek(ms) },
                    onStopPlay = { viewModel.stopPlay() },
                    onSkipPrev = { viewModel.skipPrev() },
                    onSkipNext = { viewModel.skipNext() },
                    onTogglePlayPause = { viewModel.togglePlayPause() },
                    onCycleRepeat = {
                        val mode = viewModel.cycleRepeat()
                        onShowToast("播放模式：${mode.label}")
                        mode
                    },
                    onRename = { file, name -> viewModel.renameRecording(file, name) },
                    onDelete = { file -> viewModel.deleteRecording(file) },
                    onPlaybackParams = { speed, pitch -> viewModel.setPlaybackParams(speed, pitch) },
                    onConvert = { req -> viewModel.convert(req) },
                    onCopyTo = { file, target -> viewModel.copyTo(file, target) },
                    onMoveTo = { file, target -> viewModel.moveTo(file, target) },
                    onPickCover = { file ->
                        viewModel.requestCover(file)
                        onLaunchImagePicker()
                    },
                )

                Tab.ABOUT -> AboutPage(
                    onRequestPermissions = onRequestPermissions,
                    onOpenManageStorage = onOpenManageStorage,
                    onOpenOverlaySettings = onOpenOverlaySettings,
                    onResetDefaults = viewModel::resetDefaults,
                )
            }
        }
    }
}
