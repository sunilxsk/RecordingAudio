package com.leoskyzwengmail.Internalrecordingaudio.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.leoskyzwengmail.Internalrecordingaudio.audio.AudioCaptureManager
import com.leoskyzwengmail.Internalrecordingaudio.audio.AudioConverter
import com.leoskyzwengmail.Internalrecordingaudio.audio.ConvertRequest
import com.leoskyzwengmail.Internalrecordingaudio.audio.LevelMonitor
import com.leoskyzwengmail.Internalrecordingaudio.data.Settings
import com.leoskyzwengmail.Internalrecordingaudio.data.toConfig
import com.leoskyzwengmail.Internalrecordingaudio.data.toSettings
import com.leoskyzwengmail.Internalrecordingaudio.floating.FloatWindowService
import com.leoskyzwengmail.Internalrecordingaudio.floating.FloatWindowServiceProxy
import com.leoskyzwengmail.Internalrecordingaudio.player.Media3Player
import com.leoskyzwengmail.Internalrecordingaudio.player.RepeatMode
import com.leoskyzwengmail.Internalrecordingaudio.ui.pages.FileTarget
import com.leoskyzwengmail.Internalrecordingaudio.ui.pages.RecordingGroup
import com.leoskyzwengmail.Internalrecordingaudio.util.AppFileHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 一帧电平数据（波形可视化用）。
 * bars / peaks 为 0~1 的归一化幅度，顺序为「最旧 → 最新」。
 */
data class LevelFrame(
    val dbFs: Float = -120f,
    val silent: Boolean = false,
    val bars: List<Float> = List(LevelMonitor.BARS) { 0f },
    val peaks: List<Float> = List(LevelMonitor.BARS) { 0f },
)

class RecordingViewModel(application: Application) : AndroidViewModel(application) {

    private val app: Application get() = getApplication()
    private val manager: AudioCaptureManager = AudioCaptureManager.get(application)

    private val _settings = MutableStateFlow(AppFileHelper.loadConfig(application).toSettings())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    private val _state = MutableStateFlow(AudioCaptureManager.STATE_IDLE)
    val state: StateFlow<Int> = _state.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private val _level = MutableStateFlow(LevelFrame())
    val level: StateFlow<LevelFrame> = _level.asStateFlow()

    // ---------- 播放（Media3，走 PlaybackService）----------
    private val player = Media3Player(application)
    val playback: StateFlow<Media3Player.PlaybackState> = player.state

    private val _apps = MutableStateFlow<List<AppFileHelper.AppInfo>>(emptyList())
    val apps: StateFlow<List<AppFileHelper.AppInfo>> = _apps.asStateFlow()

    /** 外部（公开目录）与内部（应用私有，降级时使用）两组录音 */
    private val _groups = MutableStateFlow<List<RecordingGroup>>(emptyList())
    val groups: StateFlow<List<RecordingGroup>> = _groups.asStateFlow()

    /** 转换输出目录里的文件 */
    private val _converted = MutableStateFlow<List<File>>(emptyList())
    val converted: StateFlow<List<File>> = _converted.asStateFlow()

    private val _converting = MutableStateFlow(false)
    val converting: StateFlow<Boolean> = _converting.asStateFlow()

    /** 转换进度 0~1；-1 表示不确定进度 */
    private val _convertProgress = MutableStateFlow(-1f)
    val convertProgress: StateFlow<Float> = _convertProgress.asStateFlow()

    /** 等待写入封面的目标音频 */
    private var coverTarget: File? = null

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 5)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val listener = object : AudioCaptureManager.OnRecordStateListener {
        override fun onStateChanged(state: Int) {
            _state.value = state
            if (state == AudioCaptureManager.STATE_IDLE) {
                _durationMs.value = 0L
                refreshRecordings()
            }
        }

        override fun onError(message: String) {
            _messages.tryEmit(message)
        }

        override fun onRecordFinished(file: File, sizeBytes: Long) {
            _messages.tryEmit("已保存：${file.name}（${AppFileHelper.formatFileSize(sizeBytes)}）")
            refreshRecordings()
        }

        override fun onDurationUpdate(durationMs: Long) {
            _durationMs.value = durationMs
        }
    }

    private val levelListener = AudioCaptureManager.OnLevelListener { dbFs, silent, bars, peaks ->
        _level.value = LevelFrame(dbFs, silent, bars.toList(), peaks.toList())
    }

    init {
        manager.addOnRecordStateListener(listener)
        manager.addOnLevelListener(levelListener)
        manager.setConfig(_settings.value.toConfig())
        _state.value = manager.state
        loadApps()
        refreshRecordings()
    }

    override fun onCleared() {
        manager.removeOnRecordStateListener(listener)
        manager.removeOnLevelListener(levelListener)
        player.release()
        super.onCleared()
    }

    // ---------- 设置 ----------

    /**
     * 更新设置。录制中也能立即生效的部分（增益 / 静音阈值 / 静音模式 / 自动停止）
     * 由 AudioCaptureManager 内部热更新；采样率等结构性参数下次录音生效。
     */
    fun updateSettings(newSettings: Settings) {
        _settings.value = newSettings
        AppFileHelper.saveConfig(app, newSettings.toConfig())
        manager.setConfig(newSettings.toConfig())
    }

    fun resetDefaults() {
        AppFileHelper.clearAllPrefs(app)
        val fresh = AppFileHelper.loadConfig(app).toSettings()
        _settings.value = fresh
        manager.setConfig(fresh.toConfig())
        _messages.tryEmit("已恢复默认设置")
    }

    // ---------- 录音控制 ----------

    fun startRecording(resultCode: Int, data: Intent) {
        val name = "Rec_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        manager.setConfig(_settings.value.toConfig())
        manager.startRecording(name, resultCode, data)
    }

    fun pause() = manager.pauseRecording()

    fun resume() = manager.resumeRecording()

    fun stop() = manager.stopRecording()

    fun isBusy(): Boolean = manager.isBusy()

    // ---------- 应用列表 ----------

    fun loadApps() {
        viewModelScope.launch {
            _apps.value = withContext(Dispatchers.IO) { AppFileHelper.getThirdPartyApps(app) }
        }
    }

    // ---------- 录音文件 ----------

    fun refreshRecordings() {
        viewModelScope.launch {
            val (groups, converted) = withContext(Dispatchers.IO) {
                val external = AppFileHelper.getPublicRecordDir()
                val internal = AppFileHelper.getPrivateRecordDir(app)
                val g = listOf(
                    RecordingGroup(
                        title = "外部存储",
                        subtitle = "${external.absolutePath}（未授权时可能为空）",
                        files = AppFileHelper.listInDir(external, 50),
                    ),
                    RecordingGroup(
                        title = "内部存储",
                        subtitle = "${internal.absolutePath}（降级时使用）",
                        files = AppFileHelper.listInDir(internal, 50),
                    ),
                )
                g to AudioConverter.listConverted(app, 50)
            }
            _groups.value = groups
            _converted.value = converted
        }
    }

    fun renameRecording(file: File, newName: String) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { AppFileHelper.renameRecording(file, newName) }
            _messages.tryEmit(if (ok) "已重命名" else "重命名失败：名称为空或已存在")
            refreshRecordings()
        }
    }

    fun deleteRecording(file: File) {
        viewModelScope.launch {
            val playing = player.state.value.path == file.absolutePath
            if (playing) player.stop()
            val ok = withContext(Dispatchers.IO) { AppFileHelper.deleteRecording(file) }
            _messages.tryEmit(if (ok) "已删除 ${file.name}" else "删除失败")
            refreshRecordings()
        }
    }

    // ---------- 播放 ----------

    /** 从某个分组内开始播放；循环范围只限该分组（外部 / 内部 / 转换三者独立） */
    fun play(file: File, contextFiles: List<File>) = player.playFrom(file, contextFiles)

    fun seek(ms: Long) = player.seekTo(ms)

    fun stopPlay() = player.stop()

    /** 实时音调 / 音速，立即作用于正在播放的音频 */
    fun setPlaybackParams(speed: Float, pitch: Float) = player.setPlaybackParams(speed, pitch)

    fun skipPrev() = player.skipPrevious()

    fun skipNext() = player.skipNext()

    /** 播放 / 暂停当前曲目（有曲目时播放，正在播则暂停） */
    fun togglePlayPause() {
        if (player.state.value.path == null) return
        if (player.state.value.playing) player.pause() else player.play()
    }

    /** 切换循环模式，返回切换后的模式（供 UI 弹提示） */
    fun cycleRepeat(): RepeatMode {
        player.cycleRepeatMode()
        return player.state.value.repeatMode
    }

    // ---------- FFmpeg 转换 ----------

    fun convert(req: ConvertRequest) {
        if (_converting.value) {
            _messages.tryEmit("已有转换任务正在进行")
            return
        }
        _converting.value = true
        _convertProgress.value = -1f
        viewModelScope.launch {
            val result = AudioConverter.execute(app, req) { p ->
                _convertProgress.value = p
            }
            _converting.value = false
            _convertProgress.value = -1f
            _messages.tryEmit(result.message)
            refreshRecordings()
        }
    }

    // ---------- 移动 / 复制 ----------

    private fun targetDir(target: FileTarget): File = when (target) {
        FileTarget.INTERNAL -> AppFileHelper.getPrivateRecordDir(app)
        FileTarget.EXTERNAL -> AppFileHelper.getPublicRecordDir()
        FileTarget.CONVERT -> AudioConverter.getConvertDir(app)
    }

    fun copyTo(file: File, target: FileTarget) {
        viewModelScope.launch {
            val out = withContext(Dispatchers.IO) {
                AudioConverter.copyToDir(file, targetDir(target))
            }
            _messages.tryEmit(
                if (out != null) "已复制到 ${target.label}：${out.name}" else "复制失败"
            )
            refreshRecordings()
        }
    }

    fun moveTo(file: File, target: FileTarget) {
        viewModelScope.launch {
            val out = withContext(Dispatchers.IO) {
                AudioConverter.moveToDir(file, targetDir(target))
            }
            _messages.tryEmit(
                if (out != null) "已移动到 ${target.label}：${out.name}" else "移动失败"
            )
            refreshRecordings()
        }
    }

    // ---------- 封面 ----------

    /** 记录要写封面的音频，并通知 UI 拉起系统图片选择 */
    fun requestCover(file: File) {
        coverTarget = file
    }

    /** 用户选完图片后调用 */
    fun applyCover(uri: Uri) {
        val target = coverTarget
        if (target == null) {
            _messages.tryEmit("未选择目标音频")
            return
        }
        coverTarget = null
        if (_converting.value) {
            _messages.tryEmit("已有任务正在进行")
            return
        }
        _converting.value = true
        _convertProgress.value = -1f
        viewModelScope.launch {
            val image = withContext(Dispatchers.IO) {
                AudioConverter.copyImageToCache(app, uri)
            }
            if (image == null) {
                _converting.value = false
                _messages.tryEmit("读取图片失败")
                return@launch
            }
            val result = AudioConverter.writeCover(app, target, image)
            withContext(Dispatchers.IO) { image.delete() }
            _converting.value = false
            _convertProgress.value = -1f
            _messages.tryEmit(result.message)
            refreshRecordings()
        }
    }

    // ---------- 悬浮窗 ----------

    fun isFloatRunning(): Boolean = FloatWindowServiceProxy.getInstance().isServiceAlive()

    fun startFloat() {
        val intent = Intent(app, FloatWindowService::class.java)
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                app.startForegroundService(intent)
            } else {
                app.startService(intent)
            }
        }.onFailure {
            _messages.tryEmit("启动悬浮窗失败：${it.message}")
        }
    }

    fun stopFloat() {
        runCatching { app.stopService(Intent(app, FloatWindowService::class.java)) }
    }
}
