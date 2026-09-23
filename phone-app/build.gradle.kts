plugins {
    alias(libs.plugins.android.application)
    kotlin("android")
    alias(libs.plugins.kotlin.compose)
}

// ── 发布前闸门（release 洁净工作区 + release 依赖单测），双端共用同一份实现 ──
apply(from = "../gradle/local-gates.gradle.kts")

/**
 * 读取一个签名属性；缺失或为空白时返回 null（**不抛异常**）。
 *
 * 为什么不在配置阶段抛：`android { signingConfigs { ... } }` 在**配置阶段无条件执行**，
 * 若在里面直接抛，`compileDebugKotlin` / `installDebug` 这类与签名毫无关系的任务
 * 也会因「本机没配 release 口令」而构建失败 —— 发布闸门被误当成日常开发闸门
 * （2026-09-18 实测：签名治理落地后 Debug 编译直接挂）。
 * 因此「缺什么」的判定与拦截下移到 [releaseSigningGate]，按**实际任务图**精确触发。
 *
 * 与 RokidLink 侧保持逐字一致（两端签名参数必须同源，改动请双改）。
 *
 * @param name     属性名（-P / gradle.properties / ORG_GRADLE_PROJECT_<name> 环境变量均可）
 * @param fallback 可选默认值；**仅限非机密项**（如 keystore 路径），口令类一律不传
 */
fun optionalSigningProperty(name: String, fallback: String? = null): String? =
    providers.gradleProperty(name).orNull?.takeIf { it.isNotBlank() } ?: fallback

/** release keystore 默认路径（非机密项，允许回退；实际位置用 RELEASE_KEYSTORE_PATH 覆盖） */
val defaultReleaseKeystorePath = "D:\\rokidapp\\release.keystore"

/** 当前缺失的 release 签名材料清单（空列表 = 齐备）。供 [releaseSigningGate] 生成报错。 */
fun releaseSigningProblems(): List<String> = buildList {
    val path = optionalSigningProperty("RELEASE_KEYSTORE_PATH", defaultReleaseKeystorePath)!!
    if (!rootProject.file(path).isFile) {
        add("keystore 文件不存在：$path（用 RELEASE_KEYSTORE_PATH 指定实际位置）")
    }
    if (optionalSigningProperty("RELEASE_KEYSTORE_PASSWORD") == null) {
        add("缺少 RELEASE_KEYSTORE_PASSWORD（keystore 口令）")
    }
    if (optionalSigningProperty("RELEASE_KEY_ALIAS") == null) {
        add("缺少 RELEASE_KEY_ALIAS（签名密钥别名）")
    }
    if (optionalSigningProperty("RELEASE_KEY_PASSWORD") == null) {
        add("缺少 RELEASE_KEY_PASSWORD（签名密钥口令）")
    }
}

/**
 * release 签名闸门：仅当任务图里**真的出现 release 打包任务**时才要求签名材料齐备。
 *
 * 判定基于**实际任务图**（而不是任务名字符串猜测），因此 `assemble` / `build` 这类
 * 同时产出 debug 与 release 的聚合任务也会被正确拦住 —— 它们同样会走到 packageRelease。
 * 历史教训：此处曾有 `orElse("rokid123")` 明文回退，会让缺配置的机器静默产出一个
 * 「签名看似正确」的包，事后极难发现来源不对。现在缺材料一律硬失败，且**不做任何回退**。
 *
 * ⚠️ 必须显式写成 `Action { }`（不能写 `whenReady { }`）：`TaskExecutionGraph.whenReady`
 * 有 `Action` 与 Groovy `Closure` 两个重载，Kotlin 的裸 lambda 会解析到 `Closure` 那个
 * （实测报 "Closure<(raw) Any!> was expected"）。而 Kotlin DSL 的 `Action<T> { }` 是
 * **带接收者**的变体（`inline fun <T> Action(block: T.() -> Unit)`），所以块内没有参数、
 * 用 `allTasks` 直接取任务图 —— 写成带参数形式会报 "Expected no parameters"。
 */
gradle.taskGraph.whenReady(
    org.gradle.api.Action<org.gradle.api.execution.TaskExecutionGraph> {
        val packaging = allTasks.filter { t ->
            t.project == project && t.name.contains("Release") &&
                listOf("package", "assemble", "bundle", "install", "publish").any { t.name.startsWith(it) }
        }
        if (packaging.isNotEmpty()) {
            val problems = releaseSigningProblems()
            if (problems.isNotEmpty()) {
                throw GradleException(
                    buildString {
                        append("release 签名材料不齐备，已中止：${packaging.joinToString { it.name }}\n")
                        problems.forEach { append("  ✗ $it\n") }
                        append("为避免静默产出错误来源的包，此处不做任何回退。请任选一种方式提供：\n")
                        append("  ① 在 gradle.properties 中写（该文件已被 .gitignore 排除）\n")
                        append("  ② 在 ~/.gradle/gradle.properties 中写（推荐：跨项目复用且绝不会误提交）\n")
                        append("  ③ 设置环境变量 ORG_GRADLE_PROJECT_<属性名>\n")
                        append("  ④ 命令行传参 -P<属性名>=...\n")
                        append("模板见仓库根 gradle.properties.example。")
                    },
                )
            }
        }
    },
)

android {
    namespace = "com.rokidlab.phone"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.rokidlab.phone"
        minSdk = 29
        targetSdk = 34
        versionCode = 25
        versionName = "4.0"
        manifestPlaceholders["cleartextTrafficPermitted"] = "false"

        // 本地 OCR（onnxruntime + opencv）体积较大，只保留主流真机 ABI
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        create("release") {
            // 签名参数一律显式提供，**缺失时留空**、由上面的 releaseSigningGate 硬失败拦截
            // （此处不得抛异常：本块在配置阶段无条件执行，抛了会误伤 debug 构建，理由见其上注释）。
            val keystorePath = optionalSigningProperty("RELEASE_KEYSTORE_PATH", defaultReleaseKeystorePath)!!
            val ksFile = rootProject.file(keystorePath)
            if (ksFile.isFile) storeFile = ksFile
            storePassword = optionalSigningProperty("RELEASE_KEYSTORE_PASSWORD")
            keyAlias = optionalSigningProperty("RELEASE_KEY_ALIAS")
            keyPassword = optionalSigningProperty("RELEASE_KEY_PASSWORD")
        }
    }

    buildTypes {
        debug {
            manifestPlaceholders["cleartextTrafficPermitted"] = "true"
        }

        release {
            signingConfig = signingConfigs.getByName("release")
            // R8 混淆压缩 dex，减小 APK 体积（so 的压缩策略见下方 packaging 块，别照抄旧结论）
            isMinifyEnabled = true
            // P0-5：release 关闭全局明文。实际策略以 res/xml/network_security_config.xml 为准
            // （声明该文件后本属性在 API 24+ 被忽略），此处保持同值以避免误读。
            manifestPlaceholders["cleartextTrafficPermitted"] = "false"
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
        freeCompilerArgs += "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi"
        freeCompilerArgs += "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // ── JVM 单测：android.jar 桩方法返回默认值，而不是抛 "not mocked" ──
    // 关键链路回归单测（ADB sync 协议 / HID 报表归一化）要直接驱动生产代码，而生产代码里有
    // android.util.Log 调用；不开此项则一碰就抛 RuntimeException("Stub!")。
    // 副作用：Log.getStackTraceString 会返回 null —— LogCollector 的堆栈拼接已做 null 兜底。
    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    // so 库**压缩**存储（⇒ 清单里 extractNativeLibs=true，安装时解压到 nativeLibraryDir）。
    //
    // 为什么必须是 true —— 两件事同时成立才敢改：
    //  1. **自持 proot 的硬需求**：W^X 只允许在 `nativeLibraryDir`（SELinux 类型 `apk_data_file`）
    //     里 `execve`；而 `useLegacyPackaging = false` 时 .so 是**未压缩直载**、系统**根本不落盘** ⇒
    //     nativeLibraryDir 是个空目录，execve 必然 ENOENT。
    //     （2026-09-22 真机实测：装完 APK 后 `libproot.so` 不在位，自检报「二进制不在位」。）
    //  2. **官方 16KB 页面文档明确支持这条路**：「如果无法升级到 AGP 8.5.1+，可以改用**压缩共享库**……
    //     从而避免因共享库未对齐而导致的应用安装问题」。
    //     ⚠️ 原注释写反了：会让 16KB 设备**装不上**的是「未压缩且未做 16KB zip 对齐」，
    //     **不是**压缩 —— 压缩的库安装时被解压，压根不参与 zip 对齐检查。
    // 代价：安装后占用磁盘变大（库被解压落盘，本包 11 个库约 +45 MB）；APK 本身反而变小。
    // 依据：https://developer.android.com/guide/practices/page-sizes
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    // ── APK 输出命名规则 ──
    applicationVariants.all {
        val variant = this
        variant.outputs.all {
            val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            output.outputFileName = "RokidLab-v${variant.versionName}-${variant.buildType.name}.apk"
        }
    }
}

// ── 眼镜端 RokidLink APK 自动构建集成 ──
// 构建 phone-app 时, 先构建 RokidLink 模块, 将输出的 APK 拷贝到 assets
// 这样 installRokidLinkToGlasses() 读取的总是最新版 RokidLink
// 兼容两种构建布局：仓库根（RokidLab/）为 ":RokidLink"；外层壳工程（D:\rokidapp）为 ":cxrl:RokidLab:RokidLink"
val rokidLinkProject by lazy {
    rootProject.findProject(":RokidLink")
        ?: rootProject.findProject(":cxrl:RokidLab:RokidLink")
        ?: error("找不到 RokidLink 模块（既不在 :RokidLink 也不在 :cxrl:RokidLab:RokidLink）")
}

// ── ink 引擎资产：与眼镜端共用同一份 ──
// 手机端宿主（AiuiWebHost）与眼镜端（AiuiLinkActivity）跑的是同一套 assets/ink/
// （index.html / host.js / index.js / env.js / lab-page-bridge.js / pkg/*，其中
// ink_web_bg.wasm 有 20MB）。这里把 RokidLink 的 assets 目录挂成本模块的 srcDir，
// APK 里仍是 assets/ink/... 的路径（两端宿主代码零改动），仓库里则只存一份。
//
// ⚠️ 两条约束：
//   1. ink 资产只改 RokidLink 侧那一份；本模块 assets/ 下**不要**再放 ink/，否则同
//      一路径有两份来源（合并结果取决于顺序，会静默用错版本）。
//   2. RokidLink/src/main/assets 里新增的任何文件都会一并打进手机端 APK（目前该目录
//      只有 ink/）。
android.sourceSets.getByName("main").assets.srcDir(rokidLinkProject.projectDir.resolve("src/main/assets"))

val buildRokidLinkDebug by tasks.registering {
    description = "构建 RokidLink (debug) 并拷贝到 phone-app assets"
    group = "build"
    dependsOn("${rokidLinkProject.path}:assembleDebug")
    doLast {
        val sourceApk = rokidLinkProject.buildDir.resolve("outputs/apk/debug/RokidLink-debug.apk")
        val targetFile = file("src/main/assets/RokidLink.apk")
        sourceApk.copyTo(targetFile, overwrite = true)
        logger.lifecycle("RokidLink APK (debug) 已同步 -> $targetFile")
    }
}

val buildRokidLinkRelease by tasks.registering {
    description = "构建 RokidLink (release) 并拷贝到 phone-app assets"
    group = "build"
    dependsOn("${rokidLinkProject.path}:assembleRelease")
    doLast {
        val releaseDir = rokidLinkProject.buildDir.resolve("outputs/apk/release")
        // 优先取已签名的 APK，失败则取未签名的
        val sourceApk = releaseDir.resolve("RokidLink-release.apk").takeIf { it.exists() }
            ?: releaseDir.resolve("RokidLink-release-unsigned.apk").takeIf { it.exists() }
            ?: error("在 $releaseDir 中未找到 RokidLink-release APK")
        val targetFile = file("src/main/assets/RokidLink.apk")
        sourceApk.copyTo(targetFile, overwrite = true)
        logger.lifecycle("RokidLink APK (release) 已同步 ${sourceApk.name} -> $targetFile")
    }
}

// ── 双端协议同源守护（AiChannel.kt + LinkProtocol.kt）──
// 两个协议文件在 phone-app 与 RokidLink 各持一份同源副本，修改必须双端同步。
// 本任务在 preBuild 时校验：除 package 行与空行外逐字节一致，否则构建失败。
// 同时扫描两模块 src/main，禁止在协议文件之外出现裸协议字面量
// （CXR 频道名 "Ai"/"Sys"/"Wifi"/"Jsai"/"Ai_RenderPayload" 与 __LAB_* 控制帧标记），
// 强制所有协议引用收敛到 LinkProtocol / AiChannel。
val checkProtocolSynced by tasks.registering {
    group = "verification"
    description = "校验 phone-app 与 RokidLink 的 AiChannel.kt / LinkProtocol.kt 同源，并禁止裸协议字面量"
    doLast {
        fun normalized(f: File): String = f.readText()
            .lineSequence()
            .filterNot { it.startsWith("package ") || it.isBlank() }
            .joinToString("\n")

        val protocolFiles = listOf(
            "AiChannel.kt" to "src/main/java/com/rokidlab/phone/glasses/AiChannel.kt",
            "LinkProtocol.kt" to "src/main/java/com/rokidlab/phone/glasses/LinkProtocol.kt",
        )
        for ((name, phoneRel) in protocolFiles) {
            val phone = file(phoneRel)
            val glasses = rokidLinkProject.projectDir.resolve(
                phoneRel.replace("com/rokidlab/phone/glasses", "com/rokidlab/rokidlink"),
            )
            if (normalized(phone) != normalized(glasses)) {
                error(
                    "$name 双端不同源！\n  phone-app: $phone\n  RokidLink: $glasses\n" +
                        "修改协议必须同步修改两端文件（仅 package 行允许不同）。",
                )
            }
            logger.lifecycle("checkProtocolSynced: $name 双端同源校验通过")
        }

        // 裸协议字面量扫描：禁止在协议文件之外直接写 CXR 频道名 / __LAB_* 标记
        val forbiddenChannels = setOf("Ai", "Sys", "Wifi", "Jsai", "Ai_RenderPayload")
        val chanArg = Regex("""(?:sendCustomCmd|sendMessage|rawSendCmd|caps\.write|pushControl|push)\s*\(\s*(?:[^,)]*,\s*)?["']([^"']+)["']""")
        val labMarker = Regex("""__LAB_""")
        val roots = listOf(
            file("src/main/java"),
            rokidLinkProject.projectDir.resolve("src/main/java"),
        )
        val violations = mutableListOf<String>()
        for (root in roots) {
            if (!root.exists()) continue
            root.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .forEach { f ->
                    if (f.name == "LinkProtocol.kt" || f.name == "AiChannel.kt") return@forEach
                    f.useLines { lines ->
                        lines.forEachIndexed { idx, raw ->
                            val trimmed = raw.trimStart()
                            if (trimmed.startsWith("*") || trimmed.startsWith("//") ||
                                trimmed.startsWith("/*") || trimmed.startsWith("*/")
                            ) return@forEachIndexed
                            val code = raw.substringBefore("//")
                            if (labMarker.containsMatchIn(code)) {
                                violations.add("${f.path}:${idx + 1}: 裸 __LAB_ 字面量")
                            }
                            val m = chanArg.find(code)
                            if (m != null && m.groupValues[1] in forbiddenChannels) {
                                violations.add("${f.path}:${idx + 1}: 裸频道字面量 \"${m.groupValues[1]}\"")
                            }
                        }
                    }
                }
        }
        if (violations.isNotEmpty()) {
            error(
                "发现裸协议字面量，请改用 LinkProtocol / AiChannel 常量引用：\n" +
                    violations.joinToString("\n"),
            )
        }
        logger.lifecycle("checkProtocolSynced: 无裸协议字面量")
    }
}
tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(checkProtocolSynced)
}

// ── 多语言 key 集合守护（values/strings.xml ↔ values-en/strings.xml）──
// 规则（RULES「多语言同步」）：新增/修改/删除中文串必须同步英文包。历史上曾出现 5 条
// 中文串缺英文翻译（guide_ready_title / guide_reinstall_link_btn / guide_skip_btn /
// unknown_author / wifi_config_success），只在切英文真机时才暴露。故在 preBuild 强制
// 两个模块的 key 集合完全相等（只比 key，不比顺序与文案）。
val checkI18nKeysSynced by tasks.registering {
    group = "verification"
    description = "校验 phone-app 与 RokidLink 的 values / values-en strings.xml key 集合一致"
    doLast {
        val nameAttr = Regex("""<string\s+name="([^"]+)"""")
        fun keysOf(f: File): Set<String> = nameAttr.findAll(f.readText())
            .map { it.groupValues[1] }
            .toSet()

        val targets = listOf(
            "phone-app" to file("src/main/res"),
            "RokidLink" to rokidLinkProject.projectDir.resolve("src/main/res"),
        )
        for ((module, resDir) in targets) {
            val zh = resDir.resolve("values/strings.xml")
            val en = resDir.resolve("values-en/strings.xml")
            if (!zh.exists() || !en.exists()) {
                error("checkI18nKeysSynced: $module 缺少 strings.xml（$zh / $en）")
            }
            val zhKeys = keysOf(zh)
            val enKeys = keysOf(en)
            val missingEn = (zhKeys - enKeys).sorted()
            val extraEn = (enKeys - zhKeys).sorted()
            if (missingEn.isNotEmpty() || extraEn.isNotEmpty()) {
                error(
                    buildString {
                        append("$module 多语言 key 不一致（values=${zhKeys.size} / values-en=${enKeys.size}）：\n")
                        if (missingEn.isNotEmpty()) {
                            append("  values 有 / values-en 缺（需补英文翻译）: ${missingEn.joinToString(", ")}\n")
                        }
                        if (extraEn.isNotEmpty()) {
                            append("  values-en 有 / values 缺（需删除或补中文）: ${extraEn.joinToString(", ")}\n")
                        }
                        append("  请同步 res/values-en/strings.xml（RULES「多语言同步」）。")
                    },
                )
            }
            logger.lifecycle("checkI18nKeysSynced: $module ${zhKeys.size} 条 key 双语一致")
        }
    }
}
tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(checkI18nKeysSynced)
}

// ── 关键链路「空 catch」守护（RULES §12.14）──
// 异常吞噬是 P1-8 的根因：链路出问题时 App 内日志面板（LogCollector）里什么都看不到，
// 只能靠用户口述现象反推。规则分两层：
//   1. 关键链路（ASR 补读 / RFCOMM 隧道 / ADB sync / AIUI 工具网关）的 catch 必须落 LogCollector；
//   2. 确实无信息量的空 catch（关闭句柄、消费收尾包、读线程正常退出）必须带 `// catch-ok: <原因>` 标注 ——
//      把「默默吞掉」变成「显式声明的决策」，CR 时一眼可查。
// 全局禁止空 catch 由 RULES §12.14 作 CR 约束，并由下方 checkNoBareCatch 以「棘轮预算」机器兜底。
val keyPathFiles = listOf(
    "src/main/java/com/rokidlab/phone/glasses/AsrBridgeCoordinator.kt",
    "src/main/java/com/rokidlab/phone/glasses/AsrPushClient.kt",
    "src/main/java/com/rokidlab/phone/connection/ConnectionRouteManager.kt",
    "src/main/java/com/rokidlab/phone/platform/AdbTransport.kt",
    "src/main/java/com/rokidlab/phone/adb/AdbFileManagerClient.kt",
    "src/main/java/com/rokidlab/phone/ai/ToolGateway.kt",
)

val checkKeyPathEmptyCatch by tasks.registering {
    group = "verification"
    description = "关键链路禁止裸空 catch：空 catch 必须带 // catch-ok: <原因> 豁免说明"
    doLast {
        val emptyCatch = Regex("""catch\s*\([^)]*\)\s*\{\s*\}""")
        val violations = mutableListOf<String>()
        var waived = 0
        for (rel in keyPathFiles) {
            val f = file(rel)
            if (!f.exists()) {
                error("checkKeyPathEmptyCatch: 关键链路文件缺失（路径已变？请同步本任务清单）: $rel")
            }
            f.readLines().forEachIndexed { idx, line ->
                if (emptyCatch.containsMatchIn(line)) {
                    if (line.contains("catch-ok:")) {
                        waived++
                    } else {
                        violations.add("  $rel:${idx + 1}  ${line.trim()}")
                    }
                }
            }
        }
        if (violations.isNotEmpty()) {
            error(
                "关键链路存在未标注的空 catch（异常被静默吞掉）：\n" +
                    violations.joinToString("\n") +
                    "\n处理方式二选一：\n" +
                    "  a) 在 catch 内落 LogCollector（推荐，见 RULES §12.14）；\n" +
                    "  b) 若确无信息量（关闭句柄 / 消费收尾包 / 读线程正常退出），补注释 `// catch-ok: <原因>`。",
            )
        }
        logger.lifecycle("checkKeyPathEmptyCatch: ${keyPathFiles.size} 个关键链路文件，$waived 处空 catch 均已标注豁免理由")
    }
}
tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(checkKeyPathEmptyCatch)
}

// ── 全仓「空 catch」预算守护（RULES §12.14，阶段二 #10）──
// checkKeyPathEmptyCatch 对 6 个关键链路文件零容忍；本任务把扫描扩到双端 src/main 全量，
// 采用「棘轮预算」：未标注的空 catch 数只允许下降、不允许上升。
// 为什么不一次清零存量：存量多为「关句柄 / 消费收尾包 / 读线程正常退出」，逐一补
// `// catch-ok:` 属纯注释 churn，收益低于回归风险；先把闸门立起来，新增一处即构建失败，
// 存量在后续改动该文件时顺手收敛（预算可随之下调）。
// 2026-09-18 收敛：基线 47 → 42（实测全仓 68 处，已标注 26 / 未标注 42），
// 按 RULES §12.14「只降不升」同步下调基线。
val bareCatchBudget = 42

val checkNoBareCatch by tasks.registering {
    group = "verification"
    description = "全仓空 catch 预算守护：未标注的空 catch 不得超过 $bareCatchBudget 处（只降不升）"
    doLast {
        val emptyCatch = Regex("""catch\s*\([^)]*\)\s*\{\s*\}""")
        val targets = listOf(
            "phone-app" to file("src/main/java"),
            "RokidLink" to rokidLinkProject.projectDir.resolve("src/main/java"),
        )
        var total = 0
        var unannotated = 0
        val violations = mutableListOf<String>()
        for ((module, root) in targets) {
            if (!root.exists()) continue
            root.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .forEach { f ->
                    f.useLines { lines ->
                        lines.forEachIndexed { idx, line ->
                            if (emptyCatch.containsMatchIn(line)) {
                                total++
                                if (!line.contains("catch-ok:")) {
                                    unannotated++
                                    violations.add("  $module/${f.relativeTo(root).path}:${idx + 1}  ${line.trim()}")
                                }
                            }
                        }
                    }
                }
        }
        if (unannotated > bareCatchBudget) {
            error(
                "未标注的空 catch 增至 $unannotated 处（预算 $bareCatchBudget），新增静默吞异常不允许：\n" +
                    violations.joinToString("\n") +
                    "\n处理方式二选一：\n" +
                    "  a) 在 catch 内落 LogCollector（推荐，见 RULES §12.14）；\n" +
                    "  b) 若确无信息量（关闭句柄 / 消费收尾包 / 读线程正常退出），补注释 `// catch-ok: <原因>`。\n" +
                    "若确需调整存量基线，请同步修改 phone-app/build.gradle.kts 的 bareCatchBudget。",
            )
        }
        logger.lifecycle(
            "checkNoBareCatch: 全仓空 catch $total 处（已标注 ${total - unannotated} / 未标注 $unannotated，预算 $bareCatchBudget）",
        )
        if (unannotated < bareCatchBudget) {
            logger.lifecycle("checkNoBareCatch: 未标注数已低于预算，可把 bareCatchBudget 下调为 $unannotated")
        }
    }
}
tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(checkNoBareCatch)
}

// 在合并 assets 前先同步 RokidLink APK
tasks.matching { it.name.startsWith("mergeDebug") && it.name.endsWith("Assets") }.configureEach {
    dependsOn(buildRokidLinkDebug)
}
tasks.matching { it.name.startsWith("mergeRelease") && it.name.endsWith("Assets") }.configureEach {
    dependsOn(buildRokidLinkRelease)
}

// ── 依赖 ──

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    // OkHttp：连接池复用 + HTTP/2，替代 HttpURLConnection 每请求重握手
    implementation(libs.okhttp)
    
    // CXR-L SDK 1.1.2 (Maven)
    // 1.1.1 起 client-l 改为瘦 aar：CXRServiceBridge/Caps/CXRSocketProtocol/RLog 拆到独立
    // artifact com.rokid.cxr:cxr-service-bridge，由 client-l 传递依赖引入（so 仍在 bridge 内）。
    implementation("com.rokid.cxr:client-l:1.1.2")
    // ⚠️ 显式锁定 bridge 版本：1.1.2 的 POM 依赖的是「固定时间戳 SNAPSHOT」
    //    cxr-service-bridge:1.0-20260715.121510-107，而该 artifact 的 release 1.0 与它内容并不相同
    //    （1076548 vs 1126241 字节）。此处显式声明同一版本，保证构建可复现；
    //    若将来 Nexus 清理掉该 SNAPSHOT，需改用 release 1.0 并真机回归。
    implementation("com.rokid.cxr:cxr-service-bridge:1.0-20260715.121510-107")
    
    // client-l SDK 内部依赖 Gson，需要显式引入
    implementation("com.google.code.gson:gson:2.10.1")
    
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.material:material")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // 本地 OCR（PP-OCRv4，ONNX Runtime 推理，无需联网/无 GMS 依赖）。
    // 使用剥除 assets 模型的「薄 AAR」（官方 AAR 内置 3 个 onnx 约 15.4MB，会撑大 APK）：
    // 模型首次使用时由 LocalOcr 从 Gitee Release 按需下载（见 LocalOcr.MODELS）。
    // 薄 AAR 仅去掉 assets/*.onnx，classes.jar 与官方 1.0.0 完全一致。
    implementation(files("libs/rapidocr4j-android-1.0.0-thin.aar"))
    // 覆盖旧版 native 依赖为 16KB 对齐版本（兼容 Android 16 页面大小）
    // opencv 4.9.0 / onnxruntime 1.18.0 的 so 为 4KB 对齐，16KB 设备上 dlopen 会崩溃
    implementation("org.opencv:opencv:4.12.0")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.22.0")

    // ── JVM 单元测试（纯 Kotlin 协议/状态机/裁剪逻辑，无需真机）──
    testImplementation("junit:junit:4.13.2")
    // 真实 org.json 实现：android.jar 中为抛 "not mocked" 的桩，SSE 解析单测需要真实解析
    testImplementation("org.json:json:20231013")
}
