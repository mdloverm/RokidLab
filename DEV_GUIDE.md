# RokidLab 开发者文档

## 目录

1. [RokidLab 基础定制](#1-rokidlab-基础定制)
   - [语言包与翻译](#11-语言包与翻译)
   - [主题系统](#12-主题系统)
2. [蓝牙手柄开发指南](#2-蓝牙手柄开发指南)
   - [连接方式](#21-连接方式)
   - [HID 描述符](#22-hid-描述符)
   - [按键映射表](#23-按键映射表)
   - [按键常量定义](#24-按键常量定义)
3. [键盘输入功能开发指南](#3-键盘输入功能开发指南)
   - [功能概述](#31-功能概述)
   - [三层方案详解](#32-三层方案详解)
   - [通信协议](#33-通信协议)
   - [文件清单](#34-文件清单)
4. [眼镜端游戏开发](#4-眼镜端游戏开发)
   - [推荐方案：键盘监听](#41-推荐方案键盘监听)
   - [Android View 示例](#42-android-view-示例)
   - [Unity 示例](#43-unity-示例)
   - [关键须知](#44-关键须知)
   - [调试工具](#45-调试工具)
   - [快速开始模板](#46-快速开始模板)
5. [商店应用提交指南](#5-商店应用提交指南)
   - [提交步骤](#51-提交步骤)
   - [完整 JSON 示例](#52-完整-json-示例)
   - [字段详解](#53-字段详解)
   - [更新版本](#54-更新版本)
   - [注意事项](#55-注意事项)
6. [AIUI Lab 工具桥开发指南（v3.5 新增）](#6-aiui-lab-工具桥开发指南v35-新增)
   - [架构与协议链路](#61-架构与协议链路)
   - [页面侧调用规范](#62-页面侧调用规范)
   - [启动参数下发](#63-启动参数下发)
   - [手机端 ToolGateway 设计约束](#64-手机端-toolgateway-设计约束)
   - [调试要点](#65-调试要点)
7. [Agent 核心架构（v3.5 升级）](#7-agent-核心架构v35-升级)
   - [CxrLHiRokidSession 拆分](#71-cxrlhirokidsession-拆分)
   - [ASR 双通道与防抖](#72-asr-双通道与防抖)
   - [会话记忆与长期记忆](#73-会话记忆与长期记忆)

---

## 1. RokidLab 基础定制

### 1.1 语言包与翻译

#### Crowdin 翻译平台

RokidLab 使用 Crowdin 进行社区协作翻译。

**翻译提交地址：**
```
https://crwd.in/rokidlab/de7a762ecacaa13d21021e1b5f66b0132805017
```

#### 语言文件位置

- 中文（默认）：`phone-app/src/main/res/values/strings.xml`
- 英语：`phone-app/src/main/res/values-en/strings.xml`

#### 添加新语言

1. 在 `LocalizationManager.kt` 的 `AppLocale` 枚举中添加新语言：

```kotlin
// phone-app/src/main/java/com/rokidlab/phone/util/LocalizationManager.kt
enum class AppLocale(val code: String, val displayName: String) {
    ZH_CN("zh", "简体中文"),
    EN("en", "English"),
    // 添加新语言：
    JA("ja", "日本語"),
}
```

2. 在 `res/` 下创建对应 values 文件夹和 `strings.xml`，如 `res/values-ja/strings.xml`，以英文版为模板翻译。
3. 在 Crowdin 项目中添加新语言，译者可直接在平台上翻译。

---

### 1.2 主题系统

#### 架构

| 文件 | 用途 |
|------|------|
| `design/theme/BrewColors.kt` | 配色接口，定义所有颜色字段 |
| `design/theme/VelvetDarkColors.kt` | 默认暗色主题实现 |
| `design/theme/CoolBlueColors.kt` | 浅蓝主题实现 |
| `design/theme/BrewThemeManager.kt` | 主题管理器，切换主题 |

#### 颜色接口

每个主题需实现 `BrewColors` 接口的全部字段：

```kotlin
interface BrewColors {
    // 底色
    val bg: Color         // 主背景
    val panel: Color      // 暗灰板
    val panelAlt: Color   // 亮灰板
    val panelHi: Color    // 高亮面板

    // 文字
    val textBright: Color // 正文
    val text: Color       // 次要文字
    val muted: Color      // 辅助文字
    val dim: Color        // 禁用文字

    // 边框
    val border: Color

    // 七模块七色（固定用途）
    val store: Color       // 商店
    val mirror: Color      // 屏幕镜像
    val projection: Color  // 手机投屏
    val fileManager: Color // 文件管理
    val adbTools: Color    // ADB 工具
    val hidGamepad: Color  // HID 手柄
    val settings: Color    // 设置
}
```

#### 添加新主题

**第一步：创建配色实现类**

```kotlin
// design/theme/ForestGreenColors.kt
package com.rokidlab.phone.design.theme

import androidx.compose.ui.graphics.Color

object ForestGreenColors : BrewColors {
    override val bg: Color = Color(0xFF0A1A0F)
    override val panel: Color = Color(0xFF0F2214)
    override val panelAlt: Color = Color(0xFF142C1A)
    override val panelHi: Color = Color(0xFF1A3722)
    override val textBright: Color = Color(0xFFE8F0E8)
    override val text: Color = Color(0xFFC0D0C0)
    override val muted: Color = Color(0xFF809880)
    override val dim: Color = Color(0xFF405540)
    override val border: Color = Color(0xFF2A4A30)
    override val store: Color = Color(0xFFE8914A)
    override val mirror: Color = Color(0xFF4AE8B5)
    override val projection: Color = Color(0xFFB58AFF)
    override val fileManager: Color = Color(0xFFFFB84D)
    override val adbTools: Color = Color(0xFF5CB8FF)
    override val hidGamepad: Color = Color(0xFFFF6B8A)
    override val settings: Color = Color(0xFF8AB8D8)
}
```

**第二步：注册主题枚举**

```kotlin
// BrewThemeManager.kt
enum class BrewTheme(val displayNameResId: Int, val descriptionResId: Int) {
    VELVET_DARK(R.string.theme_velvet_dark, R.string.theme_velvet_dark_desc),
    COOL_BLUE(R.string.theme_cool_blue, R.string.theme_cool_blue_desc),
    FOREST_GREEN(R.string.theme_forest_green, R.string.theme_forest_green_desc),
}
```

**第三步：关联配色**

```kotlin
// BrewThemeManager.kt
val currentColors: BrewColors
    get() = when (_currentTheme) {
        BrewTheme.VELVET_DARK -> VelvetDark
        BrewTheme.COOL_BLUE -> CoolBlue
        BrewTheme.FOREST_GREEN -> ForestGreenColors
    }
```

**第四步：添加多语言字符串**

```xml
<!-- values/strings.xml -->
<string name="theme_forest_green">森林绿</string>
<string name="theme_forest_green_desc">深绿自然主题</string>

<!-- values-en/strings.xml -->
<string name="theme_forest_green">Forest Green</string>
<string name="theme_forest_green_desc">Deep forest green theme</string>
```

---

## 2. 蓝牙手柄开发指南

### 2.1 连接方式

RokidLab 通过 Android `BluetoothHidDevice` API 将手机注册为蓝牙 HID 设备，眼镜作为 HID Host 接收输入。

```
手机（HID Device）──蓝牙──▶ 眼镜（HID Host）
```

### 2.2 HID 描述符

手机端 HID 描述符包含 3 个 Report：

| Report ID | 类型 | 用途 | 眼镜端设备 |
|:----------:|:----:|:----:|:----------:|
| 2 | Consumer Control | ↑↓←→ / Select / Start | `event2` Consumer Control |
| 1 | Keyboard | A/B/C/X/Y/Z/L/R | `event3` Keyboard |
| 3 | Mouse | 鼠标模式 | `event4` Mouse |

### 2.3 按键映射表

#### 手柄模式（方向键走 Consumer Control，功能键走 Keyboard）

| 手柄按键 | HID 通道 | 发送数据 | 眼镜接收键码 | Keyboard 映射 |
|:--------:|:---------:|:--------:|:------------:|:-------------:|
| **↑** | Consumer | `0x42 0x00` | `KEYCODE_DPAD_UP` (19) | — |
| **↓** | Consumer | `0x43 0x00` | `KEYCODE_DPAD_DOWN` (20) | — |
| **←** | Consumer | `0x44 0x00` | `KEYCODE_DPAD_LEFT` (21) | — |
| **→** | Consumer | `0x45 0x00` | `KEYCODE_DPAD_RIGHT` (22) | — |
| **Select** | Consumer | `0x41 0x00` | `KEYCODE_DPAD_CENTER` (23) | — |
| **Start** | Consumer | `0x41 0x00` | `KEYCODE_DPAD_CENTER` (23) | — |
| **A** | Keyboard | `scancode 0x1D` | `KEYCODE_Z` (52) | `z` |
| **B** | Keyboard | `scancode 0x1B` | `KEYCODE_X` (47) | `x` |
| **C** | Keyboard | `scancode 0x06` | `KEYCODE_C` (43) | `c` |
| **X** | Keyboard | `scancode 0x04` | `KEYCODE_A` (29) | `a` |
| **Y** | Keyboard | `scancode 0x16` | `KEYCODE_S` (47) | `s` |
| **Z** | Keyboard | `scancode 0x07` | `KEYCODE_D` (32) | `d` |
| **L** | Keyboard | `scancode 0x14` | `KEYCODE_Q` (45) | `q` |
| **R** | Keyboard | `scancode 0x1A` | `KEYCODE_W` (51) | `w` |

### 2.4 按键常量定义

```kotlin
// BluetoothHidManager.kt
const val KEY_A      = 0
const val KEY_B      = 1
const val KEY_C      = 2
const val KEY_X      = 3
const val KEY_Y      = 4
const val KEY_Z      = 5
const val KEY_L      = 6
const val KEY_R      = 7
const val KEY_SELECT = 8
const val KEY_START  = 9
const val KEY_UP     = 10
const val KEY_DOWN   = 11
const val KEY_LEFT   = 12
const val KEY_RIGHT  = 13
```

---

## 3. 键盘输入功能开发指南

### 3.1 功能概述

RokidLab 提供在鼠标模式下向眼镜焦点 App 输入中英文文字的功能。用户通过手机键盘打字，文字经三层方案依次尝试写入眼镜。

```
手机输入法 ──→ [键盘弹窗] ──→ TCP TextInputService ─→ 设置剪贴板 ─→ input keyevent KEYCODE_PASTE
                  │
                  ├── 失败 → AdbPasteCompat (ADB 原始协议) ─→ shell input keyevent KEYCODE_PASTE
                  │
                  └── 失败 → BluetoothHidManager.sendCtrlV() (HID Ctrl+V)
```

### 3.2 三层方案详解

#### 方案一：TCP 直连眼镜 TextInputService（主方案）

**眼镜端** — `TextInputService.kt`：
- 在端口 7656 启动 TCP Server 线程
- 接收手机发送的文字数据（UTF-8 编码的字符串，以换行符结尾）
- 使用 Java `StringBuilder` 阻塞读取 `BufferedReader.readLine()`
- 将接收到的文字设置到系统剪贴板：通过 `context.getSystemService<ClipboardManager>().setPrimaryClip()`
- 尝试 `Runtime.exec("input keyevent KEYCODE_PASTE")` 注入粘贴事件
- 回复 "OK\n" 到客户端，表示处理完成
- 循环等待下一条文字

> **注意**：从普通 App 内执行 `input keyevent` 缺少 `INJECT_EVENTS` 系统权限，此方案在部分系统上可能失败。

**手机端** — `GamepadActivity.kt`：
- 触控板区域下方显示键盘按钮，点击弹出 `AlertDialog`
- 对话框内包含 `TextField`（默认 `OutlinedTextField`）用于文字输入
- 点击"发送"后顺序执行三层方案
- 自动从 `LabApplication.phoneMirrorIp` 获取眼镜 IP

#### 方案二：ADB 原始协议粘贴（备选）

**手机端** — `AdbPasteCompat.kt`：
- 通过 TCP Socket 直连眼镜 ADB 端口 5555，无需 adb.exe
- 实现 ADB 原始二进制协议（24 字节小端序报头 + 负载）
- 完成 RSA 认证握手（`CNXN`/`AUTH`/`SIGNATURE` 消息交换）
- 打开 `shell:` service，执行 `input keyevent KEYCODE_PASTE`
- 读取服务端 `OKAY` + 输出流确认命令已执行
- 连接完成后自动关闭 Socket

**核心代码结构**：
```kotlin
data class Packet(val cmd: String, val arg0: Int, val arg1: Int, val data: ByteArray)

// 连接 → 认证 → 打开 shell → 执行 input keyevent KEYCODE_PASTE → 关闭
fun execPaste(ip: String, port: Int = 5555): Boolean
```

> 从 ADB shell 执行 `input keyevent` 拥有 `INJECT_EVENTS` 系统权限，可以跨 App 注入粘贴事件。

#### 方案三：HID Ctrl+V 兜底

**手机端** — `BluetoothHidManager.kt` 的 `sendCtrlV()` 方法：
- 发送 8 字节标准 HID 键盘报告：`byteArrayOf(0x08, 0x00, 0x19, 0x00, 0x00, 0x00, 0x00, 0x00)`
  - 第一个字节 `0x08` = 左 Ctrl 修饰键
  - 第三个字节 `0x19` = 按键码 'v'（对应的 Usage ID）
- 通过 `BluetoothHidDevice.sendReport()` 发送到眼镜
- 松键报告：`byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)`

### 3.3 通信协议

#### TextInputService TCP 协议

| 方向 | 格式 | 说明 |
|:----:|:----:|:----:|
| 手机 → 眼镜 | `UTF-8 字符串 + \n` | 要输入的文字内容 |
| 眼镜 → 手机 | `OK\n` | 处理完成（剪贴板已设置，粘贴已尝试） |

#### ADB 原始协议（AdbPasteCompat）

完整实现 ADB TCP 通信协议，包含以下消息交换：

| 阶段 | 客户端发送 | 服务端响应 | 说明 |
|:----:|:----------:|:----------:|:----:|
| 连接 | `CNXN`（版本+最大负载+系统标识） | `CNXN` / `AUTH` | 协商版本 |
| 认证 | `AUTH(TOKEN)` 签名响应 | `OKAY` / `AUTH` | RSA 签名认证 |
| Shell | `OPEN(local_id, "shell:input...")` | `OKAY` → `WRTE`(输出) | 执行粘贴命令 |

**关键实现细节**：
- RSA 密钥对使用 `KeyPairGenerator` 生成，`SHA1withRSA` 签名
- 公钥以 `signature + styles` 格式发送（`@adb` 后缀）
- 24 字节报头：`[4B cmd][4B arg0][4B arg1][12B data_length]`（小端序）

### 3.4 文件清单

| 文件 | 位置 | 说明 |
|:----|:-----|:-----|
| `TextInputService.kt` | `RokidLink/.../rokidlink/` | 眼镜端 TCP 文字接收服务 |
| `AdbPasteCompat.kt` | `phone-app/.../adb/` | 手机端 ADB 原始协议粘贴工具 |
| `GamepadActivity.kt` | `phone-app/.../hid/` | 手机端键盘弹窗 UI 和发送逻辑 |
| `BluetoothHidManager.kt` | `phone-app/.../hid/` | HID Ctrl+V 兜底方案 |

---

## 4. 眼镜端游戏开发

### 4.1 推荐方案：键盘监听

Rokid 眼镜的 Linux 内核 HID 驱动不支持标准 Gamepad Input Device。所有按键通过 **Keyboard** 和 **Consumer Control** 两个输入设备发送，眼镜端接收后转换为 Android 键码。

| 编程框架 | 监听方式 | 示例代码 |
|:---------:|:---------|:---------|
| **Unity** | `Input.GetKeyDown()` | `Input.GetKeyDown(KeyCode.Z)` |
| **Android View** | `onKeyDown()` | `KEYCODE_Z` (52) |
| **Android Compose** | `onKeyEvent` | `KeyEvent.Key.Z` |

### 4.2 Android View 示例

```kotlin
class GameActivity : Activity() {
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_Z      -> { /* A 键按下 */; true }
            KeyEvent.KEYCODE_X      -> { /* B 键按下 */; true }
            KeyEvent.KEYCODE_C      -> { /* C 键按下 */; true }
            KeyEvent.KEYCODE_A      -> { /* X 键按下 */; true }
            KeyEvent.KEYCODE_S      -> { /* Y 键按下 */; true }
            KeyEvent.KEYCODE_D      -> { /* Z 键按下 */; true }
            KeyEvent.KEYCODE_Q      -> { /* L 键按下 */; true }
            KeyEvent.KEYCODE_W      -> { /* R 键按下 */; true }
            KeyEvent.KEYCODE_DPAD_UP     -> { /* ↑ */; true }
            KeyEvent.KEYCODE_DPAD_DOWN   -> { /* ↓ */; true }
            KeyEvent.KEYCODE_DPAD_LEFT   -> { /* ← */; true }
            KeyEvent.KEYCODE_DPAD_RIGHT  -> { /* → */; true }
            KeyEvent.KEYCODE_DPAD_CENTER -> { /* Select / Start */; true }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_Z      -> { /* A 键释放 */; true }
            // ...
            else -> super.onKeyUp(keyCode, event)
        }
    }
}
```

### 4.3 Unity 示例

```csharp
using UnityEngine;

public class RokidController : MonoBehaviour
{
    void Update()
    {
        // 功能键
        if (Input.GetKeyDown(KeyCode.Z))    Debug.Log("A pressed");   // KEYCODE_Z
        if (Input.GetKeyDown(KeyCode.X))    Debug.Log("B pressed");   // KEYCODE_X
        if (Input.GetKeyDown(KeyCode.C))    Debug.Log("C pressed");   // KEYCODE_C
        if (Input.GetKeyDown(KeyCode.A))    Debug.Log("X pressed");   // KEYCODE_A
        if (Input.GetKeyDown(KeyCode.S))    Debug.Log("Y pressed");   // KEYCODE_S
        if (Input.GetKeyDown(KeyCode.D))    Debug.Log("Z pressed");   // KEYCODE_D
        if (Input.GetKeyDown(KeyCode.Q))    Debug.Log("L pressed");   // KEYCODE_Q
        if (Input.GetKeyDown(KeyCode.W))    Debug.Log("R pressed");   // KEYCODE_W

        // 方向键
        if (Input.GetKeyDown(KeyCode.UpArrow))    Debug.Log("Up");
        if (Input.GetKeyDown(KeyCode.DownArrow))  Debug.Log("Down");
        if (Input.GetKeyDown(KeyCode.LeftArrow))  Debug.Log("Left");
        if (Input.GetKeyDown(KeyCode.RightArrow)) Debug.Log("Right");

        // Select / Start 都映射到回车
        if (Input.GetKeyDown(KeyCode.Return))     Debug.Log("Select or Start");
    }
}
```

### 4.4 关键须知

1. **非标准 Gamepad**：Rokid 眼镜内核不支持 `Usage Game Pad (0x05)`，请使用键盘键码。
2. **Select 和 Start 键码相同**：两者都映射为 `KEYCODE_DPAD_CENTER (23)`。建议 Select 作"确定"，Start 作"暂停"。
3. **单键释放**：松开按键即发送释放报告。
4. **鼠标模式**：RokidLab 提供"鼠标"标签页，使用 Mouse Report ID 3，非游戏场景适用。

### 4.5 调试工具

```bash
# 查看所有输入设备
adb shell getevent -p

# 实时监控键盘事件
adb shell getevent -l /dev/input/event3

# 实时监控 Consumer Control 事件
adb shell getevent -l /dev/input/event2
```

### 4.6 快速开始模板

```kotlin
// RokidGameTemplate.kt — 可复用的 Rokid 手柄游戏 Activity 模板

import android.app.Activity
import android.os.Bundle
import android.view.KeyEvent

class RokidGameTemplate : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 设置你的游戏视图
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return handleRokidKey(keyCode, isDown = true)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        return handleRokidKey(keyCode, isDown = false)
    }

    private fun handleRokidKey(keyCode: Int, isDown: Boolean): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP      -> onAction("UP", isDown)
            KeyEvent.KEYCODE_DPAD_DOWN    -> onAction("DOWN", isDown)
            KeyEvent.KEYCODE_DPAD_LEFT    -> onAction("LEFT", isDown)
            KeyEvent.KEYCODE_DPAD_RIGHT   -> onAction("RIGHT", isDown)
            KeyEvent.KEYCODE_Z            -> onAction("A", isDown)
            KeyEvent.KEYCODE_X            -> onAction("B", isDown)
            KeyEvent.KEYCODE_C            -> onAction("C", isDown)
            KeyEvent.KEYCODE_A            -> onAction("X", isDown)
            KeyEvent.KEYCODE_S            -> onAction("Y", isDown)
            KeyEvent.KEYCODE_D            -> onAction("Z", isDown)
            KeyEvent.KEYCODE_Q            -> onAction("L", isDown)
            KeyEvent.KEYCODE_W            -> onAction("R", isDown)
            KeyEvent.KEYCODE_DPAD_CENTER  -> onAction("SELECT", isDown)
            else -> return false
        }
        return true
    }

    private fun onAction(action: String, isDown: Boolean) {
        if (isDown) {
            // 按键按下逻辑
        } else {
            // 按键释放逻辑
        }
    }
}
```

---

## 5. 商店应用提交指南

### 5.1 提交步骤

#### 第一步：准备应用

- 开发适配 Rokid 眼镜（RV101）的 Android 应用
- 目标 API：`minSdk=31`（Android 12），`targetSdk` 兼容即可
- 确保在 **480×640 单绿色屏** 下正常显示
- 打包为 Release APK

#### 第二步：发布 APK

- 上传 APK 到 **GitHub Releases** 或 **Gitee Releases**
- 获取 APK 直链（`releases/download/` 格式）
  - GitHub：`https://github.com/{user}/{repo}/releases/download/{tag}/{file}.apk`
  - Gitee：`https://gitee.com/{user}/{repo}/releases/download/{tag}/{file}.apk`
- 按下方字段说明编写应用 JSON，连同 APK 一起提交给商店维护者

### 5.2 完整 JSON 示例

```json
{
  "schemaVersion": 1,
  "generatedAt": "2026-06-22T00:00:00.000Z",
  "apps": [
    {
      "id": "example-my-app",
      "name": "示例应用",
      "type": "glasses",
      "category": "Utility",
      "version": "1.0.0",
      "summary": "一句话简介",
      "description": "详细描述，2-3句话说明应用功能",
      "author": "你的昵称",
      "sourceUrl": "https://github.com/yourname/your-repo",
      "phoneRequired": false,
      "featured": false,
      "featuredRank": null,
      "publishedAt": "2026-06-01T00:00:00.000Z",
      "newUntil": "2026-08-01T00:00:00.000Z",
      "iconUrl": "https://raw.githubusercontent.com/yourname/your-repo/main/icon.png",
      "screenshotUrls": [
        "https://raw.githubusercontent.com/yourname/your-repo/main/screenshot1.png",
        "https://raw.githubusercontent.com/yourname/your-repo/main/screenshot2.png"
      ],
      "listing": {
        "about": "Markdown 格式的详细介绍，支持换行\n\n### 功能列表\n- 功能一\n- 功能二"
      },
      "releases": [
        {
          "version": "1.0.0",
          "date": "2026-06-01T00:00:00.000Z",
          "sourceReleaseUrl": "https://github.com/yourname/your-repo/releases/tag/v1.0.0",
          "notes": "版本更新说明",
          "changes": [
            "新增功能一",
            "优化功能二",
            "修复问题"
          ]
        }
      ],
      "artifacts": [
        {
          "target": "glasses",
          "url": "https://github.com/yourname/your-repo/releases/download/v1.0.0/app-release.apk",
          "sha256": "apk的sha256校验值，可选",
          "sizeBytes": 1048576,
          "packageName": "com.example.app",
          "versionCode": 1
        }
      ]
    }
  ]
}
```

### 5.3 字段详解

#### 顶层字段

| 字段 | 必填 | 说明 |
|------|:----:|------|
| `schemaVersion` | 是 | 固定为 `1` |
| `generatedAt` | 否 | 生成时间，ISO 8601 |
| `brewVersion` | 否 | RokidLab 自身版本号（第三方勿改） |
| `brewVersionCode` | 否 | RokidLab 自身 versionCode（第三方勿改） |
| `brewApkUrl` | 否 | RokidLab 自身 APK 下载地址（第三方勿改） |
| `brewReleaseUrl` | 否 | RokidLab 自身 Release 页面（第三方勿改） |
| `brewNotes` | 否 | RokidLab 自身更新说明（第三方勿改） |
| `brewChanges` | 否 | RokidLab 自身更新列表（第三方勿改） |
| `apps` | 是 | 应用列表（数组） |

#### 应用字段

| 字段 | 必填 | 说明 | 示例 |
|------|:----:|------|------|
| `id` | **是** | 唯一标识，建议 `作者-应用名` | `dlover1314-rokid-xiaozhi` |
| `name` | **是** | 应用展示名称 | `Rokid 小助手` |
| `type` | **是** | 固定为 `glasses` | `glasses` |
| `category` | **是** | 分类（见下方） | `Utility` |
| `version` | **是** | 版本号（字符串） | `1.0.0` |
| `summary` | **是** | 一句话简介（12 字以内） | `Rokid 眼镜语音助手` |
| `author` | **是** | 作者名称 | `dlover1314` |
| `sourceUrl` | 否 | 源码仓库地址 | `https://github.com/...` |
| `phoneRequired` | 否 | 是否需要手机端配合 | `false` |
| `featured` | 否 | 是否置顶展示 | `false` |
| `featuredRank` | 否 | 置顶排序（越小越靠前） | `1` |
| `publishedAt` | 否 | 发布时间（ISO 8601） | `2026-06-01T00:00:00.000Z` |
| `newUntil` | 否 | 此时间前显示"NEW"标记 | `2026-08-01T00:00:00.000Z` |
| `iconUrl` | 否 | 应用图标 URL（512×512 PNG） | `https://.../icon.png` |
| `screenshotUrls` | 否 | 截图 URL 列表（2-3 张） | `["https://..."]` |
| `description` | 否 | 详细描述 | `这是一款...的应用` |
| `listing` | 否 | Markdown 详细介绍 | 见下方 |
| `releases` | 否 | 版本更新日志 | 见下方 |
| `artifacts` | **是** | APK 下载配置 | 见下方 |

#### 分类选项

| 值 | 说明 |
|----|------|
| `Utility` | 工具 |
| `Game` | 游戏 |
| `Media` | 影音 |
| `Social` | 社交 |
| `Navigation` | 导航 |
| `Health` | 健康 |
| `Education` | 教育 |
| `Productivity` | 效率 |
| `Entertainment` | 娱乐 |
| `Other` | 其他 |

#### artifacts

```json
{
  "target": "glasses",
  "url": "https://.../app.apk",
  "sha256": "可选，SHA256 校验值",
  "sizeBytes": 1048576,
  "packageName": "com.example.app",
  "versionCode": 1
}
```

> `url` 必须是 APK 直链，不能是 Release 页面地址。

#### listing

```json
{
  "about": "Markdown 格式的详细介绍"
}
```

#### releases

每次更新版本时**追加新记录**，不修改旧记录。

```json
{
  "version": "1.0.0",
  "date": "2026-06-01T00:00:00.000Z",
  "sourceReleaseUrl": "https://github.com/.../releases/tag/v1.0.0",
  "notes": "版本更新说明",
  "changes": ["功能一", "功能二"]
}
```

### 5.4 更新版本

修改对应应用的以下字段：

1. `version` — 新版本号
2. `releases` — 追加新 release 记录
3. `artifacts[0].url` — 新 APK 下载地址
4. `artifacts[0].sizeBytes` — 新 APK 文件大小
5. `artifacts[0].versionCode` — 新 versionCode
6. `artifacts[0].sha256` — 新 SHA256（可选）

### 5.5 注意事项

- 远程注册表的 `apps` 数组只需维护**你的应用**，不需要包含全部应用
- 远程应用 `id` 与本地内置应用相同时，远程数据覆盖本地数据
- 手机端 RokidLab 切换商店源或下拉刷新时自动获取最新注册表
- 应用图标和截图建议托管在你自己的代码仓库中，用 raw URL 引用

---

## 6. AIUI Lab 工具桥开发指南（v3.5 新增）

AIUI 页面（`.ink` 智能体）运行在眼镜端 `@yodaos-pkg/ink` 的 quickjs-wasm 沙箱里，与 WebView 主 realm 隔离。原本只能渲染不能调用外部能力。v3.5 引入 Lab 工具桥，让页面可以调用手机端全部 34 个工具（音乐 / 天气 / 搜索 / 提醒 / 设备信息 / 电话 / 日历 等）。

### 6.1 架构与协议链路

```
页面 (ink 沙箱 realm)
  │ globalThis.Lab.callTool(name, args)
  ▼
fetch('https://ink.local/__lab/tool_call_sync?name=...&args=...&cbId=...')
  │  (同源 https://ink.local，被 WebView shouldInterceptRequest 拦截)
  ▼
AiuiLinkActivity.shouldInterceptRequest  (后台线程)
  │  ArrayBlockingQueue<String>(1) 同步阻塞 poll(15s)
  │  invokeToolCall() 调 AsrPushServer.pushControl
  ▼
AsrPushServer.pushControl("__LAB_TOOL__" + JSON)
  │  (复用现有 ASR 推送 RFCOMM 通道，不新开通道
  │   —— 眼镜端同一时刻只允许一条 RFCOMM，adb 隧道已占满)
  ▼
手机端 AsrPushClient.onText
  ▼
AsrBridgeCoordinator（识别 __LAB_TOOL__ 前缀 → 后台线程分流）
  ▼
CxrLHiRokidSession.handleAiuiToolCall(payload)
  ▼
ToolGateway.call(context, name, arguments)
  │  - 检查 isEnabled / DENY_TOOLS / ALLOWED_DOMAINS
  │  - 新建 worker 线程跑 ToolRegistry.execute (15s 超时)
  │  - 结果截断到 8000 字符
  ▼
结果按原路回传：CMD_AIUI_MSG → KeyButtonService.dispatchMessageToActive
  → AiuiLinkActivity.dispatchHostMessage → hostMessage
  → 同时 cacheToolResult 写入轮询缓存（页面 realm 桥可 fetch 拉取）
  ▼
同步队列被 offer 唤醒 → fetch 同步返回 JSON → 页面 Promise resolve
```

**关键文件**：

| 文件 | 角色 |
|---|---|
| `RokidLink/src/main/assets/ink/host.js` | 主 realm 桥：`window.Lab.callTool` / `listTools` / `onToolResult`（适配可访问 `window.Android` 的页面） |
| `RokidLink/src/main/assets/ink/lab-page-bridge.js` | **页面 realm 桥**（关键）：在 `AiuiLinkActivity.buildBundleJson` 装配 bundle 时前置注入到 `app.js`，让 ink 沙箱页面也能用 `globalThis.Lab.callTool` |
| `RokidLink/src/main/java/.../AiuiLinkActivity.kt` | 眼镜端宿主：`@JavascriptInterface callTool` + `__lab/*` 端点拦截 + 同步工具队列 + 启动参数下发 |
| `RokidLink/src/main/java/.../AsrPushServer.kt` | `CTRL_TOOL_CALL = "__LAB_TOOL__"` 工具调用前缀常量 |
| `phone-app/src/main/java/.../ai/ToolGateway.kt` | 手机端统一工具网关，单一入口调度 `ToolRegistry.execute` |
| `phone-app/src/main/java/.../glasses/AsrBridgeCoordinator.kt` | `__LAB_TOOL__` 前缀分流到 `onToolCall` 回调 |
| `phone-app/src/main/java/.../glasses/CxrLHiRokidSession.kt` | `handleAiuiToolCall(payload)` 委派 ToolGateway，结果经 `sendAiuiHostMessage` 下发 |

### 6.2 页面侧调用规范

```js
// 必须写 globalThis.Lab 或 window.Lab —— ink 沙箱页面 realm 不走 globalThis 解析裸标识符，
// 直接写 Lab.callTool 会 ReferenceError。
const text = await globalThis.Lab.callTool('play_song', { songName: '西厢' });
```

**四条硬性要求**（生成方模型必须遵守，见 `lab-runtime.md` 第 8 章）：

1. **必须 try/catch**：官方渲染环境（`Sys_AIUI_Start` / `AgentStore`）没有 JS bridge，`callTool` 会直接 reject。页面必须能降级，不能白屏或卡死
2. **必须有 loading 态**：一次调用含蓝牙往返 + 可能的网络请求，通常 1~3 秒，最长 20 秒超时。调用前 `setData` 置 loading，成功和失败都要清除
3. **结果是字符串**：要什么格式自己解析；超长结果不要整段铺满屏幕
4. **一次只做一件事**：蓝牙通道是串行的，不要并发发起多个 `callTool`

### 6.3 启动参数下发

用户说「用 AIUI 播放西厢」时，模型调用 `open_aiui_app` 工具的 `params` 字段携带 `{"songName":"西厢"}`：

1. `ToolRegistry.open_aiui_app` 调 `parseLaunchParams(args.optString("params"))` 解析（非法 JSON 直接丢弃，绝不把脏串下发到页面）
2. `AiuiFrontendController.pushAixToRokidLinkHost(aixFile, launchParams=...)` → `openAiuiHost(name, launchParams)` → `sendAiuiHostCmd("open", name, launchParams)`
3. 眼镜端 `KeyButtonService` 收到 `CMD_AIUI_OPEN`，从 `caps[2]` 取启动参数，调 `AiuiLinkActivity.open(context, aixPath, launchParams)`
4. `AiuiLinkActivity.onCreate` 读 `EXTRA_LAUNCH_PARAMS`，在 `bootJs` 时作为 `config.launchParams` 带入 `boot()`，页面 boot 完成后 `deliverLaunchParams()` 调 `hostMessage({type:"launch", params})` 下发
5. 页面在 `onMessage` 里接收（**不能在 `onLoad` 里拿**，启动参数只在页面渲染完成后投递一次）：

```js
export default {
  data: { song: '', loading: false },
  onMessage(e) {
    // ⚠️ Lab 自托管 ink 宿主把真实 payload 放在 e.data（JSON 字符串），
    // 不是直接放在 e.type/e.params。必须 JSON.parse(e.data)。
    const msg = (typeof e.data === 'string') ? JSON.parse(e.data) : (e.data || e);
    if (msg.type !== 'launch') return;
    const p = msg.params || {};
    if (p.songName) this.play(p.songName);
  },
}
```

**同一包再次打开换参数**（如「再用 AIUI 放一首别的」）：`AiuiLinkActivity.onNewIntent` 检测到 `p == currentAixPath` 时**不重启宿主**，直接更新 `launchParamsJson` 字段，若已 boot 则 `deliverLaunchParams()` 下发新参数，避免打断页面当前播放/动画。

### 6.4 手机端 ToolGateway 设计约束

`ToolGateway.kt` 是 AIUI 页面调用手机端工具的唯一入口，**改代码前请先读以下约束**：

1. **域白名单按 DOMAIN 继承而非逐工具配置** — 后期加工具只改 `ToolRegistry` 一行注册，网关 / 协议 / JS / skill 全不动。逐工具配会破坏这个性质。
2. **全部域开放**（用户 2026-09-09 拍板）。保留 `ALLOWED_DOMAINS` 是为了想收窄时只改一处。
3. **`DENY_TOOLS` 只放会造成技术故障的工具**（自指递归），不放「危险」工具 — 安全边界由 `isEnabled` 总开关负责，不做逐工具安全判断。
4. **本类是同步阻塞 API**，调用方必须在非主线程调用。`AsrBridgeCoordinator` 已切到后台线程。
5. **结果截断 8000 字符**：RFCOMM 单帧上限 64KB，且页面也渲染不下超长文本。
6. **15s 超时后不 interrupt**：工具可能持有文件/网络资源，强中断会留下半写状态，让线程自己跑完（daemon 线程不阻塞进程退出）。

### 6.5 调试要点

- **WebView 未开启远程调试**：页面 / 宿主的 `console.log` 在 logcat 里看不到，全部走 `window.Android.log(msg)` → `AiuiLinkActivity.bridge.log()` → logcat（tag=`AiuiLink`）
- **`host.js` 主 realm vs 页面 realm**：ink 沙箱页面 realm 看不到主 realm 的 `window.Lab` / `window.Android`（实测 `typeof=undefined`），必须靠 `lab-page-bridge.js` 注入到 `app.js` 前置
- **同步 vs 异步**：`__lab/tool_call_sync` 同步阻塞 fetch 拿结果（页面侧无需轮询），`__lab/tool_call` 异步 fetch 返回 202 + 后续 `onMessage` 回传（兼容旧桥，新代码不用）
- **`toolResult` 双路径下发**：手机端执行完既走 `CMD_AIUI_MSG` → `dispatchHostMessage`，又写入 `toolResults` 缓存（页面 realm 桥可 `fetch('/__lab/tool_result?cbId=...')` 轮询拉取，30s TTL）

---

## 7. Agent 核心架构（v3.5 升级）

### 7.1 CxrLHiRokidSession 拆分

`CxrLHiRokidSession` 在 v3.4 前已长到 3550 行，混了四个不相关职责。v3.5 抽出三个协调器（行为不变，纯粹提取）：

| 新类 | 职责 |
|---|---|
| `glasses/AsrBridgeCoordinator` | 双通道 ASR（SDK + link）去重、控制标记、下行 ping |
| `glasses/AiuiFrontendController` | 整个 AIUI 微前端 pipeline（push .aix、open/close/msg、launchParams） |
| `glasses/PhotoQuizFlow` | 拍照 → OCR → RAG → AI 答案 |

`CxrLHiRokidSession` 保留**每一个 public method 作为委派 facade**，调用方完全不动。同时把持有的 Activity 改为 app context，需要 lifecycle owner 的地方改用 `WeakReference`（leak fix）。

仍然太胖、留待后续的部分：连接引擎（`connectAnd*` ~1200 行）和 `sendAiTextViaLink`（~600 行）。

### 7.2 ASR 双通道与防抖

ASR 文字有三个入口：RFCOMM 推送主通道 / ADB 文件轮询兜底 / CXR 全局指令监听。`AsrBridgeCoordinator` 是去重核心：

**v3.4 之前的去重逻辑**：3 秒内相同文字只处理一次。

**v3.5 新逻辑**：仅当「文字与上次相同 **且** 上一条仍在处理中（`asrHandling=true` 且未超 90s 兜底）」才丢弃。

- 用户连说两次「停止播放」应当执行两次，不再被当成重复指令吞掉
- 真正的「上一条还没处理完」判定交由 `asrHandling` 标志（由 `CxrLHiRokidSession.dispatchGlassesAsrText` 在处理开始 / 结束时调 `markAsrHandling(true/false)`）
- 90s 兜底超时防止异常路径下标志卡死，导致该指令被永久吞掉

**推送断连→恢复补读**：

```
pushWasDown=true  ── 推送断连 ──> 文件轮询兜底（积压文字写入文件）
                                  │
                                  ▼ 推送恢复，AsrPushClient.onConnected 回调
                                  │  catchUpRequested = true
                                  ▼ 下一轮 poll 立即跳出退避，补读积压文件
                                  │  readAiAsrBridgeTextOnce(lastTs)
                                  ▼ catchUpRequested = false
```

之前推送恢复就放心不再读文件，断连期间积压的文字永久丢失（眼镜已显示提问却等不到回复）。

**`start()` 防抖**：连接建立期 `onConnected` 回调与显式调用可能连着两次进入，1.5 秒内重入直接忽略 — 避免重复创建 `AsrPushClient` 抢同一条 RFCOMM 通道把通道搞断。

**`AsrPushClient` socket 时序修复**：必须在 `connect()` 成功**之后**才置 `socket`。旧实现在 `connect()` 前赋值，导致 `isConnected` 在建链握手期间/建链失败后短暂为 true — 上层据此认为「主通道健康」而跳过 ADB 文件兜底轮询，建链失败期间产生的 ASR 文字便永久丢失。

### 7.3 会话记忆与长期记忆

**多轮会话记忆（`AgentSessionManager`）**：

- 跨请求多轮历史，10 分钟无活动自动清空
- v3.5：超长上下文被裁剪时，前缀折叠为 ≤800 字的 pinned system message `[summary of earlier conversation]`，跨数小时长会话不再失忆
- 最多 12 条 / 6000 字符，从最旧丢弃

**长期记忆（`LongTermMemoryManager`）**：

- 跨会话记住用户称呼与偏好，每次对话注入 system prompt
- v3.5：存储从 SharedPreferences 改为 SQLite
  - FIFO 上限 200 条
  - 90 天过期
  - 首次启动自动迁移旧 SharedPreferences 数据
- 注入策略从「全量」改为「检索」：
  - 中文 bigram 重叠度评分
  - 注入 top 12 而非全量
  - 长记忆库增长后不再撑爆 system prompt

**知识库（`KnowledgeBase`）**：

- v3.5：BM25-style IDF 评分
- 命中条目带 provenance（`docName/chunkIdx`），AI 回答可引用来源

**SSE 重连（`OpenAiService`）**：

- v3.5：重连条件从「已开始」收紧为「已发出内容」
  - 无内容时重放是安全的，工具增量可重发
  - 已发出内容后重连会导致重复，所以不重连
- 指数退避 500ms × 2^attempt，上限 4s
- 远程重试从 2 次升到 3 次
