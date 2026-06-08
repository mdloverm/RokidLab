plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
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
    namespace = "com.rokidbrew.phone"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.rokidbrew.phone"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        manifestPlaceholders["cleartextTrafficPermitted"] = "false"
    }

    signingConfigs {
        create("release") {
            storeFile = file("D:\\rokidapp\\release.keystore")
            storePassword = "rokid123"
            keyAlias = "rokidbrew"
            keyPassword = "rokid123"
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
            isMinifyEnabled = false
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
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.4"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    
    // 本地 CXR-L SDK (包含所有 SO 文件)
    implementation(files("libs/client-l-1.0.3.aar"))
    implementation(files("libs/cxr-service-bridge-1.0.aar"))
    
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
