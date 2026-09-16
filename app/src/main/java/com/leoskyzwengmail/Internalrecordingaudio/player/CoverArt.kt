package com.leoskyzwengmail.Internalrecordingaudio.player

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.media.MediaMetadataRetriever
import java.io.ByteArrayOutputStream
import kotlin.math.min

/**
 * 封面兜底图。
 *
 * 没有内嵌封面的音频，系统媒体卡片（通知栏 / 锁屏）会显示一个灰色默认图标。
 * 这里给每个文件生成一张「随机两种色」的渐变图作为默认背景。
 *
 * 关键点：
 * 1. 颜色由文件路径 hash 决定，所以同一文件每次进来都是同一张图，不会闪烁。
 * 2. 生成放在服务端（PlaybackService.onAddMediaItems）而不是 UI，
 *    避免大 ByteArray 走 Binder（单次事务上限 1MB）。
 * 3. 配色用**高饱和**的色值。之前用的是极淡的 pastel，经过 SystemUI 的
 *    压暗/取色处理后看起来就是一片灰，渐变也几乎看不出来。
 */
object CoverArt {

    /**
     * 四色，按「相邻色对比弱」的顺序排列，取色时会跳过相邻位（见 [pickPair]），
     * 保证抽到的两种色差足够大、渐变一眼能看出来。
     *
     * 蓝 / 黄 / 紫 / 粉 —— 都是 Material 300~400 档，鲜艳但不刺眼。
     */
    private val PALETTE = intArrayOf(
        0xFF42A5F5.toInt(), // 蓝
        0xFFFFCA28.toInt(), // 黄
        0xFFAB47BC.toInt(), // 紫
        0xFFEC407A.toInt(), // 粉
    )

    private const val SIZE = 512
    /** 内嵌封面超过这个边长就先缩放，避免大图走 Binder 触发 TransactionTooLarge */
    private const val MAX_EMBEDDED = 512
    /** 兜底图压缩后的大小上限，超过就降质量重压 */
    private const val MAX_BYTES = 96 * 1024

    /**
     * 取封面字节：优先文件内嵌封面，没有就生成渐变图。
     * 只做本地文件的元数据读取，不读整个文件，耗时可控。
     */
    fun embeddedOrGradient(path: String): ByteArray? {
        readEmbedded(path)?.let { return it }
        return gradient(path)
    }

    private fun readEmbedded(path: String): ByteArray? = try {
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(path)
        val art = retriever.embeddedPicture
        retriever.release()
        art?.let { downscaleIfNeeded(it) }
    } catch (e: Exception) {
        null
    }

    /** 内嵌封面可能上千像素，直接塞进 Metadata 会顶到 Binder 上限，先缩一下 */
    private fun downscaleIfNeeded(data: ByteArray): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0) return null

        val scale = min(1f, MAX_EMBEDDED.toFloat() / maxOf(w, h).toFloat())
        if (scale >= 1f && data.size <= MAX_BYTES) return data

        val sample = BitmapFactory.Options().apply {
            inSampleSize = if (scale < 1f) {
                var s = 1
                while (maxOf(w, h) / (s * 2) >= MAX_EMBEDDED) s *= 2
                s
            } else 1
        }
        val bmp = BitmapFactory.decodeByteArray(data, 0, data.size, sample) ?: return data
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 88, out)
        bmp.recycle()
        return out.toByteArray()
    }

    /**
     * 抽两种色：保证不是相邻位，色差足够大，渐变才明显。
     * 返回 palette 的两个下标。
     */
    private fun pickPair(seed: String): Pair<Int, Int> {
        val h = seed.hashCode().toLong() and 0xFFFFFFFFL
        val i = (h % PALETTE.size).toInt()
        // 跳过相邻位：只取 +1 或 +3（即 -1），两种都和 i 差得足够远
        val step = if ((h / PALETTE.size) % 2L == 0L) 1 else PALETTE.size - 1
        val j = (i + step) % PALETTE.size
        return i to j
    }

    /** 生成渐变 PNG。同一 path 恒定得到同一张图。 */
    fun gradient(seed: String): ByteArray? = try {
        val (i, j) = pickPair(seed)
        val start = PALETTE[i]
        val end = PALETTE[j]

        val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // 三段渐变：起 → 中间过渡 → 止。
        // 纯两点渐变在大色差下中段容易显得断层，加一个中间色让过渡更顺滑，
        // 同时整体跨度更大，一眼就能看出是渐变而不是单色。
        val mid = blend(start, end, 0.5f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, SIZE.toFloat(), SIZE.toFloat(),
                intArrayOf(start, mid, end),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, SIZE.toFloat(), SIZE.toFloat(), paint)

        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
        bitmap.recycle()
        out.toByteArray()
    } catch (e: Exception) {
        null
    }

    private fun blend(from: Int, to: Int, ratio: Float): Int {
        val r = (android.graphics.Color.red(from) * (1f - ratio) +
            android.graphics.Color.red(to) * ratio).toInt()
        val g = (android.graphics.Color.green(from) * (1f - ratio) +
            android.graphics.Color.green(to) * ratio).toInt()
        val b = (android.graphics.Color.blue(from) * (1f - ratio) +
            android.graphics.Color.blue(to) * ratio).toInt()
        return android.graphics.Color.argb(0xFF, r, g, b)
    }
}
