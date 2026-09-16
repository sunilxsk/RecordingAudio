package com.leoskyzwengmail.Internalrecordingaudio.player

import android.content.ComponentName
import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.Locale

/** 循环模式 */
enum class RepeatMode(val label: String) {
    /** 默认：播完当前这首就停 */
    OFF("默认"),

    /** 顺序循环 */
    ALL("顺序循环"),

    /** 随机循环 */
    SHUFFLE("随机循环"),
}

/**
 * 播放控制门面：把 Media3 的 MediaController（异步 IPC）包装成 UI 可直接观察的状态流。
 *
 * 播放实际发生在 [PlaybackService] 里，这里只是众多 controller 中的一个。
 * 好处：退出页面 / 界面重建都不会打断播放，通知栏控件始终有效。
 *
 * ## 关于「上一曲 / 下一曲」
 * 之前只往播放器里塞了 **一首** MediaItem，队列是自己在 UI 层维护的。
 * 于是 ExoPlayer 认为没有下一首 → `COMMAND_SEEK_TO_NEXT` 不可用 →
 * 系统媒体卡片不显示下一曲按钮，耳机线控按了也没反应。
 *
 * 现在改成**把整个目录塞进 ExoPlayer 播放列表**，交给它原生管理：
 *   - 系统卡片 / 线控的上一曲、下一曲自动可用
 *   - 循环与随机直接用 ExoPlayer 的 repeatMode / shuffleModeEnabled
 *   - 三个目录仍然互相独立（队列只在开始播放的那个目录内）
 */
@OptIn(UnstableApi::class)
class Media3Player(private val context: Context) {

    data class PlaybackState(
        val path: String? = null,
        val playing: Boolean = false,
        val positionMs: Long = 0L,
        val durationMs: Long = 0L,
        val error: String? = null,
        /** 播放速度倍率（0.5~2.0） */
        val speed: Float = 1f,
        /** 音调倍率（0.5~2.0） */
        val pitch: Float = 1f,
        /** 内嵌封面（已解码，供 UI 直接显示；无封面为 null） */
        val artwork: ImageBitmap? = null,
        val title: String? = null,
        /** 当前播放队列（同一目录内的文件） */
        val queue: List<String> = emptyList(),
        val queueIndex: Int = -1,
        val repeatMode: RepeatMode = RepeatMode.OFF,
    )

    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null

    /** 当前队列（绝对路径），只来自「开始播放时所在的那个目录」 */
    private var queue: List<String> = emptyList()

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            controller?.let { c ->
                if (c.isPlaying) {
                    _state.value = _state.value.copy(
                        positionMs = c.currentPosition.coerceAtLeast(0),
                        durationMs = c.duration.takeIf { it > 0 } ?: _state.value.durationMs,
                    )
                }
            }
            handler.postDelayed(this, 250)
        }
    }

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _state.value = _state.value.copy(playing = isPlaying)
            if (isPlaying) handler.post(ticker) else handler.removeCallbacks(ticker)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) {
                val c = controller ?: return
                _state.value = _state.value.copy(
                    durationMs = c.duration.takeIf { it > 0 } ?: 0L,
                    path = c.currentMediaItem?.mediaId ?: _state.value.path,
                    queueIndex = c.currentMediaItemIndex,
                )
            }
            if (playbackState == Player.STATE_ENDED) {
                handler.removeCallbacks(ticker)
                _state.value = _state.value.copy(playing = false)
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            mediaItem ?: return
            val path = mediaItem.mediaId
            _state.value = _state.value.copy(
                path = path,
                positionMs = 0L,
                durationMs = 0L,
                title = File(path).nameWithoutExtension,
                artwork = null,
            )
            loadArtwork(path)
        }

        override fun onPlayerError(error: PlaybackException) {
            _state.value = _state.value.copy(
                playing = false,
                error = "播放失败：${error.message ?: "不支持的格式"}",
            )
            handler.removeCallbacks(ticker)
        }
    }

    /** 连接服务（幂等）。播放前调用即可。 */
    private fun connect(onReady: (MediaController) -> Unit) {
        val existing = controller
        if (existing != null) {
            onReady(existing)
            return
        }
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        controllerFuture = future
        future.addListener({
            try {
                val c = future.get()
                c.addListener(listener)
                controller = c
                onReady(c)
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = "无法连接播放服务：${e.message}")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    // ---------------- 播放 ----------------

    /**
     * 从指定文件开始播放，队列取自它所在的分组（外部 / 内部 / 转换三个目录互相独立）。
     *
     * 整个目录一次性塞进 ExoPlayer 播放列表——这是让系统媒体卡片和耳机线控
     * 出现「上一曲 / 下一曲」的前提。
     *
     * @param contextFiles 该分组的全部文件；为空时退化为单曲播放
     */
    fun playFrom(file: File, contextFiles: List<File>) {
        val path = file.absolutePath
        val current = _state.value

        // 同一首：切换暂停 / 继续
        if (current.path == path && controller != null && current.queue.contains(path)) {
            if (current.playing) pause() else play()
            return
        }

        val list = if (contextFiles.isEmpty()) listOf(file) else contextFiles
        queue = list.map { it.absolutePath }
        val startIndex = queue.indexOf(path).coerceAtLeast(0)

        // 只传 mediaId（绝对路径）；Uri 与封面在服务端还原，避免大 ByteArray 走 Binder
        val items = queue.map { MediaItem.Builder().setMediaId(it).build() }

        _state.value = _state.value.copy(
            path = path,
            error = null,
            queue = queue,
            queueIndex = startIndex,
            title = file.nameWithoutExtension,
            artwork = null,
        )
        loadArtwork(path)

        connect { c ->
            c.setMediaItems(items, startIndex, 0L)
            applyRepeatMode(c)
            c.prepare()
            c.playWhenReady = true
        }
    }

    /** 单曲播放（外部调用时不知道分组，就用这一首） */
    fun toggle(file: File) = playFrom(file, listOf(file))

    private fun applyRepeatMode(c: MediaController) {
        when (_state.value.repeatMode) {
            RepeatMode.OFF -> {
                runCatching { c.repeatMode = Player.REPEAT_MODE_OFF }
                runCatching { c.shuffleModeEnabled = false }
            }

            RepeatMode.ALL -> {
                runCatching { c.repeatMode = Player.REPEAT_MODE_ALL }
                runCatching { c.shuffleModeEnabled = false }
            }

            RepeatMode.SHUFFLE -> {
                // 随机循环 = 洗牌 + 整表循环，ExoPlayer 一轮播完会重新洗
                runCatching { c.repeatMode = Player.REPEAT_MODE_ALL }
                runCatching { c.shuffleModeEnabled = true }
            }
        }
    }

    fun play() {
        connect { c ->
            if (c.mediaItemCount == 0) return@connect
            c.play()
        }
    }

    fun pause() {
        controller?.pause()
        _state.value = _state.value.copy(playing = false)
    }

    fun seekTo(ms: Long) {
        controller?.seekTo(ms.coerceAtLeast(0))
        _state.value = _state.value.copy(positionMs = ms.coerceAtLeast(0))
    }

    fun skipNext() {
        connect { c ->
            // hasNextMediaItem 在 REPEAT_MODE_ALL / 洗牌下会自动绕回开头
            if (c.hasNextMediaItem()) {
                c.seekToNextMediaItem()
            } else if (_state.value.repeatMode != RepeatMode.OFF) {
                c.seekTo(0)
            }
        }
    }

    fun skipPrevious() {
        // 常见交互：播放超过 3 秒时「上一曲」先回到本曲开头
        if (_state.value.positionMs > 3000) {
            seekTo(0)
            return
        }
        connect { c ->
            if (c.hasPreviousMediaItem()) {
                c.seekToPreviousMediaItem()
            } else {
                c.seekTo(0)
            }
        }
    }

    fun setRepeatMode(mode: RepeatMode) {
        _state.value = _state.value.copy(repeatMode = mode)
        controller?.let { applyRepeatMode(it) }
    }

    /** 循环切换：默认 → 顺序循环 → 随机循环 → 默认 */
    fun cycleRepeatMode() {
        setRepeatMode(
            when (_state.value.repeatMode) {
                RepeatMode.OFF -> RepeatMode.ALL
                RepeatMode.ALL -> RepeatMode.SHUFFLE
                RepeatMode.SHUFFLE -> RepeatMode.OFF
            }
        )
    }

    /** 停止播放并清空播放列表 → 通知随之消失、服务回到后台态 */
    fun stop() {
        handler.removeCallbacks(ticker)
        controller?.let { c ->
            runCatching { c.stop() }
            runCatching { c.clearMediaItems() }
        }
        queue = emptyList()
        _state.value = _state.value.copy(
            path = null,
            playing = false,
            positionMs = 0L,
            durationMs = 0L,
            artwork = null,
            title = null,
            queue = emptyList(),
            queueIndex = -1,
        )
    }

    /**
     * 实时改变音调与速度（ExoPlayer 原生支持，改完立即作用于正在播放的音频）。
     * speed = 播放速度倍率，pitch = 音调倍率；1.0 为原始。
     */
    fun setPlaybackParams(speed: Float, pitch: Float) {
        val s = speed.coerceIn(0.5f, 2.0f)
        val p = pitch.coerceIn(0.5f, 2.0f)
        _state.value = _state.value.copy(speed = s, pitch = p)
        connect { c ->
            runCatching { c.setPlaybackParameters(PlaybackParameters(s, p)) }
        }
    }

    // ---------------- 封面 ----------------

    private fun decodeToBitmap(data: ByteArray): ImageBitmap? = try {
        BitmapFactory.decodeByteArray(data, 0, data.size)?.asImageBitmap()
    } catch (e: Exception) {
        null
    }

    /**
     * 读内嵌封面（只用于 **应用内** 显示）。
     *
     * 注意这里不读 MediaMetadata 里的 artworkData——那里放的是服务端生成的
     * 渐变兜底图；应用内图标保持原样（没有封面就显示默认图标）。
     */
    private fun loadArtwork(path: String) {
        Thread {
            val bytes = try {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(path)
                val art = retriever.embeddedPicture
                retriever.release()
                art
            } catch (e: Exception) {
                null
            }
            val bmp = bytes?.let { decodeToBitmap(it) }
            handler.post {
                // 只在该文件仍是当前播放项时才更新，避免快速切歌时错位
                if (_state.value.path == path) {
                    _state.value = _state.value.copy(artwork = bmp)
                }
            }
        }.start()
    }

    /** 彻底断开（页面销毁时用） */
    fun release() {
        handler.removeCallbacks(ticker)
        controller?.removeListener(listener)
        // releaseFuture 收的是 ListenableFuture<MediaController>，不是 controller 本身
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controller = null
        controllerFuture = null
        _state.value = PlaybackState()
    }

    fun isPlaying(path: String?): Boolean =
        path != null && _state.value.playing && _state.value.path == path

    fun formatSpeed(value: Float): String =
        String.format(Locale.getDefault(), "%.2fx", value)
}
