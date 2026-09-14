plugins {
    alias(libs.plugins.android.application) apply false
    kotlin("android") version libs.versions.kotlin.get() apply false
    alias(libs.plugins.kotlin.compose) apply false
}
