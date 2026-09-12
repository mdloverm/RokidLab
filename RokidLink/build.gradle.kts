plugins {
    alias(libs.plugins.android.application)
    kotlin("android")
}

// ── 发布前闸门（release 洁净工作区 + release 依赖单测），与 phone-app 共用同一份实现 ──
apply(from = "../gradle/local-gates.gradle.kts")

android {
    namespace = "com.rokidlab.rokidlink"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.rokidlab.rokidlink"
        minSdk = 28
        targetSdk = 34
        versionCode = 14
        versionName = "3.6"
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
