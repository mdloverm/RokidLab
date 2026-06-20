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


