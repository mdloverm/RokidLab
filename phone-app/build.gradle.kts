plugins {
    alias(libs.plugins.android.application)
    kotlin("android")
    alias(libs.plugins.kotlin.compose)
}

// ── 发布前闸门（release 洁净工作区 + release 依赖单测），双端共用同一份实现 ──
apply(from = "../gradle/local-gates.gradle.kts")

android {
    namespace = "com.rokidlab.phone"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.rokidlab.phone"
        minSdk = 29
        targetSdk = 34
        versionCode = 24
        versionName = "3.9"
        manifestPlaceholders["cleartextTrafficPermitted"] = "false"

        // 本地 OCR（onnxruntime + opencv）体积较大，只保留主流真机 ABI
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        create("release") {
            storeFile = file("D:\\rokidapp\\release.keystore")
            storePassword = providers.gradleProperty("RELEASE_KEYSTORE_PASSWORD").orElse("rokid123").get()
            keyAlias = providers.gradleProperty("RELEASE_KEY_ALIAS").orElse("rokidbrew").get()
            keyPassword = providers.gradleProperty("RELEASE_KEY_PASSWORD").orElse("rokid123").get()
        }
    }

    buildTypes {
        debug {
            manifestPlaceholders["cleartextTrafficPermitted"] = "true"
        }

        release {
            signingConfig = signingConfigs.getByName("release")
            // R8 混淆压缩 dex，减小 APK 体积（so 必须未压缩以兼容 16KB 设备）
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

    // so 库未压缩存储（16KB 页面设备要求，Android 16 强制）
    // 注意：so 压缩(useLegacyPackaging=true) 会导致 16KB 设备安装失败，必须保持未压缩
    packaging {
        jniLibs {
            useLegacyPackaging = false
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
// 为什么不一次清零存量：存量 47 处多为「关句柄 / 消费收尾包 / 读线程正常退出」，逐一补
// `// catch-ok:` 属纯注释 churn，收益低于回归风险；先把闸门立起来，新增一处即构建失败，
// 存量在后续改动该文件时顺手收敛（预算可随之下调）。
val bareCatchBudget = 47

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

    // 本地 OCR（PP-OCRv4 模型，ONNX Runtime 推理，无需联网/无 GMS 依赖）
    implementation("io.github.hzkitty:rapidocr4j-android:1.0.0") {
        // 覆盖旧版 native 依赖为 16KB 对齐版本（兼容 Android 16 页面大小）
        // opencv 4.9.0 / onnxruntime 1.18.0 的 so 为 4KB 对齐，16KB 设备上 dlopen 会崩溃
        exclude(group = "org.opencv", module = "opencv")
        exclude(group = "com.microsoft.onnxruntime", module = "onnxruntime-android")
    }
    implementation("org.opencv:opencv:4.12.0")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.22.0")

    // ── JVM 单元测试（纯 Kotlin 协议/状态机/裁剪逻辑，无需真机）──
    testImplementation("junit:junit:4.13.2")
    // 真实 org.json 实现：android.jar 中为抛 "not mocked" 的桩，SSE 解析单测需要真实解析
    testImplementation("org.json:json:20231013")
}
