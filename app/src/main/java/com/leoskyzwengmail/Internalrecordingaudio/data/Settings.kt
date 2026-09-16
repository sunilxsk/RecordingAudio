package com.leoskyzwengmail.Internalrecordingaudio.data

import com.leoskyzwengmail.Internalrecordingaudio.audio.RecordingConfig

/**
 * UI 层使用的不可变设置模型。
 * 与 Java 侧的 [RecordingConfig] 一一对应，
 * 任何改动都会同时写入 SharedPreferences，供悬浮球服务读取。
 */
data class Settings(
    val sampleRate: Int = 44100,
    val bitDepth: Int = 16,
    val bitRate: Int = 128000,
    val format: String = RecordingConfig.FORMAT_AAC,
    val global: Boolean = true,
    val usage: Int = 0,
    val selectedUids: Set<Int> = emptySet(),

    val silenceMode: Int = RecordingConfig.SILENCE_OFF,
    val silenceThresholdDb: Float = -55f,
    val silenceDurationSec: Int = 2,

    val autoStopMode: Int = RecordingConfig.AUTO_STOP_OFF,
    val autoStopCustomMin: Int = 10,

    val micEnabled: Boolean = false,
    val micGain: Float = 1.0f,
    val internalGain: Float = 1.0f,
)

fun Settings.toConfig(): RecordingConfig = RecordingConfig().apply {
    sampleRate = this@toConfig.sampleRate
    bitDepth = this@toConfig.bitDepth
    bitRate = this@toConfig.bitRate
    channelCount = 2
    format = this@toConfig.format
    global = this@toConfig.global
    usage = this@toConfig.usage
    targetUids = this@toConfig.selectedUids.toList()

    silenceMode = this@toConfig.silenceMode
    silenceThresholdDb = this@toConfig.silenceThresholdDb
    silenceDurationSec = this@toConfig.silenceDurationSec

    autoStopMode = this@toConfig.autoStopMode
    autoStopCustomMin = this@toConfig.autoStopCustomMin

    micEnabled = this@toConfig.micEnabled
    micGain = this@toConfig.micGain
    internalGain = this@toConfig.internalGain
}

fun RecordingConfig.toSettings(): Settings = Settings(
    sampleRate = sampleRate,
    bitDepth = bitDepth,
    bitRate = bitRate,
    format = format,
    global = global,
    usage = usage,
    selectedUids = targetUids.toSet(),
    silenceMode = silenceMode,
    silenceThresholdDb = silenceThresholdDb,
    silenceDurationSec = silenceDurationSec,
    autoStopMode = autoStopMode,
    autoStopCustomMin = autoStopCustomMin,
    micEnabled = micEnabled,
    micGain = micGain,
    internalGain = internalGain,
)

val FORMAT_OPTIONS = listOf(
    RecordingConfig.FORMAT_AAC to "AAC",
    RecordingConfig.FORMAT_MP3 to "MP3",
    RecordingConfig.FORMAT_OPUS to "Opus",
    RecordingConfig.FORMAT_FLAC to "FLAC",
    RecordingConfig.FORMAT_WAV to "WAV",
    RecordingConfig.FORMAT_PCM to "PCM",
)

val SILENCE_MODE_OPTIONS = listOf(
    RecordingConfig.SILENCE_OFF to "关闭",
    RecordingConfig.SILENCE_STOP to "静音自动停止",
    RecordingConfig.SILENCE_TRIM to "剪掉静音段",
)

val AUTO_STOP_OPTIONS = listOf(
    RecordingConfig.AUTO_STOP_OFF to "关闭",
    RecordingConfig.AUTO_STOP_3 to "3 分钟",
    RecordingConfig.AUTO_STOP_5 to "5 分钟",
    RecordingConfig.AUTO_STOP_15 to "15 分钟",
    RecordingConfig.AUTO_STOP_CUSTOM to "自定义",
)

fun formatName(value: String): String = FORMAT_OPTIONS.firstOrNull { it.first == value }?.second ?: value
