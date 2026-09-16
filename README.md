# RecordingAudio · 😆

> 内部录音（AudioPlaybackCapture）工具。Material Design 3 界面 + FFmpeg 真实编码 + 实时波形 + 静音检测 + 自动停止 + 麦克风混录 + 悬浮窗面板 + Media3 播放 + 四页导航。

---

## 功能概览

### 四个页面

| 页 | 内容 |
|---|---|
| 录音 | 状态卡（计时 + 实时波形 + dBFS + 静音状态）、输出格式、音质参数、静音检测、自动停止、麦克风、悬浮窗 |
| 来源 | 全局内录 / 音频类型 / 指定应用（含搜索与应用图标） |
| 文件 | 外部存储、内部存储、转换后文件三组列表，支持搜索、播放、重命名、删除、转换 |
| 关于 | 权限设置、使用指导、开源许可、恢复默认设置 |

### 录音

- **状态栏适配**：默认淡紫 `#EADDFF`，深色主题用 `#1F1B2B`；配合 `WindowInsetsControllerCompat` 强制深色图标，保证浅底深字可读。
- **实时波形**：录音核心每帧算 RMS（dBFS）与峰值，写入 64 格循环缓冲，节流 20fps 推给 UI。主界面计时卡显示对称柱状波形 + 峰值包络线 + 静音阈值虚线 + 实时 dBFS 与命中状态。
- **静音检测**：
  - 关闭 / 检测到静音自动停止 / 检测到静音时剪掉静音段
  - 阈值可调（默认 -55 dBFS），持续时长可调（默认 2 秒）
  - 开麦克风混录时启用自适应门限：底噪跟踪 + 底噪之上 12 dB 才算有声 + 3 dB 回差，避免底噪抖动
  - 剪静音全程内存操作，不截断文件；保留 80ms 呼吸间隔 + 30ms 淡入，接缝无感；起始静音直接丢弃，无论多长
- **自动停止**：关闭 / 3 / 5 / 15 分钟 / 自定义（1 ~ 120 分钟）。每块重算，改完立即按新值执行。
- **麦克风混录**：内录 + 麦克风两条独立采集线程，浮点域相加混音，两侧音量/增益分别可调，输出软限幅防爆音。
- **悬浮窗面板**：约 244×52dp 半透明圆角面板，含计时、波形、播放/暂停、结束四个区域；录制中整体转红；可拖动，按钮独立响应。

### 来源

全局内录 / 按音频类型（媒体 / 游戏 / 语音等）/ 指定应用（搜索 + 应用图标，列表高度 560dp）。

### 文件

- **三组列表**：外部存储、内部存储、转换后文件，各自限高（屏幕 40%，最小 200dp），卡内滚动。顶部搜索框按文件名过滤，标题实时显示命中数。
- **播放（应用内）**：基于 Media3 ExoPlayer + MediaSessionService。
  - 切页面、界面重建不打断播放；通知栏与系统媒体卡片始终有效
  - 进度条下方 `⏮ 上一曲` `▶/⏸ 播放暂停` `⏭ 下一曲` `循环模式`
  - 循环模式：默认（播完停）/ 顺序循环 / 随机循环，映射为 ExoPlayer 原生 `repeatMode` 与 `shuffleModeEnabled`
  - 三个目录互相独立：从哪个目录开始播放，队列就只由该目录组成
  - 顶部显示「本目录共 N 首 · 第 M 首」
  - 顶部有实时音调 / 音速滑块（0.5x ~ 2.0x），通过 `setPlaybackParameters` 立即生效
- **封面**：优先读 ExoPlayer 解析的 `MediaMetadata.artworkData`，退回 `MediaMetadataRetriever.getEmbeddedPicture()`，都没有则显示默认图标。解码在子线程，切歌时校验当前曲目后才更新。
- **默认渐变背景**：无内嵌封面的音频在服务端生成淡蓝/淡紫/淡黄/淡粉随机两色三段渐变图（320×320），颜色由文件路径 hash 决定，同一文件稳定不变。内嵌封面超过 512px 先缩放，避免 Binder 超限。
- **长按转换（12 项，全部走 FFmpeg）**：

| 操作 | 实现 |
|---|---|
| 转 MP3 / AAC / M4A | `-c:a libmp3lame` / `-c:a aac` + `-b:a {码率}k`（滑块 64~320） |
| 转 WAV | `-c:a pcm_s16le` |
| 转 FLAC | `-c:a flac -compression_level {0~12}` |
| 转 OGG | `-c:a libvorbis -q:a {0~10}` |
| 倒放音频 | `-af areverse`；>60MB 自动分段倒放后按倒序拼接，避免 OOM |
| 逆转声道 | `-ac 2 -af 'pan=stereo\|c0=c1\|c1=c0'` |
| 去除左 / 右声道 | `-ac 2 -af 'pan=mono\|c0=c1'` / `'pan=mono\|c0=c0'` |
| 音调音速改变 | 双滑块 → `'aresample=44100,asetrate=44100*{音调},aresample=44100,atempo={音速}'` |
| 增大音量 | 滑块 → `-af 'volume={增益}dB'` |
| 写入封面图 | MP3 用 `-c:v mjpeg -disposition:v attached_pic -id3v2_version 3`；M4A/FLAC 用 `-c:v mjpeg -disposition:v attached_pic`；WAV/PCM 菜单置灰 |

- **输出目录**：`/sdcard/Music/FormatConversion/`，无全盘权限时降级到应用专属目录。
- **文件名**自动追加 `_功能名_参数`，如 `Rec_xxx_转MP3_192k.mp3`。
- **移动 / 复制**：长按 → 选目标目录 → 二次弹窗选复制或移动。同名自动追加 `_1` `_2`，跨分区 rename 失败自动回退为复制 + 删除。
- **转换进度**：单条命令按 `StatisticsCallback.time` 除以时长算百分比；分段模式按已完成步骤上报；读不到时长则用不确定进度条。

### 关于

应用图标 → 权限设置 → 介绍与使用指导 → 开源许可 → 恢复默认设置。

---

## 关键实现说明

### 采集与编码

- 采集线程使用阻塞读，累积到凑满一整块（20ms）才产出，保证「产出一块 = 精确 20ms」，避免非阻塞读造成的计时放大与音频断续。
- 采样率由 `AudioRecord.Builder.setSampleRate` 决定；位深 24/32 bit 用 `ENCODING_PCM_FLOAT`，16 bit 用 `PCM_16BIT`；码率作用于 FFmpeg `-b:a`（无损格式 UI 置灰）。
- 录音阶段写原始 PCM 到临时文件，结束后由 FFmpeg 一次性转码。
- FFmpeg 可用性预检（`Class.forName` 触发静态初始化，在后台线程捕获 `NoClassDefFoundError`）；不可用或编码失败时自动封装为标准 WAV 保存，并提示「已自动改用 WAV」，保证不丢录音。

### 配置热更新

`AudioCaptureManager.setConfig()` 区分 `activeConfig` 与 `config`：

| 立即生效 | 下次录音生效 |
|---|---|
| 麦克风增益、内录音量 | 采样率、位深、格式、声道数 |
| 静音模式、静音阈值、持续时长 | 全局内录 / 音频类型 / 指定应用、麦克风开关 |
| 自动停止模式与时长 | |

### Media3 播放

- `PlaybackService` 继承 `MediaSessionService`，ExoPlayer 开 `handleAudioFocus` 与 `handleAudioBecomingNoisy`，`MediaSession` 设 `setSessionActivity`；`onDestroy` 先释放 player 再释放 session。
- `MediaItem` 只传 `mediaId`（存文件绝对路径），在 `MediaSession.Callback.onAddMediaItems()` 里还原为 Uri——绕开系统对跨进程 Uri 的安全剥离。
- `Media3Player` 把 `MediaController` 的异步 IPC 包装成 StateFlow，UI 只与状态流打交道。
- 整个目录一次性 `setMediaItems(items, startIndex, 0)`，上一曲/下一曲走原生 `seekToPreviousMediaItem()` / `seekToNextMediaItem()`。
- 用 `ForwardingPlayer` 包一层传给 `MediaSession`，`getAvailableCommands()` 与 `isCommandAvailable()` 同时补上 next/previous，并在 `addListener()` 中主动重推一次补齐后的 commands，让 MediaSession 拿到正确值；`seekToNextMediaItem()` 兜底绕回第一首，按钮永不消失。
- `MediaSession.Callback.onMediaButtonEvent()` 直接拦截线控：NEXT/SKIP_FORWARD → 下一首，PREVIOUS/SKIP_BACKWARD → 上一首，其余返回 false 交回默认处理。按键 DOWN/UP 配对去抖（800ms），避免一次按键切两首。
- Manifest 声明 `FOREGROUND_SERVICE_MEDIA_PLAYBACK` 权限 + `foregroundServiceType="mediaPlayback"` + `exported="true"` + 固定 intent-filter `androidx.media3.session.MediaSessionService`。

### 悬浮窗

`FloatPanelView` 约 244×52dp，半透明圆角背景，录制中整体转红；点播放/暂停开始/暂停/继续，点停止结束保存；拖动空白处可移动，按钮独立响应。

---

## 目录结构

```
InternalRecordingAudio/
├── build.gradle
├── settings.gradle
├── gradle.properties
├── local.properties
├── gradle/wrapper/gradle-wrapper.properties
└── app/
    ├── build.gradle
    ├── proguard-rules.pro
    ├── libs/README.md
    └── src/main/
        ├── AndroidManifest.xml
        ├── res/
        └── java/com/leoskyzwengmail/Internalrecordingaudio/
            ├── MainActivity.kt
            ├── audio/
            │   ├── RecordingConfig.java
            │   ├── AudioCaptureManager.java
            │   ├── CaptureService.java
            │   ├── PcmCodec.java
            │   ├── LevelMonitor.java
            │   ├── SilenceDetector.java
            │   ├── AudioMixer.java
            │   ├── WavWriter.java
            │   └── FfmpegEncoder.java
            ├── floating/
            │   ├── FloatWindowService.java
            │   ├── FloatPanelView.java
            │   ├── WaveformBarView.java
            │   ├── FloatProxyActivity.java
            │   └── FloatWindowServiceProxy.java
            ├── player/
            │   ├── PlaybackService.kt
            │   └── Media3Player.kt
            ├── util/AppFileHelper.java
            ├── data/Settings.kt
            └── ui/
                ├── RecordingViewModel.kt
                ├── RecordingScreen.kt
                ├── AppNav.kt
                ├── pages/{RecordPage, SourcesPage, FilesPage, AboutPage, FormatUtils}.kt
                ├── components/{AppIcons, WaveformBar, SettingsComponents}.kt
                └── theme/{Theme, Color, Type, StatusBar}.kt
```

---

## 依赖

```gradle
// Material Design 3
implementation "androidx.compose.material3:material3:1.3.0"

// Media3
implementation "androidx.media3:media3-session:1.4.0"
implementation "androidx.media3:media3-exoplayer:1.4.0"
implementation "androidx.media3:media3-common:1.4.0"

// 音频编码
implementation fileTree(dir: 'libs', include: ['*.jar', '*.aar'])
implementation 'com.arthenica:smart-exception-java:0.2.1'
implementation 'com.arthenica:smart-exception-common:0.2.1'

`minSdk` 为 **29**

---

## 构建

```bash
cd InternalRecordingAudio
gradle --refresh-dependencies assembleRelease
```
---

## 开源许可

本项目使用的第三方组件及其许可，详见应用内「关于 → 开源许可」。