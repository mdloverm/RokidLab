# RokidLab

Rokid 眼镜配套手机应用，提供屏幕镜像、文件管理、应用安装等功能。

[![Platform](https://img.shields.io/badge/Android-3DDC84?logo=android)](https://github.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0-7F52FF?logo=kotlin)](https://kotlinlang.org/)
[![Compose](https://img.shields.io/badge/Jetpack_Compose-Material_3-4285F4?logo=jetpackcompose)](https://developer.android.com/compose)

## 主要功能

### 屏幕镜像
- **眼镜镜像** - 将眼镜屏幕实时显示在手机上
- **手机投屏** - 将手机屏幕投射到眼镜上

### 文件管理
- 通过 ADB 连接眼镜
- 浏览和管理眼镜上的文件
- 上传和下载文件
- APK 安装功能

### 应用安装
- 安装应用到眼镜端
- 通过 CXR-L 协议与眼镜通信

## 技术栈

- **语言**: Kotlin
- **UI**: Jetpack Compose (Material 3)
- **通信**: CXR-L SDK
- **最低版本**: Android 9 (API 28)

## 项目结构

```
RokidLab/
├── phone-app/              手机端应用
│   ├── src/main/
│   │   ├── java/com/rokidlab/phone/    主要代码
│   │   ├── res/                         资源文件
│   │   └── assets/                      静态资源
│   └── build.gradle.kts
│
├── glasses-screen-service/ 眼镜端屏幕服务
│   ├── src/main/
│   │   ├── java/com/rokidlab/screenservice/
│   │   ├── res/
│   │   └── AndroidManifest.xml
│   └── build.gradle.kts
│
└── apps/                   应用数据示例
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

- CXR-L SDK (client-l) - 与 Rokid 眼镜通信
- Jetpack Compose - 现代 UI 框架
- Material 3 - 设计系统
