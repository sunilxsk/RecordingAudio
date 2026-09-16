package com.leoskyzwengmail.Internalrecordingaudio.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * 开屏动画（完全复刻原版）。
 *
 * 原始实现：白底 + 居中 "RecordingAudio" 粗体黑字，
 *   - 淡入 0→1，800ms，起始偏移 100ms
 *   - 淡出 1→0，600ms，起始偏移 600ms
 *   - 1400ms 后进入主界面
 * 这里用两段 alpha 相乘，还原两条动画并行叠加时的重叠效果。
 */
@Composable
fun SplashScreen(onFinished: () -> Unit) {
    // 0=未开始 1=淡入 2=淡出
    var phase by remember { mutableIntStateOf(0) }

    val alphaIn by animateFloatAsState(
        targetValue = if (phase >= 1) 1f else 0f,
        animationSpec = tween(durationMillis = 800),
        label = "splashIn",
    )
    val alphaOut by animateFloatAsState(
        targetValue = if (phase >= 2) 0f else 1f,
        animationSpec = tween(durationMillis = 600),
        label = "splashOut",
    )

    LaunchedEffect(Unit) {
        delay(100)
        phase = 1          // 100ms 起淡入
        delay(500)
        phase = 2          // 600ms 起淡出（与淡入后段重叠）
        delay(800)
        onFinished()       // 1400ms 进入主界面
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "RecordingAudio",
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Black,
            style = MaterialTheme.typography.headlineMedium.copy(
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
            ),
            modifier = Modifier.alpha(alphaIn * alphaOut),
        )
    }
}
