# ── Android 基础保留规则 ──
-keep class com.rokidlab.phone.app.LabApplication { *; }
-keep class com.rokidlab.phone.app.MainActivity { *; }

# 保留 AndroidManifest 中声明的四大组件
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver

# ── CXR-L SDK ──
-keep class com.rokid.cxr.** { *; }
-dontwarn com.rokid.cxr.**

# ── Compose ──
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# ── Kotlin 序列化 (如有使用) ──
-keepattributes *Annotation*, InnerClasses
-keep class kotlinx.serialization.** { *; }

# ── 通用优化选项 ──
-keepattributes Signature
-keepattributes Exceptions
-dontnote **
