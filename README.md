# RokidLab

Rokid 眼镜配套手机应用，提供应用商店、蓝牙手柄、ADB工具、屏幕镜像、手机投屏、文件管理等功能。

> 设计师请参考 [UI-DESIGN.md](./UI-DESIGN.md)，开发者请参考 [DEV_GUIDE.md](./DEV_GUIDE.md)。

> ⚡ 爱发电赞助主页：[https://ifdian.net/a/rokidlab](https://ifdian.net/a/rokidlab)

> 🏪 商店 GitHub 源项目地址：[https://github.com/Anezium/RokidBrew](https://github.com/Anezium/RokidBrew)

## 主要功能

### 蓝牙手柄
- 通过蓝牙 HID 协议将手机模拟为键盘/鼠标/游戏手柄
- **鼠标模式**：手机屏幕作为触控板，控制眼镜光标
- **按键模式**：14 个可自定义位置的按键（↑↓←→ + A/B/C/X/Y/Z + L/R + Select/Start）
- 按键通过双 HID 通道发送：
  - **Consumer Control**：↑↓←→ / Select / Start → 眼镜端 `KEYCODE_DPAD_*` / `KEYCODE_DPAD_CENTER`
  - **Keyboard**：A/B/C/X/Y/Z/L/R → 眼镜端 `KEYCODE_Z/X/C/A/S/D/Q/W`
- 智能重连：检测到快速断连时自动等待重试（3s→6s→9s→12s→15s），5 次上限后停止并提示用户重启眼镜蓝牙
- 安全退出：退出 App 时先 `disconnect()` 再 `unregisterApp()`，确保眼镜 HID Host 正确清理状态
- 自动扫描并列出已配对的蓝牙设备
- 支持按键位置自定义（拖拽调整布局）

### ADB 工具
- 通过 WiFi 直接连接眼镜 ADB（无需 adb.exe），自实现完整 ADB TCP 协议
- **四功能按钮**：使用非当前 ADB 导航栏色的其他四个导航栏色（Coral/Cyan/Purple/Amber），分别对应系统信息/应用管理/定时功能/Shell 命令
- **应用管理**：
  - 查看第三方/全部应用列表（可搜索过滤）
  - 启动应用、卸载应用、冻结/解冻应用
  - 提取应用 APK 到手机
  - 四个快捷按钮使用非标题导航栏色（Coral/Purple/Amber/Pink/Teal）
- **定时功能**：
  - 定时消息：设定间隔和次数，通过 ADB 推送通知到眼镜（`cmd notification post` + 文件写入）
  - 定时打开应用：周期性在眼镜上启动指定应用
- **系统信息**：查看眼镜设备属性、电量信息，三大块（设备/存储/电池）使用不同导航栏色区分
- **Shell 命令**：Shell> 前缀和回车键使用非标题色
- **输入模拟**：发送文本、按键、点击、滑动事件
- **资源管理优化**：修复 ADB Shell Client 的流泄漏问题，确保连接断开时正确释放所有资源
- **统一对话框样式**：所有 ADB 工具弹窗（系统信息/定时器/Shell/应用管理）均使用 BrewDialog RokidLink 卡片样式

### 应用商店
- 浏览和搜索 Rokid 眼镜应用
- 安装应用到手机或眼镜端
- 应用更新管理
- 多商店源切换（Gitee / GitHub，含对应图标标识）
- 应用详情页来源行动态显示 Gitee 或 GitHub 图标及域名
- **本地 APK 安装**：支持从文件管理器选择本地 APK 文件安装到眼镜
- **安装状态优化**：改进安装状态显示和错误处理，提供更清晰的安装反馈

### 屏幕镜像
- 将眼镜屏幕实时显示在手机上（ADB over TCP 自定义实现）
- 基于 scrcpy-server 通过 ADB tunnel_forward 获取 H.264 硬件编码流，手机端 MediaCodec 零拷贝解码渲染
- 首次连接时自动推送 scrcpy-server.jar 到眼镜（仅一次，后续复用），无需每次推送
- 支持缩放、双指平移查看
- 眼镜端 ADB TCP 断线后自动重连（最多 3 次）
- **性能优化**：优化灰度转换算法，使用整数运算和缓冲区复用减少 GC 压力
- **配置集中管理**：通过 `AppConfig` 统一管理镜像端口、超时时间等配置参数

### 手机投屏
- 将手机屏幕投射到眼镜上（基于 CXR-L + MediaProjection）
- 一键启动/停止，眼镜端自动接收
- 投屏后眼镜端全屏显示画面，无 UI 干扰
- Socket 传屏协议：`[1B方向][2B宽LE][2B高LE][N*1B灰度]`，3 秒连接超时
- **帧健康看门狗**：30 秒无帧时重建 ImageReader 恢复画面
- **ImageReader 自恢复**：方向切换导致帧暂停时，自动重建 ImageReader + 更新 VirtualDisplay Surface，无需重建 MediaProjection
- **Socket 无限重连**：断连后异步自动重试（独立线程池），使用 `isReconnecting` 标记防止重连循环
- **方向缓存优化**：使用 `currentOrientation` 缓存取代每帧查询 `DisplayManager`，减少 IPC 开销
- **线程安全同步锁**：`mirrorLock` 保护 ImageReader 和 VirtualDisplay 切换，防竞态
- **眼镜端 Server 自愈**：Activity 被系统重新拉起时自动重启 Server
- **停止投屏行为**：停止投屏仅断开 Socket 连接，眼镜端 RokidLink 退回后台保持运行，下次可直接恢复（不再 `stopApp`）
- **旋转不中断**：MainActivity 设置 `configChanges="orientation|screenSize"`，旋转时不重建 Activity，投屏持续流畅
- 眼镜端 RokidLink APK 自动随手机端构建（build.gradle.kts 集成），始终保持同步

### 文件管理
- 通过 ADB 浏览和管理眼镜上的文件
- 上传、下载、删除、重命名文件
- 新建文件夹、复制/剪切/粘贴
- 支持图片预览和文本查看（shell cat / 本地缓存）
- **APK 安装功能**：支持直接从文件管理器安装 APK 到眼镜，集成安装状态显示
- **排序功能**：支持按名称/大小/日期排序，点击切换升序/降序，带 ↑↓ 指示器
- **刷新动画**：刷新按钮点击时带旋转动画反馈
- **配置集中管理**：通过 `AppConfig` 统一管理 ADB 端口、连接超时等配置参数

### 设置
- 眼镜端 RokidLink 服务管理（安装/重装/启动/停止）
- 商店源切换
- 主机应用切换
- **多语言切换**：支持简体中文/English 随时切换，首次启动自动检测系统语言
- 应用版本和更新
- **系统日志面板**：提供实时日志查看功能，便于调试和问题排查

## 技术栈

- **语言**: Kotlin
- **UI**: Jetpack Compose (Material 3)
- **本地化**: Android 原生资源系统（`values/` + `values-en/`），运行时 `AppCompatDelegate.setApplicationLocales()` 切换，Crowdin 云端翻译管理
- **设计风格**: 双主题配色系统 — 丝绒炭黑（Velvet Dark，暖暗调） + 冰蓝冰川（Cool Blue，浅蓝冷调），7 模块 7 色配色体系，详见 [UI-DESIGN.md](./UI-DESIGN.md)
- **通信**:
  - CXR-L SDK（手机-眼镜通信，用于安装/启动/卸载应用）
  - ADB over TCP（自定义协议实现，无需 adb.exe，用于文件管理、ADB工具和屏幕镜像）
  - 蓝牙 HID Device 协议（手机模拟键盘/鼠标/游戏手柄）
- **投屏**: MediaProjection API + Socket 传输
- **统一 HTTP 工具**: `HttpClient` 对象封装（替代裸 `HttpURLConnection`）
- **统一交互组件**: `BrewButton`/`BrewOutlineButton`/`BrewCompactButton`/`BrewIconButton` 标准按钮系统、`BrewDialog` 标准对话框（RokidLink 卡片样式：彩色标题栏 + 装饰分隔线 + 彩色边框）、`BrewStatusDot`/`BrewStatusPill`/`BrewStateCard` 标准状态指示器（详见 [UI-DESIGN.md](./UI-DESIGN.md)）

### 国际化（i18n）

- **完整中英文支持**: 所有用户可见文本均使用 `values/strings.xml`（中文）和 `values-en/strings.xml`（英文）管理，无硬编码字符串
- **运行时语言切换**: 通过 `LocalizationManager` + `AppCompatDelegate.setApplicationLocales()` 实现无需重启的语言切换
- **首次启动自动检测**: 自动检测系统语言并应用对应翻译
- **例外**: 品牌名（ROKIDLAB、ROKIDLINK）、技术术语（ADB、TCP、HTTP）、作者信息等保持原文
- **最低版本**: Android 9 (API 28)

## 代码质量与安全

### 配置管理
- **AppConfig 统一配置**：所有硬编码的配置参数集中管理（端口、超时、重试次数等）
- **易于维护**：修改配置无需在多个文件中查找，统一在 `AppConfig.kt` 中调整

### 线程安全
- **同步锁保护**：关键操作使用 `synchronized` 块保护，防止并发修改
- **@Volatile 注解**：确保多线程环境下的变量可见性
- **协程安全**：协程间通信使用 Channel 和 Flow，避免竞态条件

### 资源管理
- **自动资源释放**：所有网络连接、文件流、ADB 连接等资源在使用后正确关闭
- **内存泄漏预防**：投屏服务停止时释放 ImageReader、VirtualDisplay、MediaProjection 等资源
- **线程管理**：使用 HandlerThread 管理后台线程，避免主线程阻塞

### 安全性
- **ADB 密钥保护**：生成的 RSA 私钥文件权限设置为仅应用可读写
- **输入验证**：所有用户输入都经过验证，防止注入攻击
- **网络安全**：使用 HTTPS 连接，支持证书验证

### 错误处理
- **异常捕获**：所有可能失败的操作都使用 try-catch 包裹
- **用户友好提示**：错误信息通过 Toast 和状态卡片显示，便于用户理解
- **日志记录**：详细的日志记录便于问题排查和调试

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

- **HID 描述符**: 自定义报表描述符，支持 3 个 Report ID：Consumer Control（导航键）、Keyboard（功能键）、Mouse（触控板）
- **双通道按键**: 方向键/Select/Start 走 Consumer Control 通道，A/B/C/X/Y/Z/L/R 走 Keyboard 通道
- **自动重连**: 检测到 HID 连接秒断时，智能等待递增间隔后自动重试，最多 5 次
- **安全退出**: App 退出或 `onDestroy()` 时按序执行 `disconnect()` → `unregisterApp()` → `closeProfileProxy()`，确保眼镜 HID Host 正确清理连接状态
- **鼠标模式**: 触控板区域拖拽发送相对位移报表，灵敏度可调

## 项目结构

```
RokidLab/
├── phone-app/                             手机端应用
│   ├── src/main/
│   │   ├── java/com/rokidlab/phone/
│   │   │   ├── app/         主入口
│   │   │   │   ├── MainActivity.kt        主入口、投屏控制、状态管理、权限请求、镜像源对话框
│   │   │   │   └── LabApplication.kt     全局 Application 状态、HID Manager
│   │   │   ├── adb/         ADB 协议实现
│   │   │   │   ├── AdbShellClient.kt      Shell 命令/应用管理/定时功能
│   │   │   │   ├── AdbFileManagerClient.kt   文件管理 ADB 客户端
│   │   │   │   └── AdbScreenMirrorClient.kt  屏幕镜像 ADB 客户端
│   │   │   ├── design/      设计系统
│   │   │   ├── StoreTheme.kt      配色/字体/主题（主入口，主题管理 + 全局颜色）
│   │   │   ├── DesignComponents.kt 全局 UI 组件（错误/警告/加载/结果卡片）
│   │   │   ├── theme/              主题目录
│   │   │   │   ├── BrewColors.kt         配色接口定义（14色）
│   │   │   │   ├── BrewThemeManager.kt   主题管理器（mutableStateOf）
│   │   │   │   ├── VelvetDarkColors.kt   默认主题（丝绒炭黑）
│   │   │   │   └── CoolBlueColors.kt     第二主题（冰蓝冰川）
│   │   │   └── RokidHostApp.kt    HostApp 枚举
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
│   │   │   │   └── GamepadActivity.kt       手柄按键/鼠标模式 Activity
│   │   │   ├── mirror/      投屏模块
│   │   │   │   ├── PhoneMirrorActivity.kt     手机投屏页面
│   │   │   │   ├── PhoneMirrorService.kt      投屏前台 Service（独立线程池异步重连、方向缓存、mirrorLock 同步锁、isReconnecting 防循环、帧健康看门狗30s）
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
│   │   │       ├── LocalizationManager.kt  语言切换管理（首次启动自动检测 + 运行时切换）
│   │   │       └── ImageDecoder.kt     图片解码工具
│   │   ├── res/             资源文件
│   │   │   ├── values/strings.xml   简体中文（默认语言）
│   │   │   └── values-en/strings.xml  English
│   │   └── assets/          内置资源
│   └── build.gradle.kts
│
├── RokidLink/                            眼镜端配套应用（RokidLink，自动打包到 phone-app assets）
│   ├── src/main/
│   │   ├── java/com/rokidlab/rokidlink/
│   │   │   ├── MainActivity.kt              WiFi/ADB 状态面板
│   │   │   ├── PhoneMirrorActivity.kt       投屏接收画面（onResume 自愈）
│   │   │   ├── PhoneMirrorServer.kt         Socket 服务端（灰度图接收 + Bitmap 显示 + 像素数组复用防 OOM + 线程泄漏修复）
│   │   ├── res/layout/activity_main.xml     状态面板布局
│   │   └── AndroidManifest.xml
│   └── build.gradle.kts
│
├── phone-app/src/main/assets/              内置资源
│   ├── RokidLink.apk                       通过 CXR-L SDK 安装到眼镜（自动从 RokidLink 构建同步）
│   └── scrcpy-server.jar                   通过 ADB 推送到 /data/local/tmp/，供 scrcpy-server 启动使用
│
├── crowdin.yml                             Crowdin 翻译管理配置
├── UI-DESIGN.md                            UI 设计参考文档
└── apps/                                   应用数据示例
```

## 工作区规范

项目遵循 `.trae/rules/workspace_rules.md` 中的开发规范，包括：

- **多语种规则**: 所有用户可见文本必须使用 `R.string.xxx` 引用语言包，禁止硬编码（例外：品牌名、技术术语、作者信息）
- **弹窗样式规范**: 所有弹窗统一使用 `BrewDialog`，传入模块色 `color` 参数，自动应用彩色标题栏 + 装饰线 + 彩色边框
- **Gradle 配置**: `settings.gradle.kts` 中模块路径为 `include(":cxrl:RokidLab:phone-app")` 和 `include(":RokidLink")`
- **资源管理**: 定期清理未使用的资源文件，避免打包冗余

## 构建

```powershell
# 构建手机应用（自动同步最新 RokidLink APK 到 assets）
.\gradlew :phone-app:assembleDebug

# 仅构建眼镜端服务
.\gradlew :RokidLink:assembleDebug

# 仅同步 RokidLink APK 到 phone-app assets（不重新构建手机端）
.\gradlew :phone-app:buildRokidLinkDebug
```

## 安装

### 手机应用
```powershell
adb install phone-app/build/outputs/apk/debug/RokidLab-v1.0.0-debug.apk
```

### 眼镜端服务
通过手机应用中的设置页 → "重装眼镜端" 自动推送到眼镜。也可手动安装：
```powershell
adb install RokidLink/build/outputs/apk/debug/RokidLink-debug.apk
```

## 使用指南

1. 确保眼镜已连接到与手机相同的 WiFi 网络
2. 在手机端打开 RokidLab，按照引导授权并连接眼镜
3. 导航栏分为七个模块：
   - **应用商店**：浏览并安装应用到手机或眼镜
   - **屏幕镜像**：查看眼镜屏幕画面
   - **手机投屏**：将手机画面投射到眼镜
   - **文件管理**：浏览和管理眼镜文件
   - **ADB 工具**：应用管理、定时消息/启动、系统信息、Shell 命令（四子功能使用其他导航栏色区分）
   - **蓝牙手柄**：鼠标/游戏手柄模式控制眼镜
   - **设置**：应用配置、服务管理、**语言切换**

> **RokidLink 卡片色彩规则**：已安装/运行中 → 各自模块导航栏色；未安装/安装中/停止按钮 → 应用商店色（BrewCoral）；启动按钮 → 各自导航栏色。

## 多语言支持

- **默认语言**：简体中文（`values/strings.xml`）
- **支持语言**：English（`values-en/strings.xml`）
- **首次启动**：自动检测手机系统语言，zh 显示中文，其他语言显示英文
- **运行时切换**：设置 → 语言 → 选择后立即生效，无需重启
- **扩展语言**：通过 Crowdin 管理翻译，在 `res/` 下新建 `values-{lang}/strings.xml` 即可

## 依赖

- **CXR-L SDK** (`client-l-1.0.3.aar`) — 手机端与眼镜通信
- **CXR-S SDK** (`cxr-service-bridge-1.0.aar`) — 眼镜端桥接服务
- Jetpack Compose (Material 3) — 现代 UI 框架
- 蓝牙 HID Device Profile — 系统 API（Android 9+）

## UI 设计

详见 [UI-DESIGN.md](./UI-DESIGN.md)

设计风格：**双主题配色系统**
- **丝绒炭黑（Velvet Dark）**：暖暗调默认主题（`#0B0B0E` 底色）
- **冰蓝冰川（Cool Blue）**：浅蓝冷调主题（`#F0F5FF` 底色）
- 全站 JetBrains Mono 等宽字体
- 统一 12dp 圆角、1dp 边框
- 五模块五色撞色方案
- 设置中可随时切换主题

## 许可

MIT License

## 作者

**DLOVER**
