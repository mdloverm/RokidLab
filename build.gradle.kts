// 顶层构建文件：插件版本统一在 gradle/libs.versions.toml 管理。
// 与外层壳工程 D:\rokidapp\build.gradle.kts 保持同源，改动请双改。
plugins {
    alias(libs.plugins.android.application) apply false
    kotlin("android") version libs.versions.kotlin.get() apply false
    alias(libs.plugins.kotlin.compose) apply false
}
