package com.leoskyzwengmail.Internalrecordingaudio.audio

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.LogCallback
import com.arthenica.ffmpegkit.StatisticsCallback
import com.leoskyzwengmail.Internalrecordingaudio.util.AppFileHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.CountDownLatch

/** 转换操作定义 */
enum class ConvertOp(
    val label: String,
    /** 参数选择方式 */
    val quality: QualityKind,
) {
    MP3("转为 MP3", QualityKind.BITRATE),
    WAV("转为 WAV", QualityKind.NONE),
    FLAC("转为 FLAC", QualityKind.FLAC_LEVEL),
    AAC("转为 AAC", QualityKind.BITRATE),
    M4A("转为 M4A", QualityKind.BITRATE),
    OGG("转为 OGG", QualityKind.VORBIS_Q),
    REVERSE("倒放音频", QualityKind.NONE),
    SWAP_CHANNELS("逆转音频声道", QualityKind.NONE),
    DROP_LEFT("去除左声道", QualityKind.NONE),
    DROP_RIGHT("去除右声道", QualityKind.NONE),
    PITCH_TEMPO("音调音速改变", QualityKind.PITCH_TEMPO),
    VOLUME("增大音量", QualityKind.VOLUME_DB),
}

enum class QualityKind { NONE, BITRATE, FLAC_LEVEL, VORBIS_Q, PITCH_TEMPO, VOLUME_DB }

/** 一次转换请求 */
data class ConvertRequest(
    val input: File,
    val op: ConvertOp,
    /** BITRATE: kbps；FLAC_LEVEL: 0~12；VORBIS_Q: 0~10 */
    val quality: Int = 0,
    /** 音调倍率 0.5~2.0 */
    val pitch: Float = 1f,
    /** 速度倍率 0.5~2.0 */
    val tempo: Float = 1f,
    /** 增益 dB 0~30 */
    val gainDb: Int = 0,
)

/** 转换结果 */
data class ConvertResult(
    val ok: Boolean,
    val message: String,
)

object AudioConverter {

    const val CONVERT_DIR = "FormatConversion"

    /** 超过这个体积就走「分段处理」，避免 areverse 把整段音频读进内存撑爆 */
    private const val CHUNK_THRESHOLD_BYTES = 60L * 1024 * 1024
    /** 每段时长（秒） */
    private const val SEGMENT_SECONDS = 60

    /** 转换输出目录：优先公共 Music/FormatConversion，失败降级到应用私有目录 */
    fun getConvertDir(context: Context): File {
        val publicDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
            CONVERT_DIR,
        )
        if (usable(publicDir)) return publicDir
        val alt = File(Environment.getExternalStorageDirectory(), CONVERT_DIR)
        if (usable(alt)) return alt
        try {
            var appDir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
            if (appDir == null) appDir = context.getExternalFilesDir(null)
            if (appDir == null) appDir = context.filesDir
            val fallback = File(appDir, CONVERT_DIR)
            if (fallback.exists() || fallback.mkdirs()) return fallback
        } catch (e: Exception) {
            AppFileHelper.writeErrorLog("AudioConverter.getConvertDir", RuntimeException(e))
        }
        return publicDir
    }

    fun listConverted(context: Context, max: Int = 50): List<File> =
        AppFileHelper.listInDir(getConvertDir(context), max)

    private fun usable(dir: File): Boolean = try {
        if (dir.exists()) dir.isDirectory && dir.canWrite() else dir.mkdirs()
    } catch (e: Exception) {
        false
    }

    // ---------------- 输出命名 ----------------

    /** 输出文件扩展名（非格式类操作沿用原扩展名） */
    private fun extOf(op: ConvertOp, src: File): String {
        val srcExt = AppFileHelper.extensionOf(src).removePrefix(".").lowercase(Locale.getDefault())
        return when (op) {
            ConvertOp.MP3 -> "mp3"
            ConvertOp.WAV -> "wav"
            ConvertOp.FLAC -> "flac"
            ConvertOp.AAC -> "m4a"
            ConvertOp.M4A -> "m4a"
            ConvertOp.OGG -> "ogg"
            else -> srcExt.ifBlank { "m4a" }
        }
    }

    /** 文件名后缀：_功能名_参数 */
    private fun nameSuffix(req: ConvertRequest): String = when (req.op) {
        ConvertOp.MP3 -> "_转MP3_${req.quality}k"
        ConvertOp.WAV -> "_转WAV"
        ConvertOp.FLAC -> "_转FLAC_L${req.quality}"
        ConvertOp.AAC -> "_转AAC_${req.quality}k"
        ConvertOp.M4A -> "_转M4A_${req.quality}k"
        ConvertOp.OGG -> "_转OGG_q${req.quality}"
        ConvertOp.REVERSE -> "_倒放"
        ConvertOp.SWAP_CHANNELS -> "_逆转声道"
        ConvertOp.DROP_LEFT -> "_去除左声道"
        ConvertOp.DROP_RIGHT -> "_去除右声道"
        ConvertOp.PITCH_TEMPO -> String.format(
            Locale.getDefault(), "_音调%.2f_音速%.2f", req.pitch, req.tempo,
        )

        ConvertOp.VOLUME -> "_增大音量_+${req.gainDb}dB"
    }

    fun outputFileFor(context: Context, req: ConvertRequest): File {
        val base = AppFileHelper.baseName(req.input)
        val name = base + nameSuffix(req) + "." + extOf(req.op, req.input)
        return File(getConvertDir(context), name)
    }

    // ---------------- 命令构造 ----------------

    private fun q(s: String): String = "'$s'"

    /** 单条命令（非分段路径） */
    fun buildCommand(context: Context, req: ConvertRequest): String {
        val input = q(req.input.absolutePath)
        val output = q(outputFileFor(context, req).absolutePath)

        return when (req.op) {
            ConvertOp.MP3 ->
                "-hide_banner -y -i $input -c:a libmp3lame -b:a ${req.quality}k $output"

            ConvertOp.WAV ->
                "-hide_banner -y -i $input -c:a pcm_s16le $output"

            ConvertOp.FLAC ->
                "-hide_banner -y -i $input -c:a flac -compression_level ${req.quality} $output"

            ConvertOp.AAC, ConvertOp.M4A ->
                "-hide_banner -y -i $input -c:a aac -b:a ${req.quality}k -movflags +faststart $output"

            ConvertOp.OGG ->
                "-hide_banner -y -i $input -c:a libvorbis -q:a ${req.quality} $output"

            // areverse 会把整段音频读进内存，小文件没问题；大文件走分段
            ConvertOp.REVERSE ->
                "-hide_banner -y -i $input -af areverse $output"

            // 先 -ac 2 保证单声道输入也能正确处理
            ConvertOp.SWAP_CHANNELS ->
                "-hide_banner -y -i $input -ac 2 -af 'pan=stereo|c0=c1|c1=c0' $output"

            ConvertOp.DROP_LEFT ->
                "-hide_banner -y -i $input -ac 2 -af 'pan=mono|c0=c1' $output"

            ConvertOp.DROP_RIGHT ->
                "-hide_banner -y -i $input -ac 2 -af 'pan=mono|c0=c0' $output"

            // 先统一重采样到 44100，asetrate 才能按固定基准做变调
            ConvertOp.PITCH_TEMPO -> {
                val p = "%.4f".format(Locale.US, req.pitch)
                val t = "%.4f".format(Locale.US, req.tempo)
                "-hide_banner -y -i $input -af " +
                    "'aresample=44100,asetrate=44100*$p,aresample=44100,atempo=$t' $output"
            }

            ConvertOp.VOLUME ->
                "-hide_banner -y -i $input -af 'volume=${req.gainDb}dB' $output"
        }
    }

    /** 按目标扩展名给出编码器参数（分段处理最后一步用） */
    private fun codecArgs(ext: String): String = when (ext) {
        "mp3" -> "-c:a libmp3lame -b:a 192k"
        "flac" -> "-c:a flac"
        "wav" -> "-c:a pcm_s16le"
        "ogg" -> "-c:a libvorbis -q:a 5"
        else -> "-c:a aac -b:a 192k -movflags +faststart"
    }

    // ---------------- 执行 ----------------

    /**
     * 执行转换（挂起函数，内部切到 IO 线程）。
     *
     * @param onProgress 0~1 的进度；传 -1 表示「不确定」进度
     */
    suspend fun execute(
        context: Context,
        req: ConvertRequest,
        onProgress: (Float) -> Unit,
    ): ConvertResult = withContext(Dispatchers.IO) {
        try {
            val dir = getConvertDir(context)
            if (!dir.exists() && !dir.mkdirs()) {
                return@withContext ConvertResult(false, "输出目录创建失败：${dir.absolutePath}")
            }
            val out = outputFileFor(context, req)
            if (out.exists()) out.delete()

            if (req.op == ConvertOp.REVERSE && req.input.length() > CHUNK_THRESHOLD_BYTES) {
                return@withContext chunkedReverse(context, req, out, onProgress)
            }

            val durationMs = readDuration(req.input)
            onProgress(if (durationMs > 0) 0f else -1f)
            val ok = runCommand(buildCommand(context, req), durationMs, onProgress)
            if (!ok) {
                out.delete()
                return@withContext ConvertResult(false, "转换失败，请检查格式是否被 FFmpeg 支持")
            }
            onProgress(1f)
            ConvertResult(true, "已输出：${out.name}")
        } catch (t: Throwable) {
            AppFileHelper.writeErrorLog("AudioConverter.execute", RuntimeException(t))
            ConvertResult(false, "转换出错：${t.message}")
        }
    }

    /** 用 MediaMetadataRetriever 读时长（毫秒），读不到返回 0 */
    fun readDuration(file: File): Long = try {
        val r = MediaMetadataRetriever()
        r.setDataSource(file.absolutePath)
        val v = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
        r.release()
        v?.toLongOrNull() ?: 0L
    } catch (e: Exception) {
        0L
    }

    /** 执行一条 FFmpeg 命令并等待结束；totalMs > 0 时按 statistics.time 上报进度 */
    private fun runCommand(cmd: String, totalMs: Long, onProgress: (Float) -> Unit): Boolean {
        val latch = CountDownLatch(1)
        var ok = false
        var tail = ""

        try {
            FFmpegKit.executeAsync(
                cmd,
                { session: FFmpegSession ->
                    val rc = session.returnCode
                    ok = rc != null && rc.isValueSuccess
                    if (!ok) tail = session.allLogsAsString?.takeLast(400) ?: ""
                    latch.countDown()
                },
                LogCallback { },
                StatisticsCallback { stats ->
                    if (totalMs > 0) {
                        val t = stats.time.toDouble()
                        onProgress((t / totalMs).toFloat().coerceIn(0f, 0.99f))
                    }
                },
            )
        } catch (t: Throwable) {
            AppFileHelper.writeErrorLog("AudioConverter.runCommand", RuntimeException(t))
            return false
        }

        latch.await()
        if (!ok && tail.isNotBlank()) {
            AppFileHelper.writeErrorLog("AudioConverter", RuntimeException("FFmpeg: $tail"))
        }
        return ok
    }

    /**
     * 大文件倒放的分段处理。
     *
     * areverse 必须把整段音频读进内存，几百 MB 的录音会直接 OOM。
     * 做法：切段 → 每段单独倒放 → 把「倒放后的段」按倒序拼接，等价于整段倒放，
     * 而每一步的内存占用只有一个分段。
     */
    private fun chunkedReverse(
        context: Context,
        req: ConvertRequest,
        out: File,
        onProgress: (Float) -> Unit,
    ): ConvertResult {
        val work = File(context.cacheDir, "rev_${System.currentTimeMillis()}")
        if (!work.exists() && !work.mkdirs()) {
            return ConvertResult(false, "无法创建临时目录")
        }

        return try {
            val ext = extOf(req.op, req.input)
            val input = q(req.input.absolutePath)

            // 步骤 1：切段（重编码成 wav，保证每段可独立解码）
            val segPattern = File(work, "seg_%05d.wav").absolutePath
            onProgress(0f)
            val splitCmd = "-hide_banner -y -i $input -map 0:a -f segment " +
                "-segment_time $SEGMENT_SECONDS -c:a pcm_s16le -ar 44100 -ac 2 ${q(segPattern)}"
            if (!runCommand(splitCmd, 0L, onProgress)) {
                return ConvertResult(false, "切段失败")
            }

            val segments = (work.listFiles() ?: emptyArray())
                .filter { it.name.startsWith("seg_") && it.name.endsWith(".wav") }
                .sortedBy { it.name }

            if (segments.isEmpty()) {
                return ConvertResult(false, "切段结果为空")
            }

            // 步骤 2：逐段倒放
            val reversed = ArrayList<File>(segments.size)
            segments.forEachIndexed { index, seg ->
                val rev = File(work, "rev_${seg.name}")
                val cmd = "-hide_banner -y -i ${q(seg.absolutePath)} " +
                    "-af areverse -c:a pcm_s16le ${q(rev.absolutePath)}"
                if (!runCommand(cmd, 0L, onProgress)) {
                    return ConvertResult(false, "第 ${index + 1} 段倒放失败")
                }
                reversed.add(rev)
                seg.delete()
                onProgress((index + 1f) / (segments.size + 2f) * 0.95f)
            }

            // 步骤 3：按「倒序」拼接 —— 最后一段的倒放结果排在最前
            val listFile = File(work, "concat.txt")
            listFile.writeText(
                reversed.reversed().joinToString("\n") { "file '${it.absolutePath}'" },
            )

            onProgress(0.96f)
            val concatCmd = "-hide_banner -y -f concat -safe 0 " +
                "-i ${q(listFile.absolutePath)} ${codecArgs(ext)} ${q(out.absolutePath)}"
            if (!runCommand(concatCmd, 0L, onProgress)) {
                out.delete()
                return ConvertResult(false, "拼接失败")
            }

            onProgress(1f)
            ConvertResult(
                true,
                "已输出：${out.name}（大文件分段处理：${segments.size} 段）",
            )
        } catch (t: Throwable) {
            out.delete()
            AppFileHelper.writeErrorLog("AudioConverter.chunkedReverse", RuntimeException(t))
            ConvertResult(false, "分段倒放出错：${t.message}")
        } finally {
            work.deleteRecursively()
        }
    }

    // ---------------- 封面 ----------------

    /** 哪些容器能挂封面 */
    private val COVER_SUPPORTED = setOf("mp3", "m4a", "mp4", "aac", "flac")

    fun coverSupported(file: File): Boolean {
        val ext = AppFileHelper.extensionOf(file).removePrefix(".").lowercase(Locale.getDefault())
        return ext in COVER_SUPPORTED
    }

    /** 封面输出文件：原名 + _封面 */
    fun coverOutputFor(context: Context, audio: File): File {
        val base = AppFileHelper.baseName(audio)
        val ext = AppFileHelper.extensionOf(audio).removePrefix(".")
        return File(getConvertDir(context), "${base}_封面.$ext")
    }

    /** 构造写封面命令；不支持的格式返回 null */
    fun buildCoverCommand(context: Context, audio: File, image: File): String? {
        val ext = AppFileHelper.extensionOf(audio).removePrefix(".").lowercase(Locale.getDefault())
        if (ext !in COVER_SUPPORTED) return null

        val a = q(audio.absolutePath)
        val img = q(image.absolutePath)
        val out = q(coverOutputFor(context, audio).absolutePath)

        val common = "-map 0:a -map 1:v -c:a copy -c:v mjpeg -disposition:v attached_pic"
        return when (ext) {
            "mp3" ->
                "-hide_banner -y -i $a -i $img $common -id3v2_version 3 " +
                    "-metadata:s:v title='Album cover' " +
                    "-metadata:s:v comment='Cover (front)' $out"

            else ->
                "-hide_banner -y -i $a -i $img $common $out"
        }
    }

    /** 执行写封面 */
    suspend fun writeCover(
        context: Context,
        audio: File,
        image: File,
    ): ConvertResult = withContext(Dispatchers.IO) {
        val cmd = buildCoverCommand(context, audio, image)
            ?: return@withContext ConvertResult(false, "该格式不支持内嵌封面（支持 MP3/M4A/FLAC）")
        val out = coverOutputFor(context, audio)
        if (out.exists()) out.delete()
        val ok = runCommand(cmd, 0L, {})
        if (!ok) {
            out.delete()
            ConvertResult(false, "写入封面失败")
        } else {
            ConvertResult(true, "已输出：${out.name}")
        }
    }

    /** 把用户选中的图片 Uri 复制到缓存目录（FFmpeg 只认真实文件路径） */
    fun copyImageToCache(context: Context, uri: Uri, name: String = "cover.jpg"): File? = try {
        val dst = File(context.cacheDir, "cover_src_$name")
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(dst).use { output -> input.copyTo(output) }
        }
        if (dst.exists() && dst.length() > 0) dst else null
    } catch (e: Exception) {
        AppFileHelper.writeErrorLog("AudioConverter.copyImageToCache", RuntimeException(e))
        null
    }

    // ---------------- 移动 / 复制 ----------------

    /** 复制到目标目录，同名自动加 _1 / _2 … */
    fun copyToDir(src: File, dir: File): File? = try {
        if (!dir.exists() && !dir.mkdirs()) null
        else {
            val target = uniqueName(dir, src.name)
            src.inputStream().use { input ->
                FileOutputStream(target).use { output -> input.copyTo(output) }
            }
            target
        }
    } catch (e: Exception) {
        AppFileHelper.writeErrorLog("AudioConverter.copyToDir", RuntimeException(e))
        null
    }

    /** 移动到目标目录；跨分区复制失败时自动回退为「复制」 */
    fun moveToDir(src: File, dir: File): File? = try {
        if (!dir.exists() && !dir.mkdirs()) {
            null
        } else {
            val target = uniqueName(dir, src.name)
            if (src.renameTo(target)) {
                target
            } else {
                copyToDir(src, dir)?.also { src.delete() }
            }
        }
    } catch (e: Exception) {
        AppFileHelper.writeErrorLog("AudioConverter.moveToDir", RuntimeException(e))
        null
    }

    private fun uniqueName(dir: File, name: String): File {
        val candidate = File(dir, name)
        if (!candidate.exists()) return candidate
        val base = AppFileHelper.baseName(candidate)
        val ext = AppFileHelper.extensionOf(candidate)
        var i = 1
        while (true) {
            val f = File(dir, "${base}_$i$ext")
            if (!f.exists()) return f
            i++
        }
    }
}
