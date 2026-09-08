plugins {
    alias(libs.plugins.android.application)
    kotlin("android")
    alias(libs.plugins.kotlin.compose)
}

fun String.asBuildConfigString(): String = replace("\\", "\\\\").replace("\"", "\\\"")

val defaultRegistryUrl = "https://gitee.com/dlover1314/RokidBrew-Registry/raw/main/dist/apps.v1.json"
val registryUrl = providers.gradleProperty("rokidbrewRegistryUrl")
val debugRegistryUrl = providers.gradleProperty("rokidbrewDebugRegistryUrl")
    .orElse(registryUrl)
    .orElse(defaultRegistryUrl)
    .get()
val releaseRegistryUrl = providers.gradleProperty("rokidbrewReleaseRegistryUrl")
    .orElse(registryUrl)
    .orElse(defaultRegistryUrl)
    .get()

android {
    namespace = "com.rokidlab.phone"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.rokidlab.phone"
        minSdk = 29
        targetSdk = 34
        versionCode = 19
        versionName = "3.4"
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
            buildConfigField("String", "ROKIDBREW_REGISTRY_URL", "\"${debugRegistryUrl.asBuildConfigString()}\"")
            manifestPlaceholders["cleartextTrafficPermitted"] = "true"
        }

        release {
            signingConfig = signingConfigs.getByName("release")
            buildConfigField("String", "ROKIDBREW_REGISTRY_URL", "\"${releaseRegistryUrl.asBuildConfigString()}\"")
            // R8 混淆压缩 dex，减小 APK 体积（so 必须未压缩以兼容 16KB 设备）
            isMinifyEnabled = true
            manifestPlaceholders["cleartextTrafficPermitted"] = "true"
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
val rokidLinkProject by lazy { rootProject.project(":cxrl:RokidLab:RokidLink") }

val buildRokidLinkDebug by tasks.registering {
    description = "构建 RokidLink (debug) 并拷贝到 phone-app assets"
    group = "build"
    dependsOn(":cxrl:RokidLab:RokidLink:assembleDebug")
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
    dependsOn(":cxrl:RokidLab:RokidLink:assembleRelease")
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

// ── 双端 AiChannel 协议同源守护 ──
// AiChannel.kt（配置通道协议）在 phone-app 与 RokidLink 各持一份同源副本，
// 修改必须双端同步（否则接收端按错位偏移解析，这正是协议版本化要防的问题）。
// 本任务在 preBuild 时校验：除 package 行与空行外必须逐字节一致，不一致直接构建失败。
val checkAiChannelSynced by tasks.registering {
    group = "verification"
    description = "校验 phone-app 与 RokidLink 的 AiChannel.kt 同源（仅允许 package 行不同）"
    doLast {
        fun normalized(f: File): String = f.readText()
            .lineSequence()
            .filterNot { it.startsWith("package ") || it.isBlank() }
            .joinToString("\n")
        val phone = file("src/main/java/com/rokidlab/phone/glasses/AiChannel.kt")
        val glasses = rokidLinkProject.projectDir.resolve(
            "src/main/java/com/rokidlab/rokidlink/AiChannel.kt",
        )
        val p = normalized(phone)
        val g = normalized(glasses)
        if (p != g) {
            error(
                "AiChannel 双端不同源！\n  phone-app: $phone\n  RokidLink: $glasses\n" +
                    "修改协议必须同步修改两端文件（仅 package 行允许不同），并同步升 SCHEMA_VERSION。",
            )
        }
        logger.lifecycle("checkAiChannelSynced: AiChannel 双端同源校验通过")
    }
}
tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(checkAiChannelSynced)
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
    
    // CXR-L SDK v1.1.0 (Maven, 自带 16KB 对齐的 jni so, 兼容 Android 16)
    // 1.1.0 内置 CXRServiceBridge/Caps/CXRSocketProtocol, 不再需要单独的 cxr-service-bridge
    implementation("com.rokid.cxr:client-l:1.1.0")
    // 移除本地旧版 AAR (client-l-1.0.3 纯 Java; cxr-service-bridge-1.0 的 so 为 4KB 对齐, 不兼容 16KB 设备)
    
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
