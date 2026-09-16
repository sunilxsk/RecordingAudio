package com.leoskyzwengmail.Internalrecordingaudio.player

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.view.KeyEvent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.leoskyzwengmail.Internalrecordingaudio.MainActivity
import java.io.File

/**
 * Media3 播放服务。
 *
 * 为什么必须有它：只写普通通知，系统不会把 App 识别为「音乐播放器」，
 * 状态栏/锁屏的媒体控件、耳机线控都不会出现。
 * MediaSessionService 会：
 *   - 自动创建 MediaStyle 通知（含播放/暂停/进度）
 *   - 自动启动 mediaPlayback 类型的前台服务
 *   - 把会话暴露给系统媒体控件、蓝牙、Android Auto
 *
 * 坑：通过 MediaController 传过来的 MediaItem 会被剥离 Uri（安全策略），
 * 必须在 onAddMediaItems 里把 mediaId 还原成 Uri，否则播放不出来。
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private var player: ExoPlayer? = null

    // ---- 线控去抖：DOWN / UP 成对到达，只让其中一个真正执行 ----
    private var lastKeyCode = -1
    private var lastKeyAction = -1
    private var lastKeyTime = 0L

    override fun onCreate() {
        super.onCreate()

        val exoPlayer = ExoPlayer.Builder(this)
            .setAudioAttributes(AudioAttributes.DEFAULT, true)   // 自动处理音频焦点
            .setHandleAudioBecomingNoisy(true)                   // 拔耳机自动暂停
            .build()
        player = exoPlayer

        val sessionActivityIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        val sessionActivity = PendingIntent.getActivity(this, 0, sessionActivityIntent, pendingFlags)

        // 传给 MediaSession 的是 AlwaysSkipPlayer，而不是裸 ExoPlayer。详见其说明。
        mediaSession = MediaSession.Builder(this, AlwaysSkipPlayer(exoPlayer))
            .setSessionActivity(sessionActivity)
            .setCallback(SessionCallback())
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    /** 用户从最近任务划掉 App 时，没在播放就顺手停掉，避免留一个空通知 */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = player ?: run {
            stopSelf()
            return
        }
        if (!p.playWhenReady || p.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        // 释放顺序不能反：先 player 再 session，反了会泄漏
        mediaSession?.release()
        player?.release()
        mediaSession = null
        player = null
        super.onDestroy()
    }

    // ---------------- 上一曲 / 下一曲的实际动作 ----------------

    /** 下一首；已经是最后一首就绕回第一首并继续播（避免按了没反应） */
    private fun skipNext(p: ExoPlayer) {
        val count = p.mediaItemCount
        if (count == 0) return
        if (p.hasNextMediaItem()) {
            p.seekToNextMediaItem()
        } else {
            p.seekTo(0, 0L)
            p.play()
        }
    }

    /** 上一首；已经是第一首就回到开头（符合常见播放器交互） */
    private fun skipPrev(p: ExoPlayer) {
        if (p.mediaItemCount == 0) return
        if (p.hasPreviousMediaItem()) {
            p.seekToPreviousMediaItem()
        } else {
            p.seekTo(0L)
        }
    }

    /**
     * 让「上一曲 / 下一曲」命令永远可用。
     *
     * ExoPlayer 只有在 **确实存在** 下一首时才把 `COMMAND_SEEK_TO_NEXT_MEDIA_ITEM`
     * 标为可用。而系统媒体卡片 / 线控读的是 `PlaybackState` 的
     * `ACTION_SKIP_TO_NEXT`，该 action 又来自这个 command。
     * 结果：列表只有 1 首、或播到最后一首时，下一个按钮消失、线控按了没反应。
     *
     * 修法（Media3 官方推荐，见 androidx/media#1708）：用 ForwardingPlayer 包一层，
     * 让这两个 command 恒为可用。
     *
     * 两个容易漏的点：
     *  1. 必须同时重写 `isCommandAvailable()`——ForwardingPlayer 默认直接问被包装的
     *     player，只改 `getAvailableCommands()` 不生效。
     *  2. 必须重写 `addListener()` 主动补推一次——MediaSession 是靠 **listener 回调**
     *     `onAvailableCommandsChanged` 拿可用命令的，而 ForwardingPlayer 转发出去的是
     *     被包装 player 的**原始**值（不含 next/prev）。不补推的话，MediaSession
     *     手里始终是「没有下一曲」的那份，按钮依旧不出现。
     */
    private class AlwaysSkipPlayer(private val exo: ExoPlayer) : ForwardingPlayer(exo) {

        private fun isSkipCommand(command: Int): Boolean =
            command == Player.COMMAND_SEEK_TO_NEXT ||
                command == Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM ||
                command == Player.COMMAND_SEEK_TO_PREVIOUS ||
                command == Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM

        private fun withSkip(src: Player.Commands): Player.Commands =
            src.buildUpon()
                .add(Player.COMMAND_SEEK_TO_NEXT)
                .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                .build()

        override fun getAvailableCommands(): Player.Commands = withSkip(super.getAvailableCommands())

        override fun isCommandAvailable(command: Int): Boolean =
            super.isCommandAvailable(command) || isSkipCommand(command)

        override fun hasNextMediaItem(): Boolean = true
        override fun hasPreviousMediaItem(): Boolean = true

        override fun addListener(listener: Player.Listener) {
            super.addListener(listener)
            // 覆盖 ForwardingPlayer 转发出来的原始值
            listener.onAvailableCommandsChanged(getAvailableCommands())
        }

        override fun seekToNextMediaItem() {
            if (exo.mediaItemCount == 0) return
            if (exo.hasNextMediaItem()) {
                exo.seekToNextMediaItem()
            } else {
                exo.seekTo(0, 0L)
                exo.play()
            }
        }

        override fun seekToNext() = seekToNextMediaItem()

        override fun seekToPreviousMediaItem() {
            if (exo.mediaItemCount == 0) return
            if (exo.hasPreviousMediaItem()) {
                exo.seekToPreviousMediaItem()
            } else {
                exo.seekTo(0L)
            }
        }

        override fun seekToPrevious() = seekToPreviousMediaItem()
    }

    /**
     * 线控兜底：直接拦截媒体按键，绕开所有 command 可用性检查。
     *
     * 这是官方为「想自己处理 next/prev」提供的钩子（Media3 1.2.0+）：
     * 返回 true 就不再走 Media3 内部的默认映射。
     * 有了它，即使上面那条路因为 ROM 差异没生效，线控的下一曲也一定能切歌。
     */
    private inner class SessionCallback : MediaSession.Callback {

        override fun onMediaButtonEvent(
            session: MediaSession,
            controllerInfo: MediaSession.ControllerInfo,
            intent: Intent,
        ): Boolean {
            if (Intent.ACTION_MEDIA_BUTTON != intent.action) return false

            val keyEvent: KeyEvent? =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT) as? KeyEvent
                }
            if (keyEvent == null) return false

            val code = keyEvent.keyCode
            val isSkip = code == KeyEvent.KEYCODE_MEDIA_NEXT ||
                code == KeyEvent.KEYCODE_MEDIA_PREVIOUS ||
                code == KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD ||
                code == KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD

            // 其余按键（播放/暂停/停止等）交回 Media3 默认处理，不抢
            if (!isSkip) return false

            val now = SystemClock.uptimeMillis()
            val action = keyEvent.action

            // DOWN/UP 成对到达：抬起时若与上一次按下配对，就吞掉，避免一次按键切两首。
            // 只发 UP 的极少数设备仍能正常触发（配对判断不成立 → 走下面执行）。
            val pairedUp = action == KeyEvent.ACTION_UP &&
                lastKeyAction == KeyEvent.ACTION_DOWN &&
                lastKeyCode == code &&
                now - lastKeyTime < 800L

            lastKeyCode = code
            lastKeyAction = action
            lastKeyTime = now

            if (pairedUp) return true

            val p = player ?: return true
            when (code) {
                KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD -> skipNext(p)
                KeyEvent.KEYCODE_MEDIA_PREVIOUS,
                KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD -> skipPrev(p)
            }
            return true
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> {
            val resolved = mediaItems.map { item ->
                val path = item.mediaId
                val file = File(path)
                if (path.isBlank() || !file.exists()) {
                    item
                } else {
                    item.buildUpon()
                        .setUri(android.net.Uri.fromFile(file))
                        .setMediaMetadata(buildMetadata(file))
                        .build()
                }
            }.toMutableList()
            return Futures.immediateFuture(resolved)
        }
    }

    /**
     * 构造用于系统媒体卡片（通知栏 / 锁屏）的元数据。
     * 有内嵌封面就用封面，没有就生成渐变图，避免系统显示灰色默认图标。
     */
    private fun buildMetadata(file: File): MediaMetadata {
        val builder = MediaMetadata.Builder()
            .setTitle(file.nameWithoutExtension)
            .setDisplayTitle(file.nameWithoutExtension)
            .setArtist("内部录音")

        val art = CoverArt.embeddedOrGradient(file.absolutePath)
        if (art != null && art.isNotEmpty()) {
            builder.setArtworkData(art, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
        }
        return builder.build()
    }
}
