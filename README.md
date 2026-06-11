# RokidLab

Rokid 眼镜配套手机应用，提供应用商店、屏幕镜像、手机投屏、文件管理等功能。

[![Android](https://img.shields.io/badge/Android-3DDC84?logo=android)](https://github.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0-7F52FF?logo=kotlin)](https://kotlinlang.org/)
[![Compose](https://img.shields.io/badge/Jetpack_Compose-Material_3-4285F4?logo=jetpackcompose)](https://developer.android.com/compose)
[![Gitee](https://img.shields.io/badge/Gitee-dlover1314-C71D23?logo=gitee)](https://gitee.com/dlover1314/RokidLab)

## 主要功能

### 应用商店
- 浏览和搜索 Rokid 眼镜应用
- 安装应用到手机或眼镜端
- 应用更新管理
- 多商店源切换

### 屏幕镜像
- 将眼镜屏幕实时显示在手机上（ADB 连接）
- 支持缩放、平移查看
- 自动开启眼镜端 ADB TCP 模式

### 手机投屏
- 将手机屏幕投射到眼镜上（基于 CXR-L + MediaProjection）
- 一键启动/停止，眼镜端自动接收
- CXR-L 远程启动眼镜端应用
- 投屏后眼镜端全屏显示画面，无 UI 干扰

### 文件管理
- 通过 ADB 浏览和管理眼镜上的文件
- 上传、下载、删除、重命名文件
- 新建文件夹、复制/剪切/粘贴
- 支持图片预览和文本查看
- APK 安装功能

## 技术栈

- **语言**: Kotlin
- **UI**: Jetpack Compose (Material 3) + Neo Brutalism 设计风格
- **通信**: CXR-L/CXR-S SDK（手机-眼镜通信）、ADB over TCP
- **投屏**: MediaProjection API + Socket 传输
- **最低版本**: Android 9 (API 28)

## 项目结构

```
RokidLab/
├── phone-app/                             手机端应用
│   ├── src/main/
│   │   ├── java/com/rokidlab/phone/         主要代码
│   │   │   ├── MainActivity.kt              主入口、投屏控制
│   │   │   ├── StoreHomeScreen.kt           主界面（Compose）
│   │   │   ├── ScreenMirrorActivity.kt      屏幕镜像画面
│   │   │   ├── PhoneMirrorActivity.kt       手机投屏页面
│   │   │   ├── PhoneMirrorService.kt        投屏前台 Service
│   │   │   ├── FileManagerActivity.kt       文件管理器
│   │   │   ├── CxrLHiRokidSession.kt        CXR-L 会话封装
│   │   │   ├── BrewModels.kt                数据结构
│   │   │   ├── BrewApplication.kt           全局状态
│   │   │   ├── StoreTheme.kt                配色/主题
│   │   │   ├── StoreChrome.kt               通用 UI 组件
│   │   │   ├── ConnectionPanel.kt           连接状态面板
│   │   │   ├── GuideScreen.kt               引导界面
│   │   │   ├── SettingsScreen.kt            设置页
│   │   │   ├── SystemLogDock.kt             日志面板
│   │   │   └── AppDetailScreen.kt           应用详情
│   │   ├── res/                             资源文件
│   │   └── assets/                          内置眼镜端 APK
│   └── build.gradle.kts
│
├── glasses-screen-service/                眼镜端屏幕服务
│   ├── src/main/
│   │   ├── java/com/rokidlab/screenservice/
│   │   │   ├── MainActivity.kt              WiFi/ADB 状态面板
│   │   │   ├── PhoneMirrorActivity.kt       投屏接收画面
│   │   │   └── PhoneMirrorServer.kt         Socket 服务端
│   │   ├── res/
│   │   │   └── layout/activity_main.xml     状态面板布局
│   │   └── AndroidManifest.xml
│   └── build.gradle.kts
│
├── UI-DESIGN.md                            UI 设计参考文档
└── apps/                                   应用数据示例
```

## 构建

```powershell
# 构建手机应用
.\gradlew :phone-app:assembleDebug

# 构建眼镜端服务
.\gradlew :glasses-screen-service:assembleDebug
```

## 安装

### 手机应用
```powershell
adb install phone-app/build/outputs/apk/debug/phone-app-debug.apk
```

### 眼镜端服务
通过手机应用中的安装功能自动推送到眼镜，或手动安装：
```powershell
adb install glasses-screen-service/build/outputs/apk/debug/glasses-screen-service-debug.apk
```

## 依赖

- **CXR-L SDK** (`client-l-1.0.3.aar`) — 手机端与眼镜通信
- **CXR-S SDK** (`cxr-service-bridge-1.0.aar`) — 眼镜端桥接服务
- Jetpack Compose — 现代 UI 框架
- Material 3 — 设计系统

## UI 设计

详见 [UI-DESIGN.md](./UI-DESIGN.md)

设计风格：Neo Brutalism（粗边框、高对比度、无圆角）
配色：60% 深邃午夜蓝 + 30% 柔和燕麦白 + 9% 珊瑚橘 + 1% 薄荷绿

## 许可

MIT License

## 作者

**DLOVER**
