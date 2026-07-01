plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.rokidlab.rokidlink"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.rokidlab.rokidlink"
        minSdk = 28
        targetSdk = 34
        versionCode = 3
        versionName = "1.7"
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
            isMinifyEnabled = false
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
}

// RokidLink 不作为独立应用安装到手机，而是打包进 phone-app assets
// 因此禁用所有 install 相关任务
tasks.whenTaskAdded {
    if (name.startsWith("install")) {
        enabled = false
    }
}

dependencies {
    implementation(files("libs/cxr-service-bridge-1.0.aar"))
}
