package com.leoskyzwengmail.Internalrecordingaudio.ui.pages

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.leoskyzwengmail.Internalrecordingaudio.audio.AudioCaptureManager
import com.leoskyzwengmail.Internalrecordingaudio.audio.RecordingConfig
import com.leoskyzwengmail.Internalrecordingaudio.data.Settings
import com.leoskyzwengmail.Internalrecordingaudio.ui.LevelFrame
import com.leoskyzwengmail.Internalrecordingaudio.ui.components.AppIcon
import com.leoskyzwengmail.Internalrecordingaudio.ui.components.AutoStopSection
import com.leoskyzwengmail.Internalrecordingaudio.ui.components.CircleActionButton
import com.leoskyzwengmail.Internalrecordingaudio.ui.components.FloatSection
import com.leoskyzwengmail.Internalrecordingaudio.ui.components.FormatSection
import com.leoskyzwengmail.Internalrecordingaudio.ui.components.MicSection
import com.leoskyzwengmail.Internalrecordingaudio.ui.components.QualitySection
import com.leoskyzwengmail.Internalrecordingaudio.ui.components.SilenceSection
import com.leoskyzwengmail.Internalrecordingaudio.ui.components.WaveformBar
import com.leoskyzwengmail.Internalrecordingaudio.ui.theme.EncodingPurple
import com.leoskyzwengmail.Internalrecordingaudio.ui.theme.PauseAmber
import com.leoskyzwengmail.Internalrecordingaudio.ui.theme.PrimaryBlue
import com.leoskyzwengmail.Internalrecordingaudio.ui.theme.RecordRed
import java.util.Locale

/**
 * 第 1 页：录音。
 * 保留全部录音相关功能；权限、录制来源、录音列表、恢复默认已分别移到第 2/3/4 页。
 */
@Composable
fun RecordPage(
    settings: Settings,
    state: Int,
    durationMs: Long,
    levelFrame: LevelFrame,
    floatRunning: Boolean,
    onRequestProjection: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onToggleFloat: () -> Unit,
    onOpenOverlaySettings: () -> Unit,
    onUpdateSettings: (Settings) -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item {
            StatusCard(
                state = state,
                durationMs = durationMs,
                settings = settings,
                levelFrame = levelFrame,
                onRequestProjection = onRequestProjection,
                onPause = onPause,
                onResume = onResume,
                onStop = onStop,
            )
        }
        item { FormatSection(settings, onUpdateSettings) }
        item { QualitySection(settings, onUpdateSettings) }
        item { SilenceSection(settings, onUpdateSettings) }
        item { AutoStopSection(settings, onUpdateSettings) }
        item { MicSection(settings, onUpdateSettings) }
        item {
            FloatSection(
                isRunning = floatRunning,
                onToggleFloat = onToggleFloat,
                onOpenOverlaySettings = onOpenOverlaySettings,
            )
        }
    }
}

@Composable
private fun StatusCard(
    state: Int,
    durationMs: Long,
    settings: Settings,
    levelFrame: LevelFrame,
    onRequestProjection: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
) {
    val isBusy = state == AudioCaptureManager.STATE_RECORDING
            || state == AudioCaptureManager.STATE_PAUSED
            || state == AudioCaptureManager.STATE_ENCODING

    val (bgStart, bgEnd) = when (state) {
        AudioCaptureManager.STATE_RECORDING -> RecordRed to RecordRed.copy(alpha = 0.72f)
        AudioCaptureManager.STATE_PAUSED -> PauseAmber to PauseAmber.copy(alpha = 0.72f)
        AudioCaptureManager.STATE_ENCODING -> EncodingPurple to EncodingPurple.copy(alpha = 0.72f)
        else -> PrimaryBlue to PrimaryBlue.copy(alpha = 0.72f)
    }

    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(listOf(bgStart, bgEnd)),
                    shape = RoundedCornerShape(24.dp),
                )
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = formatDuration(durationMs),
                fontSize = 46.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stateText(state),
                color = Color.White.copy(alpha = 0.92f),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(modifier = Modifier.height(6.dp))

            val recording = state == AudioCaptureManager.STATE_RECORDING
            WaveformBar(
                bars = levelFrame.bars,
                peaks = levelFrame.peaks,
                thresholdDb = if (settings.silenceMode == RecordingConfig.SILENCE_OFF) {
                    -120f
                } else {
                    settings.silenceThresholdDb
                },
                active = recording,
                silent = levelFrame.silent,
                barColor = Color.White,
                peakColor = Color.White,
                thresholdColor = if (levelFrame.silent) Color(0xFFFFD54F) else Color.White,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .padding(horizontal = 4.dp),
            )

            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (recording) {
                        String.format(Locale.getDefault(), "%.0f dBFS", levelFrame.dbFs)
                    } else {
                        "— dBFS"
                    },
                    color = Color.White.copy(alpha = 0.9f),
                    style = MaterialTheme.typography.labelLarge,
                )
                Spacer(modifier = Modifier.width(8.dp))
                if (settings.silenceMode != RecordingConfig.SILENCE_OFF) {
                    Text(
                        text = when {
                            state == AudioCaptureManager.STATE_PAUSED -> "静音检测：已暂停"
                            !recording -> "静音检测：待机"
                            levelFrame.silent -> "静音检测：命中"
                            else -> "静音检测：有声"
                        },
                        color = if (levelFrame.silent) Color(0xFFFFD54F)
                        else Color.White.copy(alpha = 0.75f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "${settings.sampleRate} Hz · ${settings.bitDepth} bit · ${
                    if (settings.format == RecordingConfig.FORMAT_FLAC
                        || settings.format == RecordingConfig.FORMAT_WAV
                        || settings.format == RecordingConfig.FORMAT_PCM
                    ) "无损" else "${settings.bitRate / 1000} kbps"
                } · ${settings.format.uppercase(Locale.getDefault())}",
                color = Color.White.copy(alpha = 0.8f),
                style = MaterialTheme.typography.bodySmall,
            )

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                when (state) {
                    AudioCaptureManager.STATE_RECORDING ->
                        CircleActionButton(AppIcon.Pause, "暂停") { onPause() }

                    AudioCaptureManager.STATE_PAUSED ->
                        CircleActionButton(AppIcon.Play, "继续") { onResume() }

                    AudioCaptureManager.STATE_ENCODING -> Unit
                    else -> CircleActionButton(AppIcon.Mic, "开始") { onRequestProjection() }
                }

                if (state == AudioCaptureManager.STATE_ENCODING) {
                    CircularProgressIndicator(
                        color = Color.White,
                        modifier = Modifier.size(56.dp),
                    )
                } else {
                    CircleActionButton(
                        icon = AppIcon.Stop,
                        description = "停止并保存",
                        enabled = isBusy,
                    ) { onStop() }
                }
            }
        }
    }
}

private fun stateText(state: Int): String = when (state) {
    AudioCaptureManager.STATE_RECORDING -> "正在录音"
    AudioCaptureManager.STATE_PAUSED -> "已暂停"
    AudioCaptureManager.STATE_ENCODING -> "编码中…"
    else -> "待机"
}


