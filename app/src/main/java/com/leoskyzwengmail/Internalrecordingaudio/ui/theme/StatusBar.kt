package com.leoskyzwengmail.Internalrecordingaudio.ui.theme

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * 状态栏配色。
 *
 * 之前状态栏被设成纯白、图标又是深色，等于「白底白字」——上面的时间、电量全看不见。
 * 这里统一设成淡紫色（跟随动态取色时用 primaryContainer），并把图标强制改成深色。
 */
@Composable
fun ConfigureStatusBar(
    color: Color,
    darkIcons: Boolean = true,
) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            window.statusBarColor = color.toArgb()
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = darkIcons
        }
    }
}
