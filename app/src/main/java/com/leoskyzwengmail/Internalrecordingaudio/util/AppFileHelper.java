package com.leoskyzwengmail.Internalrecordingaudio.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.media.AudioAttributes;
import android.os.Environment;

import com.leoskyzwengmail.Internalrecordingaudio.audio.RecordingConfig;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 文件 / 配置 / 应用列表 工具类。
 * 全部配置统一存放在 SharedPreferences，UI（Compose）与悬浮球共用同一份数据源。
 */
public class AppFileHelper {

    public static final String RECORD_DIR = "Internalrecording";

    private static final String PREF_NAME = "audio_recording_prefs";
    private static final String KEY_SELECTED_UIDS = "selected_uids";
    private static final String KEY_SAMPLE_RATE = "sample_rate";
    private static final String KEY_BIT_DEPTH = "bit_depth";
    private static final String KEY_BIT_RATE = "bit_rate";
    private static final String KEY_FORMAT = "format";
    private static final String KEY_USAGE = "usage";
    private static final String KEY_IS_GLOBAL = "is_global";
    private static final String KEY_SILENCE_MODE = "silence_mode";
    private static final String KEY_SILENCE_THRESHOLD = "silence_threshold_db";
    private static final String KEY_SILENCE_DURATION = "silence_duration_sec";
    private static final String KEY_AUTO_STOP_MODE = "auto_stop_mode";
    private static final String KEY_AUTO_STOP_CUSTOM = "auto_stop_custom_min";
    private static final String KEY_MIC_ENABLED = "mic_enabled";
    private static final String KEY_MIC_GAIN = "mic_gain";
    private static final String KEY_INTERNAL_GAIN = "internal_gain";

    private static final SimpleDateFormat logTimeFormat =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());

    // ============ 目录 ============

    public static boolean ensureRecordDir() {
        File dir = getRecordDirectory(null);
        return dir.exists() ? dir.isDirectory() : dir.mkdirs();
    }

    public static boolean ensureRecordDir(Context context) {
        File dir = getRecordDirectory(context);
        return dir.exists() ? dir.isDirectory() : dir.mkdirs();
    }

    public static File getRecordDirectory() {
        return getRecordDirectory(null);
    }

    /**
     * 优先公开目录 /sdcard/Music/Internalrecording；
     * 无全盘权限时自动降级到应用专属目录，避免直接录不了。
     */
    public static File getRecordDirectory(Context context) {
        File publicDir = new File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                RECORD_DIR);
        if (isDirUsable(publicDir)) return publicDir;

        publicDir = new File(Environment.getExternalStorageDirectory(), RECORD_DIR);
        if (isDirUsable(publicDir)) return publicDir;

        if (context != null) {
            try {
                File appDir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC);
                if (appDir == null) appDir = context.getExternalFilesDir(null);
                if (appDir != null) {
                    File fallback = new File(appDir, RECORD_DIR);
                    if (fallback.exists() || fallback.mkdirs()) return fallback;
                }
            } catch (Throwable e) {
                writeErrorLog("getRecordDirectory", new RuntimeException(e));
            }
        }
        return publicDir;
    }

    /** PCM 临时文件目录 */
    public static File getTempDir(Context context) {
        File dir;
        try {
            File ext = context.getExternalFilesDir(null);
            dir = new File(ext != null ? ext : context.getFilesDir(), ".pcm_tmp");
        } catch (Throwable t) {
            dir = new File(context.getFilesDir(), ".pcm_tmp");
        }
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    private static boolean isDirUsable(File dir) {
        try {
            if (dir.exists()) return dir.isDirectory() && dir.canWrite();
            return dir.mkdirs();
        } catch (Throwable e) {
            return false;
        }
    }

    /** 清空临时 PCM */
    public static void clearTemp(Context context) {
        try {
            File dir = getTempDir(context);
            File[] files = dir.listFiles();
            if (files == null) return;
            for (File f : files) f.delete();
        } catch (Throwable ignore) {
        }
    }

    /** 最近录音文件（按时间倒序） */
    public static List<File> listRecordings(Context context, int max) {
        return listInDir(getRecordDirectory(context), max);
    }

    /** 外部（公开）录音目录，可能不可用 */
    public static File getPublicRecordDir() {
        File publicDir = new File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                RECORD_DIR);
        if (isDirUsable(publicDir)) return publicDir;
        File alt = new File(Environment.getExternalStorageDirectory(), RECORD_DIR);
        return isDirUsable(alt) ? alt : publicDir;
    }

    /** 应用私有（降级时使用的）录音目录 */
    public static File getPrivateRecordDir(Context context) {
        try {
            File appDir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC);
            if (appDir == null) appDir = context.getExternalFilesDir(null);
            if (appDir == null) appDir = context.getFilesDir();
            File dir = new File(appDir, RECORD_DIR);
            if (dir.exists() || dir.mkdirs()) return dir;
        } catch (Throwable e) {
            writeErrorLog("getPrivateRecordDir", new RuntimeException(e));
        }
        return new File(context.getFilesDir(), RECORD_DIR);
    }

    /** 列出某个目录下的录音（按时间倒序，过滤掉日志等非音频文件） */
    public static List<File> listInDir(File dir, int max) {
        List<File> result = new ArrayList<File>();
        if (dir == null) return result;
        try {
            File[] files = dir.listFiles();
            if (files == null) return result;
            List<File> all = new ArrayList<File>(Arrays.asList(files));
            Collections.sort(all, new Comparator<File>() {
                @Override
                public int compare(File a, File b) {
                    return Long.compare(b.lastModified(), a.lastModified());
                }
            });
            for (File f : all) {
                if (!f.isFile()) continue;
                if (!isAudioFile(f)) continue;
                result.add(f);
                if (result.size() >= max) break;
            }
        } catch (Throwable ignore) {
        }
        return result;
    }

    /** 常见音频后缀（用于过滤 error.log 之类的杂项） */
    private static final String[] AUDIO_EXT = {
            "m4a", "mp3", "ogg", "flac", "wav", "pcm", "opus", "aac",
    };

    public static boolean isAudioFile(File f) {
        String name = f.getName();
        int dot = name.lastIndexOf('.');
        if (dot < 0) return false;
        String ext = name.substring(dot + 1).toLowerCase(Locale.getDefault());
        for (String e : AUDIO_EXT) {
            if (e.equals(ext)) return true;
        }
        return false;
    }

    /** 去掉扩展名后的主文件名（重命名对话框预填用） */
    public static String baseName(File f) {
        String name = f.getName();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    public static String extensionOf(File f) {
        String name = f.getName();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(dot) : "";
    }

    /**
     * 重命名，自动补回原扩展名。
     * @param newBaseName 用户输入的主文件名（可能自带扩展名，这里会去重）
     */
    public static boolean renameRecording(File f, String newBaseName) {
        if (f == null || !f.exists()) return false;
        String input = newBaseName.trim();
        if (input.isEmpty()) return false;

        // 用户可能顺手输入了扩展名，去掉后再统一补，避免出现 .m4a.m4a
        String ext = extensionOf(f);
        if (!ext.isEmpty()) {
            String lower = input.toLowerCase(Locale.getDefault());
            if (lower.endsWith(ext.toLowerCase(Locale.getDefault()))) {
                input = input.substring(0, input.length() - ext.length());
            } else {
                int dot = input.lastIndexOf('.');
                if (dot > 0) input = input.substring(0, dot);
            }
        }
        input = input.replace('/', '_').replace('\\', '_').trim();
        if (input.isEmpty()) return false;

        File target = new File(f.getParentFile(), input + ext);
        if (target.equals(f)) return true;
        if (target.exists()) return false;
        return f.renameTo(target);
    }

    public static boolean deleteRecording(File f) {
        if (f == null) return false;
        try {
            return f.delete();
        } catch (Throwable e) {
            writeErrorLog("deleteRecording", new RuntimeException(e));
            return false;
        }
    }

    public static String formatFileSize(long size) {
        if (size < 1024) return size + " B";
        if (size < 1024 * 1024) return String.format(Locale.getDefault(), "%.1f KB", size / 1024.0);
        if (size < 1024L * 1024 * 1024) {
            return String.format(Locale.getDefault(), "%.1f MB", size / (1024.0 * 1024));
        }
        return String.format(Locale.getDefault(), "%.2f GB", size / (1024.0 * 1024 * 1024));
    }

    // ============ 日志 ============

    public static void writeErrorLog(String tag, Throwable throwable) {
        try {
            File logFile = new File(getRecordDirectory(), "error.log");
            FileWriter writer = null;
            try {
                writer = new FileWriter(logFile, true);
                writer.write("===== " + logTimeFormat.format(new Date()) + " | " + tag + " =====\n");
                StringWriter sw = new StringWriter();
                PrintWriter pw = new PrintWriter(sw);
                throwable.printStackTrace(pw);
                writer.write(sw.toString() + "\n\n");
                writer.flush();
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                if (writer != null) {
                    try {
                        writer.close();
                    } catch (Exception ignore) {
                    }
                }
            }
        } catch (Throwable ignore) {
        }
    }

    // ============ 第三方应用列表 ============

    public static List<AppInfo> getThirdPartyApps(Context context) {
        List<AppInfo> appList = new ArrayList<AppInfo>();
        try {
            PackageManager pm = context.getPackageManager();
            List<ApplicationInfo> packages = pm.getInstalledApplications(0);
            String currentPkg = context.getPackageName();

            for (ApplicationInfo appInfo : packages) {
                boolean isSystem = (appInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                boolean isUpdatedSystem =
                        (appInfo.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
                if (isSystem && !isUpdatedSystem) continue;
                if (appInfo.packageName.equals(currentPkg)) continue;

                AppInfo app = new AppInfo();
                app.appName = appInfo.loadLabel(pm).toString();
                app.packageName = appInfo.packageName;
                app.uid = appInfo.uid;
                try {
                    app.icon = appInfo.loadIcon(pm);
                } catch (Throwable ignore) {
                    app.icon = null;
                }
                appList.add(app);
            }
            Collections.sort(appList, new Comparator<AppInfo>() {
                @Override
                public int compare(AppInfo a, AppInfo b) {
                    return a.appName.compareToIgnoreCase(b.appName);
                }
            });
        } catch (Throwable e) {
            writeErrorLog("getThirdPartyApps", new RuntimeException(e));
        }
        return appList;
    }

    public static class AppInfo {
        public String appName;
        public String packageName;
        public int uid;
        public Drawable icon;
    }

    // ============ 配置持久化 ============

    public static RecordingConfig loadConfig(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        RecordingConfig c = new RecordingConfig();
        c.sampleRate = prefs.getInt(KEY_SAMPLE_RATE, 44100);
        c.bitDepth = prefs.getInt(KEY_BIT_DEPTH, 16);
        c.bitRate = prefs.getInt(KEY_BIT_RATE, 128000);
        c.format = prefs.getString(KEY_FORMAT, RecordingConfig.FORMAT_AAC);
        c.usage = prefs.getInt(KEY_USAGE, 0);
        c.global = prefs.getBoolean(KEY_IS_GLOBAL, true);
        c.targetUids = loadSelectedUids(context);

        c.silenceMode = prefs.getInt(KEY_SILENCE_MODE, RecordingConfig.SILENCE_OFF);
        c.silenceThresholdDb = prefs.getFloat(KEY_SILENCE_THRESHOLD, -55f);
        c.silenceDurationSec = prefs.getInt(KEY_SILENCE_DURATION, 2);

        c.autoStopMode = prefs.getInt(KEY_AUTO_STOP_MODE, RecordingConfig.AUTO_STOP_OFF);
        c.autoStopCustomMin = prefs.getInt(KEY_AUTO_STOP_CUSTOM, 10);

        c.micEnabled = prefs.getBoolean(KEY_MIC_ENABLED, false);
        c.micGain = prefs.getFloat(KEY_MIC_GAIN, 1.0f);
        c.internalGain = prefs.getFloat(KEY_INTERNAL_GAIN, 1.0f);
        return c;
    }

    public static void saveConfig(Context context, RecordingConfig c) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_SAMPLE_RATE, c.sampleRate)
                .putInt(KEY_BIT_DEPTH, c.bitDepth)
                .putInt(KEY_BIT_RATE, c.bitRate)
                .putString(KEY_FORMAT, c.format)
                .putInt(KEY_USAGE, c.usage)
                .putBoolean(KEY_IS_GLOBAL, c.global)
                .putInt(KEY_SILENCE_MODE, c.silenceMode)
                .putFloat(KEY_SILENCE_THRESHOLD, c.silenceThresholdDb)
                .putInt(KEY_SILENCE_DURATION, c.silenceDurationSec)
                .putInt(KEY_AUTO_STOP_MODE, c.autoStopMode)
                .putInt(KEY_AUTO_STOP_CUSTOM, c.autoStopCustomMin)
                .putBoolean(KEY_MIC_ENABLED, c.micEnabled)
                .putFloat(KEY_MIC_GAIN, c.micGain)
                .putFloat(KEY_INTERNAL_GAIN, c.internalGain)
                .apply();
        saveSelectedUids(context, c.targetUids);
    }

    public static void saveSelectedUids(Context context, List<Integer> uids) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        Set<String> uidSet = new HashSet<String>();
        if (uids != null) {
            for (int uid : uids) uidSet.add(String.valueOf(uid));
        }
        prefs.edit().putStringSet(KEY_SELECTED_UIDS, uidSet).apply();
    }

    public static List<Integer> loadSelectedUids(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        Set<String> uidSet = prefs.getStringSet(KEY_SELECTED_UIDS, new HashSet<String>());
        List<Integer> uids = new ArrayList<Integer>();
        for (String uidStr : uidSet) {
            try {
                uids.add(Integer.parseInt(uidStr));
            } catch (NumberFormatException ignore) {
            }
        }
        return uids;
    }

    public static void clearAllPrefs(Context context) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit().clear().apply();
    }

    // ============ 下拉框数据 ============

    public static final int[] SAMPLE_RATES = {8000, 16000, 22050, 32000, 44100, 48000, 96000};
    public static final int[] BIT_DEPTHS = {16, 24, 32};

    public static String usageName(int usage) {
        switch (usage) {
            case AudioAttributes.USAGE_MEDIA:
                return "媒体";
            case AudioAttributes.USAGE_GAME:
                return "游戏";
            case AudioAttributes.USAGE_ALARM:
                return "闹钟";
            case AudioAttributes.USAGE_NOTIFICATION:
                return "通知";
            case AudioAttributes.USAGE_VOICE_COMMUNICATION:
                return "语音通话";
            default:
                return "全部";
        }
    }
}
