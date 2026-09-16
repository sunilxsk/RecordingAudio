-keepattributes SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile
-repackageclasses

# ---------- FFmpegKit ----------
# AAR 本地引入，必须完整保留，否则 R8 可能裁掉只在 native 回调里用到的成员
-keep class com.arthenica.ffmpegkit.** { *; }
-keepclassmembers class com.arthenica.ffmpegkit.** { *; }

# ---------- smart-exception（崩溃修复关键）----------
# FFmpegKitConfig 静态初始化会直接引用 com.arthenica.smartexception.java.Exceptions，
# 该类被 R8 裁掉就会导致点击结束时 NoClassDefFoundError 闪退。
# 即使 app 代码里没有任何显式引用也必须 keep。
-keep class com.arthenica.smartexception.** { *; }
-keep class com.arthenica.smartexception.java.Exceptions { *; }
-keepclassmembers class com.arthenica.smartexception.** { *; }
-dontwarn com.arthenica.smartexception.**

# ---------- Compose / AndroidX ----------
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# ---------- 反射/序列化保护 ----------
-keepclassmembers class * {
    native <methods>;
}
-keep class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}
