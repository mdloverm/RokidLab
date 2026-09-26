// ── RokidLab 独立构建入口 ──
// 本仓库有两种构建布局：
//   ① 外层壳工程（D:\rokidapp）：include(":cxrl:RokidLab:phone-app")，本文件不会被读取；
//   ② 独立 checkout（GitHub Actions / 克隆本仓库）：以本文件为根，模块路径为 ":phone-app" / ":RokidLink"。
// 两种布局共享同一套模块配置：模块脚本里的 apply(from = "../gradle/local-gates.gradle.kts")
// 与签名闸门对两种布局等价生效；phone-app 对 RokidLink 的引用已兼容两种路径
// （rootProject.findProject(":RokidLink") ?: findProject(":cxrl:RokidLab:RokidLink")）。
//
// ⚠️ 仓库根的 gradle/libs.versions.toml 是外层壳 D:\rokidapp\gradle\libs.versions.toml 的同源副本，
//    升级 AGP / Kotlin / 依赖版本时请双改，保持两端一致。
pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Rokid CXR SDK 仓库
        maven {
            url = uri("https://maven.rokid.com/repository/maven-public/")
        }
    }
}

rootProject.name = "RokidLab"
include(":phone-app")
include(":RokidLink")
