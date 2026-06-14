# RokidLab

Rokid 眼镜配套手机应用，提供应用商店、蓝牙手柄、ADB工具、屏幕镜像、手机投屏、文件管理等功能。

[![Android](https://img.shields.io/badge/Android-9%2B-3DDC84?logo=android)](https://github.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0-7F52FF?logo=kotlin)](https://kotlinlang.org/)
[![Compose](https://img.shields.io/badge/Jetpack_Compose-Material_3-4285F4?logo=jetpackcompose)](https://developer.android.com/compose)
[![Gitee](https://img.shields.io/badge/Gitee-dlover1314-C71D23?logo=gitee)](https://gitee.com/dlover1314/RokidLab)

## 主要功能

### 蓝牙手柄
- 通过蓝牙 HID 协议将手机模拟为键盘/鼠标/游戏手柄
- **鼠标模式**：手机屏幕作为触控板，控制眼镜光标
- **游戏手柄模式**：多点触控模拟摇杆 + ABXY 按键
- 智能重连：检测到快速断连时自动等待重试（3s→6s→9s→...→15s 封顶）
- RFCOMM SPP 连接（兼容 RetroArch 蓝牙手柄）
- 自动扫描并列出已配对的蓝牙设备

### ADB 工具
- 通过 WiFi 直接连接眼镜 ADB（无需 adb.exe），自实现完整 ADB TCP 协议
- **应用管理**：
  - 查看第三方/全部应用列表（可搜索过滤）
  - 启动应用、卸载应用、冻结/解冻应用
  - 提取应用 APK 到手机
- **定时功能**：
  - 定时消息：设定间隔和次数，通过 ADB 推送通知到眼镜（`cmd notification post` + 文件写入）
  - 定时打开应用：周期性在眼镜上启动指定应用
- **系统信息**：查看眼镜设备属性、电量信息
- **输入模拟**：发送文本、按键、点击、滑动事件

### 应用商店
- 浏览和搜索 Rokid 眼镜应用
- 安装应用到手机或眼镜端
- 应用更新管理
- 多商店源切换

### 屏幕镜像
- 将眼镜屏幕实时显示在手机上（ADB over TCP 自定义实现）
- 支持缩放、双指平移查看
- 自动开启眼镜端 ADB TCP 模式

### 手机投屏
- 将手机屏幕投射到眼镜上（基于 CXR-L + MediaProjection）
- 一键启动/停止，眼镜端自动接收
- 投屏后眼镜端全屏显示画面，无 UI 干扰

### 文件管理
- 通过 ADB 浏览和管理眼镜上的文件
- 上传、下载、删除、重命名文件
- 新建文件夹、复制/剪切/粘贴
- 支持图片预览和文本查看（shell cat / 本地缓存）
- APK 安装功能

### 设置
- 眼镜端 ScreenStream 服务管理（安装/重装/启动/停止）
- 商店源切换
- 主机应用切换
- 应用版本和更新

## 技术栈

- **语言**: Kotlin
- **UI**: Jetpack Compose (Material 3)
- **设计风格**: Velvet Dark — 丝绒暗调 × 油画色板（详见 [UI-DESIGN.md](./UI-DESIGN.md)）
- **通信**:
  - CXR-L SDK（手机-眼镜通信，用于安装/启动/卸载应用）
  - ADB over TCP（自定义协议实现，无需 adb.exe，用于文件管理、ADB工具和屏幕镜像）
  - 蓝牙 HID Device 协议（手机模拟键盘/鼠标/游戏手柄）
- **投屏**: MediaProjection API + Socket 传输
- **统一 HTTP 工具**: `HttpClient` 对象封装（替代裸 `HttpURLConnection`）
- **最低版本**: Android 9 (API 28)

## ADB 自实现协议

项目从零实现了 ADB TCP 通信协议，不依赖 adb.exe，可直接通过 WiFi 连接眼镜：

- **TCP Socket 连接**: 连接眼镜的 ADB 端口（默认 5555）
- **RSA 密钥认证**: 生成 RSA 密钥对，通过 AUTH/SIGNATURE/RSA_PUBLIC 消息完成 ADB 认证握手
- **shell service**: 通过 `"shell:<command>\u0000"` 格式执行远程 shell 命令
- **sync 协议**: RECV/SEND/DATA/DONE/FAIL 命令实现文件上传下载
- **流关闭处理**: 所有命令都正确消费服务端的 CLSE 响应，避免协议状态不同步
- **ReentrantLock 保护**: 多协程并发执行 shell 命令时串行化，避免 socket 冲突

## 蓝牙 HID 自实现协议

项目使用 Android `BluetoothHidDevice` API 将手机模拟为蓝牙 HID 设备：

- **HID 描述符**: 自定义报表描述符，支持键盘、鼠标、游戏手柄多模式
- **自动重连**: 检测到 HID 连接秒断时，智能等待递增间隔后自动重试
- **RGB 报表**: 鼠标模式使用增强 RGB 报表（模拟 16 位鼠标 + 滚轮 + 多媒体按键）
- **游戏手柄**: 多点触控映射为摇杆值，触摸区域划分 ABXY 按键

## 项目结构

```
RokidLab/
├── phone-app/                             手机端应用
│   ├── src/main/
│   │   ├── java/com/rokidlab/phone/
│   │   │   ├── app/         主入口
│   │   │   │   ├── MainActivity.kt        主入口、投屏控制、状态管理、权限请求
│   │   │   │   └── LabApplication.kt     全局 Application 状态、HID Manager
│   │   │   ├── adb/         ADB 协议实现
│   │   │   │   ├── AdbShellClient.kt      Shell 命令/应用管理/定时功能
│   │   │   │   ├── AdbFileManagerClient.kt   文件管理 ADB 客户端
│   │   │   │   └── AdbScreenMirrorClient.kt  屏幕镜像 ADB 客户端
│   │   │   ├── design/      设计系统
│   │   │   │   ├── StoreTheme.kt      配色/字体/主题（Velvet Dark）
│   │   │   │   └── RokidHostApp.kt    HostApp 枚举
│   │   │   ├── filemanager/  文件管理
│   │   │   │   └── FileManagerActivity.kt  文件管理器界面/组件
│   │   │   ├── glasses/     眼镜通信/UI
│   │   │   │   ├── CxrLHiRokidSession.kt        CXR-L 会话封装
│   │   │   │   ├── ConnectionPanel.kt           连接状态面板
│   │   │   │   ├── GuideScreen.kt               引导界面
│   │   │   │   ├── FullCXRLinkCallback.kt       CXR-L 连接回调
│   │   │   │   └── PhoneInstallResultReceiver.kt 安装结果接收器
│   │   │   ├── hid/         蓝牙 HID
│   │   │   │   ├── BluetoothHidManager.kt   HID 设备管理（注册/连接/报表发送）
│   │   │   │   ├── HidGamepadScreen.kt      手柄/鼠标模式 UI
│   │   │   │   └── GamepadActivity.kt       游戏手柄 Activity
│   │   │   ├── mirror/      投屏模块
│   │   │   │   ├── PhoneMirrorActivity.kt     手机投屏页面
│   │   │   │   ├── PhoneMirrorService.kt      投屏前台 Service
│   │   │   │   ├── PhonePackageInstallHelper.kt APK 安装工具
│   │   │   │   └── ScreenMirrorActivity.kt    屏幕镜像画面
│   │   │   ├── model/       数据模型
│   │   │   │   ├── Models.kt     应用/商店/更新数据模型
│   │   │   │   └── UserInstallCache.kt 安装记录缓存
│   │   │   ├── network/     网络层
│   │   │   │   ├── ApkDownloader.kt   APK 下载（基于 HttpClient）
│   │   │   │   ├── IconLoader.kt      图标加载（基于 HttpClient）
│   │   │   │   └── MediaLoader.kt     媒体加载（基于 HttpClient）
│   │   │   ├── settings/    设置页
│   │   │   │   ├── SettingsScreen.kt   设置界面
│   │   │   │   └── SystemLogPanel.kt   日志面板
│   │   │   ├── store/       商店 UI
│   │   │   │   ├── StoreHomeScreen.kt    主界面入口（含 ADB工具/底栏等全部页面）
│   │   │   │   ├── StoreComponents.kt    通用 UI 组件
│   │   │   │   ├── StoreInstallState.kt  安装状态组件
│   │   │   │   ├── StoreMedia.kt         媒体/图标组件
│   │   │   │   ├── StoreTargetTags.kt    安装目标标签
│   │   │   │   ├── AppDetailScreen.kt    应用详情
│   │   │   │   ├── DetailInfoSections.kt 详情信息块
│   │   │   │   ├── DetailScreenshots.kt  详情截图
│   │   │   │   ├── DetailTargetTags.kt   详情目标标签
│   │   │   │   └── UpdateDialog.kt       更新对话框
│   │   │   └── util/        工具
│   │   │       ├── HttpClient.kt      统一 HTTP 请求工具
│   │   │       └── ImageDecoder.kt     图片解码工具
│   │   ├── res/             资源文件
│   │   └── assets/          内置眼镜端 APK
│   └── build.gradle.kts
│
├── glasses-screen-service/                眼镜端屏幕服务
│   ├── src/main/
│   │   ├── java/com/rokidlab/screenservice/
│   │   │   ├── MainActivity.kt              WiFi/ADB 状态面板
│   │   │   ├── PhoneMirrorActivity.kt       投屏接收画面
│   │   │   └── PhoneMirrorServer.kt         Socket 服务端
│   │   ├── res/layout/activity_main.xml     状态面板布局
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

## 使用指南

1. 确保眼镜已连接到与手机相同的 WiFi 网络
2. 在手机端打开 RokidLab，按照引导授权并连接眼镜
3. 导航栏分为七个模块：
   - **应用商店**：浏览并安装应用到手机或眼镜
   - **屏幕镜像**：查看眼镜屏幕画面
   - **手机投屏**：将手机画面投射到眼镜
   - **文件管理**：浏览和管理眼镜文件
   - **ADB 工具**：应用管理、定时消息/启动、系统信息
   - **蓝牙手柄**：鼠标/游戏手柄模式控制眼镜
   - **设置**：应用配置、服务管理

## 依赖

- **CXR-L SDK** (`client-l-1.0.3.aar`) — 手机端与眼镜通信
- **CXR-S SDK** (`cxr-service-bridge-1.0.aar`) — 眼镜端桥接服务
- Jetpack Compose (Material 3) — 现代 UI 框架
- 蓝牙 HID Device Profile — 系统 API（Android 9+）

## UI 设计

详见 [UI-DESIGN.md](./UI-DESIGN.md)

设计风格：**Velvet Dark**（丝绒暗调 × 油画色板）
- 暗色画布温暖不刺眼（`#0B0B0E` 底色）
- 全站 JetBrains Mono 等宽字体
- 统一 12dp 圆角、1dp 边框
- 七模块七色取自油画色板

## 许可

MIT License

## 作者

**DLOVER**
