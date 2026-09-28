plugins {
    alias(libs.plugins.android.application)
    kotlin("android")
}

// ── 发布前闸门（release 洁净工作区 + release 依赖单测），与 phone-app 共用同一份实现 ──
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
 * 与 phone-app/build.gradle.kts 中的同名函数逐字一致（两端签名参数必须同源，改动请双改）。
 * 说明：`apply(from=...)` 是独立脚本作用域，顶层声明不跨脚本可见，故各模块自带一份。
 *
 * @param name     属性名（-P / gradle.properties / ORG_GRADLE_PROJECT_<name> 环境变量均可）
 * @param fallback 可选默认值；**仅限非机密项**（如 keystore 路径），口令类一律不传
 */
fun optionalSigningProperty(name: String, fallback: String? = null): String? =
    providers.gradleProperty(name).orNull?.takeIf { it.isNotBlank() } ?: fallback

/**
 * 解析 keystore 文件：绝对路径直接用；相对路径相对根工程解析。
 * 刻意不走 rootProject.file()：在 Linux/CI 上遇到 Windows 盘符路径（默认值
 * D:\rokidapp\release.keystore）会被 Gradle 当成 URI 解析而抛 URISyntaxException
 * （2026-09-26 GitHub Actions 实测），java.io.File 构造则跨平台安全。
 * 与 phone-app/build.gradle.kts 中的同名函数逐字一致（改动请双改）。
 */
fun resolveKeystore(path: String): File =
    File(path).let { if (it.isAbsolute) it else File(rootProject.projectDir, path) }

/** release keystore 默认路径（非机密项，允许回退；实际位置用 RELEASE_KEYSTORE_PATH 覆盖） */
val defaultReleaseKeystorePath = "D:\\rokidapp\\release.keystore"

/** 当前缺失的 release 签名材料清单（空列表 = 齐备）。供 [releaseSigningGate] 生成报错。 */
fun releaseSigningProblems(): List<String> = buildList {
    val path = optionalSigningProperty("RELEASE_KEYSTORE_PATH", defaultReleaseKeystorePath)!!
    if (!resolveKeystore(path).isFile) {
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
    namespace = "com.rokidlab.rokidlink"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.rokidlab.rokidlink"
        minSdk = 28
        targetSdk = 34
        versionCode = 20
        versionName = "4.2"
    }

    signingConfigs {
        create("release") {
            // 与 phone-app 同源：**缺失时留空**，由上面的 releaseSigningGate 硬失败拦截
            // （此处不得抛异常：本块在配置阶段无条件执行，抛了会误伤 debug 构建，理由见其上注释）。
            // ⚠️ 两端必须用同一个 keystore / alias，否则眼镜端 APK 无法随 phone-app 一同安装。
            val keystorePath = optionalSigningProperty("RELEASE_KEYSTORE_PATH", defaultReleaseKeystorePath)!!
            val ksFile = resolveKeystore(keystorePath)
            if (ksFile.isFile) storeFile = ksFile
            storePassword = optionalSigningProperty("RELEASE_KEYSTORE_PASSWORD")
            keyAlias = optionalSigningProperty("RELEASE_KEY_ALIAS")
            keyPassword = optionalSigningProperty("RELEASE_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            // 开启 R8：APK 13.8MB → 9.1MB。
            // proguard-rules.pro 已 -keep com.rokid.cxr.**（CXR SDK 内部有 JNI 反射调用，
            // 裁剪会直接崩），眼镜端自身无反射/无自写 JNI，可安全混淆。
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }

    // JVM 单测：android 桩 API 默认返回默认值而非抛 "not mocked"，
    // 保证未来眼镜端纯逻辑（AiChannel 编解码等）可无真机直接跑测试
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

// RokidLink 不作为独立应用安装到手机，而是打包进 phone-app assets
// 因此禁用所有 install 相关任务
tasks.whenTaskAdded {
    if (name.startsWith("install")) {
        enabled = false
    }
}

dependencies {
    // CXR-L 眼镜端桥接：Maven 正式版（com.rokid.cxr:cxr-service-bridge:1.0，2026-07-28 发布）。
    // 已验证与原先本地 libs/cxr-service-bridge-1.0.aar **字节完全一致**（16 个类含 ReplyImpl + 10 个 so），
    // 换 Maven 依赖后可去掉 1MB 二进制入库，版本也能随官方升级。
    //
    // ⚠️ 眼镜端只用 bridge，绝不能改用 client-l：client-l 1.1.0 的 fat aar 漏打了 ReplyImpl 类，
    //    其 native so 在 JNI 初始化时必须反射加载该类，缺失会直接 SIGABRT
    //    （2026-09-08 实测：换 Maven client-l:1.1.0 后 KeyButtonService.initCxrBridge 启动即崩）。
    //    1.1.1 起官方把 bridge 拆成独立 artifact，正是修复了这个打包缺陷。
    implementation("com.rokid.cxr:cxr-service-bridge:1.0")

    // ── JVM 单元测试 ──
    testImplementation("junit:junit:4.13.2")
}
