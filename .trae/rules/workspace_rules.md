# 工作区规则

本项目的完整开发规范详见 [`RULES.md`](file:///d:/rokidapp/cxrl/RokidLab/RULES.md)。

## 核心约束

在每次修改代码时，AI 助手必须严格遵守以下规则：

### 1. 多语种规则
- **所有用户可见文本必须通过 `strings.xml` + `ctx.getString(R.string.*)` 引用**
- 禁止任何硬编码中文或英文文本在 Kotlin/XML 代码中
- 字符串必须同时在 `values/strings.xml`（中文）和 `values-en/strings.xml`（英文）中定义
- 日志信息使用英文
- **Toast 消息、log() 输出、弹窗标题/内容等所有显示给用户的文本都必须加语言包**
- 例外：品牌名（Rokid、RokidLab、RokidLink）、作者信息、技术术语（ADB、APK、WiFi、Shell 等）

### 2. 设计系统规则
- 优先使用 `com.rokidlab.phone.design` 包中的现有组件
- 颜色优先使用 `Brew*` 常量（如 `BrewInfo`, `BrewGreen`），避免直接写 Color 值
- 形状优先使用 `BrewShape*` 常量

### 3. ADB 模块规则
- 子功能必须使用 `AdbDialogContent` 容器，不自行处理连接状态
- UI 层代码放在 `adb/ui/` 子包中
- 业务逻辑代码放在 `adb/` 根包中

### 4. 弹窗样式规范
- **统一使用 `BrewDialog` 组件**，禁止使用原始 `androidx.compose.ui.window.Dialog` 或 `AlertDialog`
- `BrewDialog` 已内置 RokidLink 卡片样式：彩色标题条 + 装饰分隔线 + 彩色边框
- 调用方式：`BrewDialog(onDismiss, title = "...", icon = "...", subtitle = "...", color = BrewXxx) { ... }`
- `color` 参数决定标题条背景色和边框色，应与功能模块对应：
  - 系统信息：`BrewInfo`（蓝色）
  - 应用管理：`BrewCoral`（珊瑚色）
  - 定时功能：`BrewWarning`（橙色）
  - Shell 命令：`BrewMagenta`（洋红）
  - 删除确认：`BrewRed`（红色）
  - 语言选择：`BrewPurple`（紫色）
  - 更新弹窗：`BrewGreen`（绿色）
- 例外：全屏覆盖层（如图片查看器 `ScreenshotViewerDialog`）可使用原始 `Dialog`

### 5. 架构规则
- 按功能分包，UI 层放在 `ui/` 子包
- 公共组件纳入 `design/` 包
- 不要在 Composable 函数内部定义类

### 6. Gradle 项目配置规范
- `settings.gradle.kts` 中项目路径必须与实际目录结构一致
- phone-app 依赖 RokidLink 模块，需在 `settings.gradle.kts` 中正确配置：
  ```kotlin
  include(":cxrl:RokidLab:phone-app")
  include(":RokidLink")  // phone-app 的 build.gradle.kts 中引用 :RokidLink
  ```
- 构建命令：`.\gradlew.bat :cxrl:RokidLab:phone-app:assembleDebug`

### 7. ADB 设备规则
- **安装 phone-app 到手机**：`adb -s 9fc033b0 install -r <apk>`
- **安装到眼镜**：`adb -s 1901092534015091 install <apk>`
- 设备清单：
  - `9fc033b0` (model: 24129PN74C) — **手机端**，phone-app 安装目标
  - `1901092534015091` (model: RG_glasses) — **眼镜端**，RokidLink/眼镜应用安装目标
- **严禁**不带 `-s` 参数执行 `adb install`，避免装错设备

### 8. Git 同步规则
- **RokidLab 同步 Gitee**：RokidLab 子模块的 origin remote 是 `https://gitee.com/dlover1314/RokidLab`，同步时必须在 `cxrl/RokidLab` 目录下执行 git 操作
  ```powershell
  cd cxrl/RokidLab
  git add <文件>
  git commit -m "描述"
  git push origin master
  ```
- **不要对外层仓库（`d:\rokidapp`）执行 push 操作**，外层仓库有 r2emu.apk 等大文件会导致 Gitee 推送被拒

### 9. 眼镜端开发参考

#### 9.1 Rokid Glasses 硬件规格

Rokid Glasses（型号 RV101）运行 **YodaOS-Sprite** 系统（基于 **Android 12（API 31）** / Android Go），配套 SDK 为 CXR-S（`cxr-service-bridge`）。

**官方开发者文档地址：**
```
https://custom.rokid.com/prod/rokid_web/ff28c865a9634876be98cbc293588460/pc/cn/index.html
```

| 项 | 说明 |
|---|---|
| 眼镜系统 | YodaOS-Sprite，Android 12（API 31）/ Android Go |
| 官方 Sample minSdk | **31** |
| RokidLink 项目 minSdk | **28**（兼容更早系统版本） |
| 屏幕分辨率 | **480 × 640 px**（单绿色显示） |
| 镜腿输入 | 功能键 + 右触控板 TouchPad（系统广播 + KeyEvent） |
| 音频 | 8 通道原始音频（`AudioRecord`） |
| 相机 | CameraX（拍照/录像） |
| 传感器 | 六轴 IMU（`SensorManager`） |
| UI 规范 | https://t.rokid.com/0w0opp8x |

#### 9.2 与手机端通信方式

| 协议/SDK | 方向 | 用途 |
|:--------:|:----:|:------|
| **CXR-S**（`cxr-service-bridge`） | 眼镜端 | 接收手机端 CXR-L SDK 的指令（安装/启动/卸载应用等） |
| **CXR-L**（`client-l`） | 手机端 | 手机端 SDK，通过 CXR-S 与眼镜通信 |
| ADB over TCP | 双向 | 文件管理、屏幕镜像、Shell 命令等 |
| Socket（自定义协议） | 手机→眼镜 | 手机投屏：`[1B方向][2B宽][2B高][N*1B灰度]` |

#### 9.3 CXR-S SDK 参考

CXR-S SDK（`cxr-service-bridge-1.0.aar`）位于 `RokidLink/libs/`，集成方式：
```kotlin
dependencies {
    implementation(files("libs/cxr-service-bridge-1.0.aar"))
}
```

CXR-S 为眼镜端提供的服务能力（对应手机端 CXR-L SDK 的 `ICXRLinkCbk` 回调）：
- 安装/启动/卸载应用
- 自定义命令、自定义视图
- 音频流、图片流接收
- 设备状态回传、AI 事件回调

CXR-S SDK 内部接口结构（从 `phone-app/libs/` 反编译获取）：
```
com.rokid.cxr.link.callbacks.ICXRLinkCbk       // CXR-L 连接回调
com.rokid.cxr.link.callbacks.IAudioStreamCbk    // 音频流回调
com.rokid.cxr.link.callbacks.IImageStreamCbk    // 图片流回调
com.rokid.cxr.link.callbacks.ICustomCmdCbk      // 自定义命令回调
com.rokid.cxr.link.callbacks.ICustomViewCbk     // 自定义视图回调
com.rokid.cxr.link.callbacks.IGlassAppCbk       // 眼镜端应用管理回调
com.rokid.cxr.CXRServiceBridge                  // 眼镜端服务桥接入口
com.rokid.cxr.CXRSocketProtocol                 // Socket 通信协议
```

**CXR-S SDK 详细 API（连接管理、消息订阅/发送、IMU、按键广播等）见 `d:\rokidapp\.trae\rules\project_rules.md` 的「SDK 能力参考」章节。**

#### 9.4 裸机开发

Rokid Glasses 支持直接运行标准 Android 应用，无需手机端协同 SDK。官方示例工程：**GlassesBareDevSample**（包名 `com.rokid.glassesbaredevsample`）。

通过手机端 **Rokid AI App** 开启眼镜 ADB 后调试：
```bash
adb connect 192.168.49.1
adb -s <device_id> install <app.apk>
```


