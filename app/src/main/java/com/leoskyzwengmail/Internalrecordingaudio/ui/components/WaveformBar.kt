package com.leoskyzwengmail.Internalrecordingaudio.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.pow

/**
 * 实时音量波形条（对称柱状）。
 *
 * - 柱高 = 该帧 RMS 归一化幅度（0~1），与静音检测同源
 * - 顶部细线 = 峰值包络，能看出瞬态
 * - 上下两条虚线 = 静音阈值位置，柱体低于它即判定为静音
 *
 * @param bars      波形柱（最旧 → 最新）
 * @param peaks     峰值包络
 * @param thresholdDb 静音阈值（dBFS），换算成归一化幅度后画阈值线
 */
@Composable
fun WaveformBar(
    bars: List<Float>,
    peaks: List<Float> = emptyList(),
    thresholdDb: Float = -55f,
    active: Boolean = true,
    silent: Boolean = false,
    barColor: Color = Color.White,
    peakColor: Color = Color.White,
    thresholdColor: Color = Color.White,
    barWidth: Dp = 3.dp,
    gap: Dp = 1.5.dp,
    modifier: Modifier = Modifier,
) {
    val waveBrush = Brush.verticalGradient(
        listOf(barColor.copy(alpha = 1f), barColor.copy(alpha = 0.55f)),
    )

    Canvas(
        modifier = modifier
            .fillMaxWidth(),
    ) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas

        val count = bars.size
        if (count == 0) return@Canvas

        val slot = w / count
        val bw = barWidth.toPx().coerceAtMost(slot - 0.5f).coerceAtLeast(1f)
        val centerY = h / 2f
        val maxHalf = h / 2f - 2f

        // ---- 阈值线 ----
        val threshold = 10f.pow(thresholdDb / 20f).coerceIn(0f, 1f)
        val thOffset = threshold * maxHalf
        for (sign in intArrayOf(-1, 1)) {
            drawLine(
                color = thresholdColor.copy(alpha = 0.35f),
                start = Offset(0f, centerY + sign * thOffset),
                end = Offset(w, centerY + sign * thOffset),
                strokeWidth = 1f,
            )
        }

        // ---- 中轴 ----
        drawLine(
            color = barColor.copy(alpha = 0.18f),
            start = Offset(0f, centerY),
            end = Offset(w, centerY),
            strokeWidth = 1f,
        )

        val alpha = if (active) 1f else 0.45f
        val tint = if (silent) barColor.copy(alpha = 0.45f * alpha) else barColor

        for (i in 0 until count) {
            val v = bars[i].coerceIn(0f, 1f)
            if (v <= 0.0005f) continue
            val half = (v * maxHalf).coerceAtLeast(1f)
            val x = i * slot + (slot - bw) / 2f

            drawRoundRect(
                brush = waveBrush,
                topLeft = Offset(x, centerY - half),
                size = Size(bw, half * 2f),
                cornerRadius = CornerRadius(bw / 2f, bw / 2f),
                alpha = alpha,
            )

            // 峰值包络
            if (peaks.size == count) {
                val p = (peaks[i].coerceIn(0f, 1f) * maxHalf).coerceAtLeast(half)
                if (p > half + 0.5f) {
                    drawLine(
                        color = peakColor.copy(alpha = 0.6f * alpha),
                        start = Offset(x, centerY - p),
                        end = Offset(x + bw, centerY - p),
                        strokeWidth = 1f,
                    )
                }
            }
        }
    }
}

/** 悬浮窗/简易场景用的矩形版本（无 Compose 依赖时由 Java 自绘） */
object WaveformMath {
    /** dBFS → 归一化幅度 0~1 */
    fun dbToAmp(db: Float): Float {
        if (db <= -120f) return 0f
        return 10f.pow(db / 20f).coerceIn(0f, 1f)
    }
}
