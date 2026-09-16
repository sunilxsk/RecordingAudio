package com.leoskyzwengmail.Internalrecordingaudio.ui.pages

import java.util.Locale

/**
 * 页面公用格式化。
 *
 * 之前 RecordPage 与 FilesPage 各写了一份同名的 formatDuration，
 * 同包下重名顶层函数会直接编译失败（Conflicting overloads / redeclaration）。
 * 统一放在这里，两个页面都不需要 import（同包可见）。
 */
internal fun formatDuration(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val min = totalSec / 60
    val sec = totalSec % 60
    return String.format(Locale.getDefault(), "%02d:%02d", min, sec)
}

/** 把 0~1 的进度渲染成百分比文本 */
internal fun progressText(value: Float): String =
    "${(value * 100f).toInt().coerceIn(0, 100)}%"
