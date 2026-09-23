# RokidLab 模块剥离 / 瘦身可行性评估

> ⚠️ **后已更正（2026-09-23）**：本文多处 `useLegacyPackaging = false`（含 §0 结论与体积口径）**已作废**，现为 **`true`（so 压缩存储）** —— 自持 proot 需在 `nativeLibraryDir` 内 `execve`，`false` 时 so 未压缩直载、系统不落盘 ⇒ 该目录为空、必然 ENOENT；官方 16KB 文档也把「压缩共享库」列为 AGP < 8.5.1 的替代方案（让 16KB 设备装不上的是「未压缩且未做 zip 对齐」，不是压缩）。⚠️ 因此文中按「未压缩 = 省解压值」估算的体积收益口径也随之失真。
>
> 权威说明见 `phone-app/build.gradle.kts` 的 `packaging` 块与 `platform/ProotShell.kt` 类注释；本文其余内容保持当时快照，未作订正。

> 评估对象：`D:\rokidapp\cxrl\RokidLab`（Kotlin/Compose，targetSdk 34，minSdk 29）
> 基线产物：`phone-app/build/outputs/apk/release/RokidLab-v3.6-release.apk` = 97,539,546 B = **93.02 MB**（850 个 zip 条目）
> 评估日期：2026-09-14 ｜ 性质：**只读分析，未改动任何源码 / 构建脚本 / 资源**

---

## 0. 结论速览

1. **可以整块剥离的最大一块是 OCR 全栈，合计 55.4 MB（占 93 MB 的 59.5%）**：
   `libopencv_java4.so` 22.38 + `libonnxruntime.so` 17.37 + `libc++_shared.so` 1.23 + 3 个 `.onnx` 模型 14.19。
   它在代码上是**完全孤立的**——`io.github.hzkitty` 只被 `LocalOcr.kt` 引用，`LocalOcr` 全工程只被 `PhotoQuizFlow.kt:105` 调用一次。

2. **但"打包成 zip 让用户下载"只对其中 14.19 MB 有效。**
   - `.onnx` 模型是**纯数据**，可以真·打包成 zip / 直链下载，解压到 `filesDir` 后**直接可用**（rapidocr4j 支持绝对路径加载，已用字节码级证据确认）。
   - `.so` native 库**不行**：Android 10+ 的 W^X 限制禁止 `dlopen` 应用可写目录里的 `.so`。要剥掉这 40.6 MB，**必须以「独立安装的辅助 APK」形态**（用户下载并安装第二个 APK，而不是下载 zip）。

3. **三种力度对应的体积**：

| 力度 | 做法 | 主 APK | 降幅 |
|---|---|---|---|
| **T1 保守** | 只剥纯数据 / 资源（模型走下载 + 清 PQC 资源 + 换字体） | **≈ 77.3 MB** | -16.9% |
| **T2 推荐** | T1 + OCR 全栈下沉到辅助 APK（`RokidLink.apk` 仍内置） | **≈ 36.2 MB** | -61.1% |
| **T3 激进** | T2 + `RokidLink.apk` 也改为下载 | **≈ 30.8 MB** | -66.9% |

4. **除 OCR 外，工程里没有第二块「体积大却少用」的整块功能。**
   `mirror/`、`hid/`、`adb/`、`LocalOllamaManager`(689 行) 等都是**纯代码**（无大资源），剥离它们对 APK 体积几乎无影响，不建议动。

**分水岭问题：是否接受引入第二个 APK？** 接受 → 省 61%；不接受 → 只能省 17%。

---

## 1. 体积构成实测

| 分类 | 解压 MB | 压缩 MB | 存储方式 | 占比(解压) |
|---|---|---|---|---|
| `lib/`（仅 arm64-v8a，9 个 .so） | 42.48 | 42.48 | **未压缩存储** | 44.0% |
| 根目录（classes.dex 17.40 + classes2.dex 8.09 + resources.arsc 1.59） | 27.09 | 27.08 | 压缩 | 28.1% |
| `assets/` | 24.34 | 21.37 | 压缩 | 25.2% |
| `res/`（722 条，含 3 个字体） | 1.32 | 0.69 | 压缩 | 1.4% |
| `org/`（bouncycastle 资源） | 1.25 | 1.17 | 压缩 | 1.3% |

**单文件 Top（解压 / 压缩 MB）**

| 文件 | 解压 | 压缩 |
|---|---|---|
| `lib/arm64-v8a/libopencv_java4.so` | 22.38 | 22.38 |
| `classes.dex` | 17.40 | 17.40 |
| `lib/arm64-v8a/libonnxruntime.so` | 17.37 | 17.37 |
| `assets/ch_PP-OCRv4_rec_infer.onnx` | 10.35 | 9.52 |
| `assets/RokidLink.apk` | 8.35 | 6.97 |
| `classes2.dex` | 8.09 | 8.09 |
| `assets/ch_PP-OCRv4_det_infer.onnx` | 4.53 | 4.18 |
| `lib/arm64-v8a/libc++_shared.so` | 1.23 | 1.23 |
| `org/bouncycastle/pqc/…lowmcL5.bin.properties` | 0.72 | 0.71 |
| `lib/arm64-v8a/libcxr-sock-proto-jni.so` | 0.61 | 0.61 |
| `assets/ch_ppocr_mobile_v2.0_cls_infer.onnx` | 0.56 | 0.49 |
| `res/__.ttf` / `res/qR.ttf` / `res/yK.ttf`（JetBrains Mono） | 0.78 | 0.37 |

> ⚠️ **估算口径（关键）**：`lib/*.so` 因 `useLegacyPackaging=false`（`phone-app/build.gradle.kts:82-86`）以**未压缩**方式存储，省下的字节 = **解压值**；而 `.onnx` / `.apk` / `org/*` / 字体都参与压缩，省下的字节 = **压缩值**（例：rec 模型解压 10.35，实际只省 9.52）。下文估算严格区分。

---

## 2. 逐项剥离清单（直接回答"哪些可以打包成 zip"）

| # | 项 | 体积 解压/压缩 | 可剥 | **交付形态** | 现用途 / 依赖关系 |
|---|---|---|---|---|---|
| 1 | `libopencv_java4.so` | 22.38 / 22.38 | ✅ | ❌ **不能 zip**，须**辅助 APK** | 源码零直接调用；rapidocr4j 的传递依赖 |
| 2 | `libonnxruntime.so` | 17.37 / 17.37 | ✅ | ❌ **不能 zip**，须**辅助 APK** | 同上 |
| 3 | `libonnxruntime4j_jni.so` | 0.10 / 0.10 | ✅ | ❌ **不能 zip** | 同上 |
| 4 | `ch_PP-OCRv4_rec_infer.onnx` | 10.35 / 9.52 | ✅ | ✅ **可以 zip / 直链下载** | OCR 模型，纯数据 |
| 5 | `ch_PP-OCRv4_det_infer.onnx` | 4.53 / 4.18 | ✅ | ✅ **可以 zip / 直链下载** | 同上 |
| 6 | `ch_ppocr_mobile_v2.0_cls_infer.onnx` | 0.56 / 0.49 | ✅ | ✅ **可以 zip / 直链下载** | 同上 |
| 7 | `libc++_shared.so` | 1.23 / 1.23 | ✅ | 随 #1 一并走 | **只被 libopencv_java4.so 依赖**（DT_NEEDED 实测） |
| 8 | `org/bouncycastle/pqc/**`（lowmc*.bin.properties） | 1.16 / 1.16 | ✅ | 构建期 `excludes` 直接去掉 | 代码只注册 RSA provider，**PQC 零引用** |
| 9 | `res/` 三个 JetBrains Mono 字体 | 0.78 / 0.37 | ✅ | 换系统等宽 / 裁剪字重 | `StoreTheme.kt:93-96` `BrewFont` 需 reg/med/bold |
| 10 | `assets/glass_mirror.py` | 0.007 / 0.002 | ✅ | 直接删（**死资源**） | 全仓无 Kotlin 引用 |
| 11 | dex 内 OCR 包装类 | 估 0.3–0.5 进 dex | ✅ | 随 #1–6 自动剔除 | opencv 435KB + onnxruntime4j 111KB + rapidocr4j 147KB 的 `.class` |
| 12 | `assets/RokidLink.apk` | 8.35 / 6.97 | ⚠️ | ✅ 可下载（**但高风险**） | 眼镜端安装必需，见 §5 风险 |
| 13 | `assets/scrcpy-server.jar` | 0.09 / 0.09 | ❌ | — | 投屏 H.264 高帧率模式必需（`AdbScreenMirrorClient.kt:319`） |
| 14 | `assets/apps.json` / `assets/skills/**` | 0.48 / ~0.11 | ❌ | — | 商店离线目录 / AIUI 技能文档，压缩后极小 |
| 15 | `Rokid SDK` 4 个 .so（libcaps/libcxr-*/libflora-cli/libmutils） | 1.40 / 1.40 | ❌ | — | 双端通信与授权，不可动 |

**一句话回答用户的问题**：能"打包成 zip 让用户自行下载"的，只有 **3 个 ONNX 模型（省 14.19 MB）**；而真正的大头——**40.6 MB 的 native 库——打包成 zip 是没用的**（下解释了为什么）。

---

## 3. 为什么 native 库不能"打包成 zip"？——W^X 硬约束

**结论：targetSdk 34 的工程，无法把 `.so` 解压到 `filesDir`/`cacheDir` 再 `System.load()`。**

官方依据（Android 10 行为变更 *Removed execute permission for app home directory*）：

> "Execution of files from the writable app home directory is a W^X violation. **Apps should load only the binary code that's embedded within an app's APK file.** Untrusted apps that target Android 10 cannot invoke `execve()` directly on files within the app's home directory. In addition, apps that target Android 10 cannot in-memory modify executable code from files which have been opened with `dlopen()` … because the library cannot have been mapped `PROT_EXEC` through a writable file descriptor."

机制：SELinux 对 `targetSdkVersion>=29` 的 `untrusted_app` 域收回了应用数据目录（`app_data_file`）上的 `execute` 权限，而 `dlopen` 需要该权限才能建立 `PROT_EXEC` 映射。真机表现为：

```
UnsatisfiedLinkError: dlopen failed: ... Permission denied
UnsatisfiedLinkError: dlopen failed: ... is not accessible for the namespace "classloader-namespace"
```

**允许 `dlopen` 的位置只剩两处**：① APK 自身的 `nativeLibraryDir`（系统安装时从 APK 解出、只读且带执行权限）；② 系统库目录。

对本工程的直接后果：rapidocr4j 自带的 `OpencvLoader.loadOpencvLib(String)` 内部就是 `System.load(path)`（javap 实测），所以**即便把 `OcrConfig.Global.opencvLibPath` 指向下载目录也没用**——同样被 W^X 拦住。

### 因此，"让用户自行下载"只有两种正确形态

| 形态 | 内容 | 是否可行 |
|---|---|---|
| **(a1) 独立 OCR APK（推荐）** | 单发一个 `RokidLab-OCR.apk`（自己的进程、自己 `nativeLibraryDir` 里的 `.so`），对外暴露 `ContentProvider.call()` 或带 `Messenger` 的绑定 Service | ✅ **成立**：各自进程加载各自的 native 库，**不触发跨包 dlopen** |
| **(a2) 插件 APK 同进程加载** | 主 APK `createPackageContext(INCLUDE_CODE)` + `DexClassLoader` + 反射加载插件 `.so` | ⚠️ **原理可行但未验证**：跨包 `dlopen` 受 SELinux + linker namespace 双重约束，须真机 spike |
| (b) 只下载 `.onnx` 模型 | 保留三个依赖，模型按需下载 | ✅ **成立**，省 14.19 MB（见 §4） |
| (c) 下载 zip → 解压 `.so` → `System.load` | — | ❌ **不可行**（W^X） |
| (d) 用 ABI 拆分包 / Play Feature Delivery | — | ❌ **不适用**：本工程分发 APK（非 AAB），APK split 只能按 ABI 拆、不能按功能拆 |

---

## 4. 关键技术验证：模型路径可外部指定（字节码级证据）

**结论：`.onnx` 可以剥出去、下载到 `filesDir` 后由 rapidocr4j 直接从文件系统加载；不需要 fork、不需要反射。**

`javap -c` 反编译 `rapidocr4j-android-1.0.0.aar` 的 `classes.jar`：

**(1) 公开 API 存在重载**
```java
public static RapidOCR create(android.content.Context);
public static RapidOCR create(android.content.Context, io.github.hzkitty.entity.OcrConfig);
```

**(2) `OcrConfig` 各子配置暴露可写 `modelPath`**
```
OcrConfig.DetConfig.modelPath : String   (getModelPath/setModelPath)
OcrConfig.RecConfig.modelPath : String   + recKeysPath : String
OcrConfig.ClsConfig.modelPath : String
OcrConfig.GlobalConfig.opencvLibPath : String
```
默认值为裸文件名（`ch_PP-OCRv4_rec_infer.onnx` 等），即默认走 assets。

**(3) 加载分支在 `io.github.hzkitty.utils.OrtInferSession.<init>`（字节码 offset 227–335）**
```
227-235:  Path p = Paths.get(modelPath);
239-244:  if (!p.isAbsolute()) goto 312;      // 相对路径 → assets 分支
247-261:  File f = p.toFile(); if (!f.exists()) throw new RuntimeException("模型文件未找到:");
291-306:  session = env.createSession(f.getAbsolutePath(), opts);   // ← 直接从文件系统加载 ✅
312+:     File f = copyAssetToCache(context, modelPath);            // context.getAssets().open(...)
```

**(4) 字典不需要额外文件**
`TextRecognizer` / `CTCLabelDecode` 中字符集键名是 `"character"`，即字典**内嵌在 ONNX 模型的 metadata**（RapidOCR 标准做法）；`recKeysPath` 只是可选覆盖。

**(5) AAR 的 assets 里只有 3 个 `.onnx`，没有字典文件**
```
assets/ch_PP-OCRv4_det_infer.onnx     4,745,517 B
assets/ch_PP-OCRv4_rec_infer.onnx    10,857,958 B
assets/ch_ppocr_mobile_v2.0_cls_infer.onnx  585,532 B
```

**(6) `libc++_shared.so` 归属确认**（对 APK 内 9 个 `.so` 解析 `DT_NEEDED`）

| .so | DT_NEEDED 中的 libc++_shared |
|---|---|
| `libopencv_java4.so` | ✅ **有** |
| `libonnxruntime.so` | ❌ 无 |
| `libonnxruntime4j_jni.so` | ❌ 无 |
| `libmutils.so` / `libflora-cli.so` / `libcaps.so` / `libcxr-bridge-jni.so` / `libcxr-sock-proto-jni.so` | ❌ 均无 |

交叉验证：`org.opencv:opencv:4.12.0` 的 AAR 内 `jni/arm64-v8a/libc++_shared.so` = 1,292,904 B，与 APK 内的 1.23 MiB 字节数一致。→ **它完全是 OCR 栈的一部分，可随 OCR 一起剥除，不会误伤 Rokid SDK 的 native 库。**

---

## 5. 三档方案与体积估算

> 基准 93.02 MB。`.so` 计**解压值**，`.onnx`/`.apk`/`org`/字体计**压缩值**。

### T1 保守（零 native 风险，推荐先做）

| 剥离项 | 省下 |
|---|---|
| 3 个 `.onnx` 模型走按需下载 | 14.19 |
| `org/bouncycastle/pqc/**` | 1.16 |
| JetBrains Mono 字体裁剪 | 0.37 |
| 删死资源 `glass_mirror.py` | 0.002 |
| **合计** | **≈ 15.72** |

**93.02 → ≈ 77.3 MB（-16.9%）** ｜ 置信度：**高**（纯数据/资源改造，无 native 加载风险）

### T2 推荐（OCR 全栈下沉辅助 APK）

| 剥离项 | 省下 |
|---|---|
| OCR native `.so`（39.85，未压缩存储） | 39.85 |
| `libc++_shared.so` | 1.23 |
| 3 个 `.onnx` | 14.19 |
| T1 中的 PQC + 字体 + 死资源 | 1.53 |
| dex 侧包装类（估算） | 0.3–0.5 |
| **合计** | **≈ 57.1** |

**93.02 → ≈ 36.0 MB（-61.3%）** ｜ 置信度：体积**高**，可行性**中高**（辅助 APK 走独立进程形态，不触 W^X）

> `assets/RokidLink.apk`（6.97 MB）**保留内置**。

### T3 激进（再剥 RokidLink.apk）

在 T2 基础上再剥 `assets/RokidLink.apk` 6.97 MB → **≈ 29.0 MB（-68.8%）**

置信度：体积高，**可行性中**——会把网络依赖引入「眼镜端安装」这条关键链路，且与 `checkGitClean` 门禁存在既有冲突（见 §6.3）。

---

## 6. 一期（T1）落地步骤

1. **排除 AAR 自带的 3 个模型**
   `phone-app/build.gradle.kts` 的 `androidResources` 增加
   `ignoreAssetsPatterns += "ch_PP-OCRv4_rec_infer.onnx:ch_PP-OCRv4_det_infer.onnx:ch_ppocr_mobile_v2.0_cls_infer.onnx"`
   ⚠️ **需出包验证 aapt2 对「AAR 合并进来的 assets」是否生效**；若无效，退化为在 `mergeReleaseAssets` 后加 `doLast` 过滤任务。

2. **排除 BC PQC 资源**（扩展现有 `packaging {}`，`build.gradle.kts:82-86`）
   ```kotlin
   packaging {
       resources {
           excludes += listOf("org/bouncycastle/pqc/**", "**/lowmc*.bin.properties")
       }
   }
   ```
   代码只用 `BouncyCastleProvider()` 注册 RSA（`LabApplication.kt:127-128,303-310`），PQC 算法零引用。**需单测 + 真机 ADB 授权回归确认无静态初始化依赖。**

3. **新增 `OcrModelStore`**
   - OkHttp 下载 3 个 `.onnx` 到 `filesDir/ocr/`：`.part` 临时文件 → **SHA-256 校验** → 原子 `rename`；SHA-256 常量内嵌 `BuildConfig`。
   - `LocalOcr.kt` 改走 `RapidOCR.create(ctx, OcrConfig().apply { Det.modelPath = ...; Rec.modelPath = ...; Rec.recKeysPath = ...; Cls.modelPath = ... })`。
   - **模型缺失时保持返回 `""`**（现有 `LocalOcr.recognize` 已是软降级，不抛异常），并在 UI 上给出「OCR 组件未就绪，去下载」的引导。

4. **删 `assets/glass_mirror.py`**；字体裁剪后回归 `StoreTheme.kt:93-96` 商店页排版。

5. **验证**：`./gradlew :phone-app:testDebugUnitTest` + 真机回归 RSA/ADB 授权 + 拍照问 AI 全链路。

---

## 7. 二期（T2/T3）要点与风险

### 7.1 模块与 IPC 边界草案

```kotlin
// 主 APK 侧（保留）：唯一入口，替代对 RapidOCR 的直接依赖
interface OcrEngine {
    fun ensureInit(ctx: Context): Boolean
    fun recognize(ctx: Context, bmp: Bitmap): String
}
object OcrClient : OcrEngine { /* 绑定 OCR 组件；缺失 → 返回 "" + OcrUnavailable */ }

// 保留在主 APK：PhotoQuizFlow.kt 调用点、降级 UI、下载器、契约常量
// 下沉到辅助 APK：rapidocr4j / opencv / onnxruntime 依赖、3 个 .onnx、LocalOcr 实现
// 契约：PluginContract { const val MIN_VERSION = 1; const val PKG = "com.rokidlab.phone.ocr" }
```
> 用 `ContentProvider.call()` / `Messenger`（Bundle 传参）而非共享 `.aidl`，避免两包独立更新时的 AIDL 版本耦合。
> 注意 Binder 单事务 ~1 MB 上限：位图请**降采样或落临时文件传 `ParcelFileDescriptor`**。

新建 `ocr-plugin` 模块：`com.android.application`，`applicationId com.rokidlab.phone.ocr`，`abiFilters arm64-v8a`，**复用同一 keystore**；`settings.gradle.kts` `include(":ocr-plugin")`；主 APK 删除 `build.gradle.kts:422-429` 三个依赖。

### 7.2 回归风险清单

| 风险点 | 位置 | 说明 |
|---|---|---|
| 「拍照问 AI」链路 | `PhotoQuizFlow.kt:100-116`（唯一调用点 `:105`） | 未装 OCR 组件时必须不崩、给可理解提示；现有空文本分支（`:111-116` `chat_ocr_empty`）需扩展语义 |
| 眼镜端安装链路 | `RokidLinkController.kt:116-176,240-…`；`GuideScreen` | `RokidLink.apk` 改下载后，首启/引导「先停后卸再装」在弱网/无网会卡；v3.6 靠内置 release 签名包解决签名冲突（`:180-184` 注释），下载版须保证同签名 |
| BouncyCastle PQC 裁剪 | `LabApplication.kt:127-128,303-310`；`AdbKeyManager.kt:19` | 仅注册 Provider 用 RSA；仍需单测 + 真机 ADB 授权回归 |
| 字体裁剪 | `StoreTheme.kt:93-96` | `BrewFont` 需 regular/medium/bold 三字重 |
| 16KB 页对齐 | `build.gradle.kts:80-86` | 移除 opencv/onnxruntime 后剩余 Rokid SDK `.so` 对齐由 SDK 负责；`useLegacyPackaging=false` 保持不变 |
| 双端协议 / i18n / 空 catch 门禁 | `build.gradle.kts:142-377`、`gradle/local-gates.gradle.kts` | 新增 `ocr-plugin` 不在扫描 roots（`phone-app/src/main/java` + `RokidLink`）内，不受影响；但**别把协议文件放进新模块** |

### 7.3 ⚠️ `checkGitClean` 与 `assets/RokidLink.apk` 的既有冲突（已实测确认）

- `git ls-files --error-unmatch phone-app/src/main/assets/RokidLink.apk` **命中** → 该 APK **已被 git 跟踪**；`.gitignore` 只忽略 `*debug.apk`（`:30`），未忽略它。
- 而 `build.gradle.kts:108-134` 的 `buildRokidLinkRelease` 会**覆盖写回** `src/main/assets/RokidLink.apk`，且 `mergeReleaseAssets` 依赖它（`:383-385`）；顺序上 `packageRelease` 才跑 `checkGitClean`（`gradle/local-gates.gradle.kts:15-50`）→ **若重生成的 APK 与已提交字节不同，release 门禁会自己把自己判脏而失败。**
- **T3 剥离 `RokidLink.apk` 时必须同步处理**：把 `buildRokidLink*` 的拷贝目标从 `src/main/assets/` 改到 `build/`（或删除该 copy 步骤），并把产物加入 `.gitignore`；否则冲突会被放大（下载版包体一旦变化即脏树）。**必须实际出一次 release 包验证。**

---

## 8. 待实测确认项（本报告为只读分析，以下均未在设备/出包层面验证）

1. `ignoreAssetsPatterns` 对 **AAR 合并进来的 assets** 的排除是否生效（或改用 merge-assets 过滤任务）。
2. bcprov PQC 资源裁剪后 **RSA / Provider 流程**是否 100% 正常。
3. `OcrConfig.modelPath` 绝对路径在**真机**上的实际加载行为（字节码已证明分支存在）。
4. **dex 侧实际缩减量**（R8 后 opencv/onnxruntime4j/rapidocr4j 归属）——需真出包对比。
5. **(a2) 插件 APK 同进程跨包 `dlopen`** 是否成功（SELinux `app_data_file` + linker namespace）——本报告唯一未验证的关键技术点；建议一期直接用 (a1) 独立进程规避。
6. 剥离 `assets/RokidLink.apk` 后 `buildRokidLink*` 与 `checkGitClean` 的交互。
7. `RokidLink.apk` 下载版签名字节能否被眼镜端接受、`pm install` 行为。

---

## 附：关键证据索引

| 结论 | 证据来源 |
|---|---|
| 体积实测 | `zipfile` 遍历 `RokidLab-v3.6-release.apk`（850 条目）；脚本 `.workbuddy/apk_analyze.py` |
| `.so` 未压缩存储 | `phone-app/build.gradle.kts:82-86` `useLegacyPackaging = false` |
| OCR 孤立性 | `grep hzkitty` → 仅 `LocalOcr.kt`；`grep LocalOcr` → 仅 `PhotoQuizFlow.kt:105` |
| 模型路径可配 | `javap -c` on `io/github/hzkitty/{RapidOCR,entity/OcrConfig*,utils/OrtInferSession}` |
| `libc++_shared.so` 归属 | `DT_NEEDED` 解析 APK 内 9 个 `.so`；脚本 `.workbuddy/elf_dep.py` |
| 字典内嵌 ONNX metadata | `TextRecognizer`/`CTCLabelDecode` 常量 `"character"` |
| W^X | Android 10 behavior changes `#execute-permission` |
| BC PQC 未引用 | `grep -rn "org.bouncycastle"` → 仅 `LabApplication.kt`（classic provider） |
| 字体 | `phone-app/src/main/res/font/jetbrains_mono{,_regular,_medium,_bold}.ttf`；`StoreTheme.kt:93-96` |
| git 跟踪状态 | `git ls-files --error-unmatch phone-app/src/main/assets/RokidLink.apk` 命中 |

---

**文档编写：软件开发团队（架构师高见远 设计 / 交付总监齐活林 汇编） · 2026-09-14**
