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

# ── Gson（client-l SDK 内部依赖）──
-keep class com.google.gson.** { *; }
-dontwarn com.google.gson.**

# ── 本地 OCR（rapidocr4j + onnxruntime + opencv，JNI 静态注册需保留类名）──
-keep class io.github.hzkitty.** { *; }
-keep class ai.onnxruntime.** { *; }
-keep class org.opencv.** { *; }
-dontwarn io.github.hzkitty.**
-dontwarn ai.onnxruntime.**
-dontwarn org.opencv.**

# ── PDFBox（反射解析字体/CMap）──
-keep class com.tom_roush.** { *; }
-dontwarn com.tom_roush.**

# ── BouncyCastle（算法注册使用反射）──
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

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
