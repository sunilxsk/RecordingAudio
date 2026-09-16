package com.leoskyzwengmail.Internalrecordingaudio.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 内置图标集（Canvas 自绘，零依赖）。
 *
 * 背景：androidx.compose.material:material-icons-core 只含「最常用」的一小部分图标，
 * 本工程需要的 Mic / Pause / Stop / Timer / GraphicEq 等都不在其中，
 * 引用会直接编译报错（Unresolved reference）；
 * 而 material-icons-extended 有 5000+ 图标、体积极大，不适合本工程。
 * 因此这里自绘一套线性图标，风格跟随 MD3 主题色。
 */
enum class AppIcon {
    Mic,
    Pause,
    Play,
    Stop,
    Tune,
    Timer,
    GraphicEq,
    AudioFile,
    Apps,
    Bubble,
    ArrowDropDown,
    Delete,
    Refresh,
    Info,
    Edit,
    Folder,
    Next,
    Prev,
    Repeat,
    Shuffle,
    Copy,
    Move,
    Photo,
    Search,
}

@Composable
fun AppIcon(
    icon: AppIcon,
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified,
    size: Dp = 24.dp,
) {
    val resolvedTint =
        if (tint == Color.Unspecified) MaterialTheme.colorScheme.primary else tint
    Canvas(modifier = modifier.size(size)) {
        drawIcon(icon, resolvedTint)
    }
}

private fun DrawScope.drawIcon(icon: AppIcon, color: Color) {
    val w = size.minDimension
    val sw = w * 0.085f
    val stroke = Stroke(width = sw, cap = StrokeCap.Round)

    fun p(x: Float, y: Float): Offset = Offset(w * x, w * y)

    when (icon) {
        AppIcon.Mic -> {
            drawRoundRect(
                color = color,
                topLeft = p(0.40f, 0.14f),
                size = Size(w * 0.20f, w * 0.46f),
                cornerRadius = CornerRadius(w * 0.10f, w * 0.10f),
                style = Fill,
            )
            drawArc(
                color = color,
                startAngle = 0f,
                sweepAngle = 180f,
                useCenter = false,
                topLeft = p(0.26f, 0.40f),
                size = Size(w * 0.48f, w * 0.44f),
                style = stroke,
            )
            drawLine(color, p(0.50f, 0.84f), p(0.50f, 0.96f), sw, StrokeCap.Round)
            drawLine(color, p(0.36f, 0.96f), p(0.64f, 0.96f), sw, StrokeCap.Round)
        }

        AppIcon.Pause -> {
            drawLine(color, p(0.36f, 0.26f), p(0.36f, 0.74f), sw, StrokeCap.Round)
            drawLine(color, p(0.64f, 0.26f), p(0.64f, 0.74f), sw, StrokeCap.Round)
        }

        AppIcon.Play -> {
            val path = Path().apply {
                moveTo(w * 0.32f, w * 0.26f)
                lineTo(w * 0.74f, w * 0.50f)
                lineTo(w * 0.32f, w * 0.74f)
                close()
            }
            drawPath(path, color, style = Fill)
        }

        AppIcon.Stop -> {
            drawRoundRect(
                color = color,
                topLeft = p(0.28f, 0.28f),
                size = Size(w * 0.44f, w * 0.44f),
                cornerRadius = CornerRadius(w * 0.06f, w * 0.06f),
                style = Fill,
            )
        }

        AppIcon.Tune -> {
            drawLine(color, p(0.16f, 0.32f), p(0.84f, 0.32f), sw, StrokeCap.Round)
            drawCircle(color, radius = w * 0.075f, center = p(0.62f, 0.32f), style = Fill)
            drawLine(color, p(0.16f, 0.50f), p(0.84f, 0.50f), sw, StrokeCap.Round)
            drawCircle(color, radius = w * 0.075f, center = p(0.36f, 0.50f), style = Fill)
            drawLine(color, p(0.16f, 0.68f), p(0.84f, 0.68f), sw, StrokeCap.Round)
            drawCircle(color, radius = w * 0.075f, center = p(0.64f, 0.68f), style = Fill)
        }

        AppIcon.Timer -> {
            drawCircle(
                color = color,
                radius = w * 0.32f,
                center = p(0.50f, 0.58f),
                style = stroke,
            )
            drawLine(color, p(0.50f, 0.58f), p(0.50f, 0.38f), sw, StrokeCap.Round)
            drawLine(color, p(0.50f, 0.58f), p(0.66f, 0.64f), sw, StrokeCap.Round)
            drawLine(color, p(0.40f, 0.14f), p(0.60f, 0.14f), sw, StrokeCap.Round)
            drawLine(color, p(0.50f, 0.14f), p(0.50f, 0.24f), sw, StrokeCap.Round)
        }

        AppIcon.GraphicEq -> {
            val bars = floatArrayOf(0.24f, 0.44f, 0.62f, 0.40f, 0.22f)
            val xs = floatArrayOf(0.20f, 0.35f, 0.50f, 0.65f, 0.80f)
            for (i in bars.indices) {
                val half = bars[i] / 2f
                drawLine(
                    color,
                    p(xs[i], 0.50f - half),
                    p(xs[i], 0.50f + half),
                    sw,
                    StrokeCap.Round,
                )
            }
        }

        AppIcon.AudioFile -> {
            drawRoundRect(
                color = color,
                topLeft = p(0.24f, 0.12f),
                size = Size(w * 0.52f, w * 0.76f),
                cornerRadius = CornerRadius(w * 0.06f, w * 0.06f),
                style = stroke,
            )
            drawLine(color, p(0.38f, 0.42f), p(0.38f, 0.58f), sw, StrokeCap.Round)
            drawLine(color, p(0.50f, 0.32f), p(0.50f, 0.68f), sw, StrokeCap.Round)
            drawLine(color, p(0.62f, 0.42f), p(0.62f, 0.58f), sw, StrokeCap.Round)
        }

        AppIcon.Apps -> {
            val pos = floatArrayOf(0.28f, 0.50f, 0.72f)
            for (x in pos) {
                for (y in pos) {
                    drawCircle(color, radius = w * 0.065f, center = p(x, y), style = Fill)
                }
            }
        }

        AppIcon.Bubble -> {
            drawCircle(color, radius = w * 0.19f, center = p(0.36f, 0.64f), style = stroke)
            drawCircle(color, radius = w * 0.13f, center = p(0.68f, 0.32f), style = stroke)
            drawCircle(color, radius = w * 0.06f, center = p(0.74f, 0.74f), style = Fill)
        }

        AppIcon.ArrowDropDown -> {
            val path = Path().apply {
                moveTo(w * 0.28f, w * 0.40f)
                lineTo(w * 0.72f, w * 0.40f)
                lineTo(w * 0.50f, w * 0.68f)
                close()
            }
            drawPath(path, color, style = Fill)
        }

        AppIcon.Delete -> {
            val body = Path().apply {
                moveTo(w * 0.30f, w * 0.34f)
                lineTo(w * 0.36f, w * 0.86f)
                lineTo(w * 0.64f, w * 0.86f)
                lineTo(w * 0.70f, w * 0.34f)
                close()
            }
            drawPath(body, color, style = stroke)
            drawLine(color, p(0.20f, 0.34f), p(0.80f, 0.34f), sw, StrokeCap.Round)
            drawLine(color, p(0.42f, 0.22f), p(0.58f, 0.22f), sw, StrokeCap.Round)
            drawLine(color, p(0.50f, 0.22f), p(0.50f, 0.34f), sw, StrokeCap.Round)
        }

        AppIcon.Refresh -> {
            drawArc(
                color = color,
                startAngle = 20f,
                sweepAngle = 300f,
                useCenter = false,
                topLeft = p(0.16f, 0.16f),
                size = Size(w * 0.68f, w * 0.68f),
                style = stroke,
            )
            val arrow = Path().apply {
                moveTo(w * 0.50f, w * 0.10f)
                lineTo(w * 0.70f, w * 0.16f)
                lineTo(w * 0.54f, w * 0.30f)
                close()
            }
            drawPath(arrow, color, style = Fill)
        }

        AppIcon.Info -> {
            drawCircle(color, w * 0.13f, p(0.5f, 0.5f), style = stroke)
            drawLine(color, p(0.50f, 0.44f), p(0.50f, 0.66f), sw, StrokeCap.Round)
            drawCircle(color, w * 0.06f, p(0.50f, 0.30f), style = Fill)
        }

        AppIcon.Edit -> {
            val tip = Path().apply {
                moveTo(w * 0.68f, w * 0.20f)
                lineTo(w * 0.80f, w * 0.32f)
                lineTo(w * 0.38f, w * 0.74f)
                lineTo(w * 0.26f, w * 0.74f)
                lineTo(w * 0.26f, w * 0.62f)
                close()
            }
            drawPath(tip, color, style = stroke)
            drawLine(color, p(0.22f, 0.82f), p(0.78f, 0.82f), sw, StrokeCap.Round)
        }

        AppIcon.Next -> {
            drawLine(color, p(0.28f, 0.26f), p(0.28f, 0.74f), sw, StrokeCap.Round)
            val tri = Path().apply {
                moveTo(w * 0.32f, w * 0.26f)
                lineTo(w * 0.74f, w * 0.50f)
                lineTo(w * 0.32f, w * 0.74f)
                close()
            }
            drawPath(tri, color, style = Fill)
        }

        AppIcon.Prev -> {
            drawLine(color, p(0.72f, 0.26f), p(0.72f, 0.74f), sw, StrokeCap.Round)
            val tri = Path().apply {
                moveTo(w * 0.68f, w * 0.26f)
                lineTo(w * 0.26f, w * 0.50f)
                lineTo(w * 0.68f, w * 0.74f)
                close()
            }
            drawPath(tri, color, style = Fill)
        }

        AppIcon.Repeat -> {
            drawRoundRect(
                color = color,
                topLeft = p(0.16f, 0.26f),
                size = Size(w * 0.68f, w * 0.48f),
                cornerRadius = CornerRadius(w * 0.08f, w * 0.08f),
                style = stroke,
            )
            val arrow = Path().apply {
                moveTo(w * 0.40f, w * 0.40f)
                lineTo(w * 0.60f, w * 0.40f)
                lineTo(w * 0.52f, w * 0.32f)
                moveTo(w * 0.60f, w * 0.40f)
                lineTo(w * 0.52f, w * 0.48f)
            }
            drawPath(arrow, color, style = stroke)
        }

        AppIcon.Shuffle -> {
            drawLine(color, p(0.20f, 0.32f), p(0.44f, 0.32f), sw, StrokeCap.Round)
            drawLine(color, p(0.56f, 0.32f), p(0.72f, 0.32f), sw, StrokeCap.Round)
            drawLine(color, p(0.20f, 0.68f), p(0.36f, 0.68f), sw, StrokeCap.Round)
            drawLine(color, p(0.48f, 0.68f), p(0.72f, 0.68f), sw, StrokeCap.Round)
            drawLine(color, p(0.44f, 0.32f), p(0.56f, 0.68f), sw, StrokeCap.Round)
            drawLine(color, p(0.36f, 0.68f), p(0.48f, 0.32f), sw, StrokeCap.Round)
            val head = Path().apply {
                moveTo(w * 0.72f, w * 0.24f)
                lineTo(w * 0.82f, w * 0.32f)
                lineTo(w * 0.72f, w * 0.40f)
                close()
            }
            drawPath(head, color, style = Fill)
        }

        AppIcon.Copy -> {
            drawRoundRect(
                color = color,
                topLeft = p(0.24f, 0.24f),
                size = Size(w * 0.46f, w * 0.46f),
                cornerRadius = CornerRadius(w * 0.07f, w * 0.07f),
                style = stroke,
            )
            drawRoundRect(
                color = color,
                topLeft = p(0.36f, 0.38f),
                size = Size(w * 0.46f, w * 0.46f),
                cornerRadius = CornerRadius(w * 0.07f, w * 0.07f),
                style = stroke,
            )
        }

        AppIcon.Move -> {
            val box = Path().apply {
                moveTo(w * 0.50f, w * 0.16f)
                lineTo(w * 0.84f, w * 0.50f)
                lineTo(w * 0.50f, w * 0.84f)
                lineTo(w * 0.16f, w * 0.50f)
                close()
            }
            drawPath(box, color, style = stroke)
            drawCircle(color, w * 0.07f, p(0.50f, 0.50f), style = Fill)
        }

        AppIcon.Photo -> {
            drawRoundRect(
                color = color,
                topLeft = p(0.16f, 0.24f),
                size = Size(w * 0.68f, w * 0.52f),
                cornerRadius = CornerRadius(w * 0.07f, w * 0.07f),
                style = stroke,
            )
            drawCircle(color, w * 0.08f, p(0.34f, 0.40f), style = stroke)
            val hill = Path().apply {
                moveTo(w * 0.20f, w * 0.66f)
                lineTo(w * 0.44f, w * 0.44f)
                lineTo(w * 0.84f, w * 0.66f)
            }
            drawPath(hill, color, style = stroke)
        }

        AppIcon.Search -> {
            drawCircle(color, w * 0.22f, p(0.44f, 0.44f), style = stroke)
            drawLine(color, p(0.60f, 0.60f), p(0.80f, 0.80f), sw, StrokeCap.Round)
        }

        AppIcon.Folder -> {
            drawRoundRect(
                color = color,
                topLeft = p(0.14f, 0.28f),
                size = Size(w * 0.72f, w * 0.50f),
                cornerRadius = CornerRadius(w * 0.08f, w * 0.08f),
                style = stroke,
            )
            val tab = Path().apply {
                moveTo(w * 0.14f, w * 0.42f)
                lineTo(w * 0.14f, w * 0.30f)
                lineTo(w * 0.36f, w * 0.28f)
                lineTo(w * 0.44f, w * 0.28f)
                lineTo(w * 0.50f, w * 0.36f)
                lineTo(w * 0.86f, w * 0.36f)
            }
            drawPath(tab, color, style = stroke)
        }
    }
}
