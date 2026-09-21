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
8. [手机端六层架构（v3.5 重构）](#8-手机端六层架构v35-重构)
   - [分层与依赖方向](#81-分层与依赖方向)
   - [工具按域拆分与风险闸门](#82-工具按域拆分与风险闸门)
   - [通道仲裁 ChannelArbiter](#83-通道仲裁-channelarbiter)
   - [双端协议同源与能力握手](#84-双端协议同源与能力握手)
   - [单元测试与回归护栏](#85-单元测试与回归护栏)
   - [聊天历史落盘与线程模型](#86-聊天历史落盘与线程模型)

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

> **翻译缺口状态（2026-09-12）**：✅ 已清零。中文 `values/strings.xml` 1100 条 = 英文 `values-en/strings.xml` 1100 条（此前缺 5 条：`guide_ready_title`、`guide_reinstall_link_btn`、`guide_skip_btn`、`unknown_author`、`wifi_config_success`，已补齐）。
>
> **构建期强制**：`phone-app` 的 `preBuild` 挂着 `checkI18nKeysSynced` 任务，校验 **phone-app 与 RokidLink 两模块** `values/` ↔ `values-en/` 的 `<string name>` 集合完全相等（只比 key，不比顺序与文案）。新增/删除中文条目而不同步英文 → 构建直接失败并列出差异键名。

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

    // 模块色（固定用途）
    val store: Color       // 商店
    val chat: Color        // 乐奇聊天
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
    override val chat: Color = Color(0xFF6BE86B)
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

AIUI 页面（`.ink` 智能体）运行在眼镜端 `@yodaos-pkg/ink` 的 quickjs-wasm 沙箱里，与 WebView 主 realm 隔离。原本只能渲染不能调用外部能力。v3.5 引入 Lab 工具桥，让页面可以调用手机端工具（`ToolRegistry` 全量 34 个中除自指递归的 `open_aiui_app` 外全部开放，即 33 个，涵盖音乐 / 天气 / 搜索 / 提醒 / 设备信息 / 电话 / 日历 等）。

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
| `phone-app/src/main/java/.../ai/ToolGateway.kt` | 手机端统一工具网关，单一入口调度 `ToolRegistry.execute`（执行前先过 `ApprovalGate.preExecute(AIUI_PAGE, …)`） |
| `phone-app/src/main/java/.../ai/approval/ApprovalGate.kt` | **工具调用唯一审批入口**：合成 guard → 解析 Ask → 审计（详见 §8.2）。原 `ToolPolicy.kt` 已并入此类 |
| `phone-app/src/main/java/.../ai/approval/ToolGuards.kt` | 5 个内置策略源：页面准入 / 未知名 / per-source 限流 / 本机模式眼镜依赖 / 风险确认 |
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
7. **执行前先过 `ToolPolicy.check`**（v3.5 加入）：以 `SOURCE_AIUI_PAGE` 来源做 30/min 限流 + 风险闸门 + 审计；`EXTERNAL_SIDE_EFFECT` 工具在确认通道不可用/超时时按 **fail-open 放行**（工具自身只做无副作用动作），仅用户显式取消才 `Deny` —— 详见 §8.2。`isEnabled` 总开关仍是页面侧唯一的一键断能兜底。

### 6.5 调试要点

- **WebView 未开启远程调试**：页面 / 宿主的 `console.log` 在 logcat 里看不到，全部走 `window.Android.log(msg)` → `AiuiLinkActivity.bridge.log()` → logcat（tag=`AiuiLink`）
- **`host.js` 主 realm vs 页面 realm**：ink 沙箱页面 realm 看不到主 realm 的 `window.Lab` / `window.Android`（实测 `typeof=undefined`），必须靠 `lab-page-bridge.js` 注入到 `app.js` 前置
- **同步 vs 异步**：`__lab/tool_call_sync` 同步阻塞 fetch 拿结果（页面侧无需轮询），`__lab/tool_call` 异步 fetch 返回 202 + 后续 `onMessage` 回传（兼容旧桥，新代码不用）
- **`toolResult` 双路径下发**：手机端执行完既走 `CMD_AIUI_MSG` → `dispatchHostMessage`，又写入 `toolResults` 缓存（页面 realm 桥可 `fetch('/__lab/tool_result?cbId=...')` 轮询拉取，30s TTL）

---

## 7. Agent 核心架构（v3.5 升级）

### 7.1 CxrLHiRokidSession 拆分

`CxrLHiRokidSession` 曾是最大的 god class（v3.4 前 3550 行，v3.5 首轮拆分后 2780 行）。v3.5 六层重构（Phase 3~5）把它继续拆成 **L3 `domain/` 领域服务 + L2 协调器** 两组，现为 **887 行**的薄路由层：

| 新类 | 职责 |
|---|---|
| `domain/ConnectionService` | 连接编排（connectAnd* 系列） |
| `domain/AuthorizationService` | SDK 内部权限补齐 |
| `domain/DeviceControlService` | 设备控制域 |
| `domain/AiConfigService` / `domain/AiConversationService` | AI 配置 / AI 对话（含 AI 下行主链路） |
| `domain/AiuiHostService` | AIUI 宿主域 |
| `domain/MirrorCoordinator` | 投屏/长连接通道协调 |
| `domain/FileTransferService` | 文件传输 |
| `domain/PhotoQuizService` | 拍照问答（编排 `PhotoQuizFlow`） |
| `glasses/AsrBridgeCoordinator` | 双通道 ASR（SDK + link）去重、控制标记、下行 ping |
| `glasses/AiuiFrontendController` | 整个 AIUI 微前端 pipeline（push .aix、open/close/msg、launchParams） |
| `glasses/PhotoQuizFlow` | 拍照 → OCR → RAG → AI 答案 |

`CxrLHiRokidSession` 保留**每一个 public method 作为委派 facade**（如 `photoQuizService` / `aiConfig` / `connection` / `deviceControl` / `aiConversation` / `aiuiHost` 等字段），调用方完全不动。同时把持有的 Activity 改为 app context，需要 lifecycle owner 的地方改用 `WeakReference`（leak fix）。

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

- 跨请求多轮历史，**按对话独立保存**（`agent_sessions/<sessionId>.jsonl` 事件流），重启不丢
- v3.5：超长上下文被裁剪时，前缀折叠为 ≤800 字的 pinned system message `[summary of earlier conversation]`，跨数小时长会话不再失忆
- 上限随模型上下文窗口浮动（默认 12 条 / 6000 字符，大窗口按份额放宽并受天花板约束），超出时把最旧一轮压进置顶滚动摘要
- **开关是纯开关**：关 = 不注入也不记录，但**不销毁**已积累的数据，重新开启后仍然生效
- **没有"空闲自动过期"**：原先的「10 分钟无活动自动清空」（`maybeExpire`）已于 2026-09-20 移除 —— 它与「聊天记录永久保存」的用户心智正面相抵，而且是静默发生的
- 清空分两级作用域，别混：聊天页「清空对话」= **当前对话**的记录＋记忆（`clear()`）；设置页「清空全部会话记忆」= **全部对话**的记忆（`clearAll()`，一个字节都不碰聊天记录）

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

- v3.5：BM25-style IDF 评分；命中条目带 provenance（`docName/chunkIdx`），AI 回答可引用来源
- **导入必须经 `TextEncoding` 判定编码**（2026-09-20 修）。中文用户的 txt 常是 `ANSI(GBK)` / `Unicode(UTF-16)`，
  按 UTF-8 硬解会整篇变成 U+FFFD，而**文件名与字节数照旧正确** ⇒ 症状是"导入看着成功、里面的内容永远问不出来"。
  判定顺序：BOM → 无 BOM 的 UTF-16（NUL 密度 + 奇偶位）→ 严格 UTF-8 试解 → `GB18030` 兜底。
  ⚠️ 严格 UTF-8 试解必须容忍**样本尾部被切断的多字节字符**，否则会把正常 UTF-8 文件误判成 GB（反向破坏）。
  ⚠️ `docs` 表 v2 新增的 `charset` 列走 `ALTER TABLE` —— **`onUpgrade` 绝不 drop 重建**（那等于"升级即清空用户资料"）。
- **双通道检索**：对话主循环**自动检索 top-2** 注入 system 提示词；**未命中时也要把"库里有哪些文档"告诉模型**
  （否则模型连知识库存在都不知道，更不会去调 `search_knowledge_base`）；`search_knowledge_base` 空结果会返回
  文档名清单让模型换关键词重试。检索 token 上限 32（每次 token 一次全表 LIKE），评分与 LIKE 同为**大小写不敏感**。
- **管理弹窗（`KbManageDialog`）显示块数 + 编码，点开预览正文** —— 让"导入到底进去了什么"可见：
  仅看文件名/大小是**看不出乱码**的，这正是该 bug 能潜伏的原因。
- 同一套解码也用在 `FileWorkspace.readTextFile`（用户文件同样是 GBK 的高发区）。

**SSE 重连（`OpenAiService`）**：

- v3.5：重连条件从「已开始」收紧为「已发出内容」
  - 无内容时重放是安全的，工具增量可重发
  - 已发出内容后重连会导致重复，所以不重连
- 指数退避 500ms × 2^attempt，上限 4s
- 远程重试从 2 次升到 3 次

---

## 8. 手机端六层架构（v3.5 重构）

### 8.1 分层与依赖方向

```
L5  app/ · feature/ · store/(UI)   UI 与入口 + 手动 DI 容器 AppContainer
L4  ai/                            Agent：ToolRegistry + tools/ Provider + approval/ + llm/ + compaction/ + ToolRisk
L3  domain/                        领域服务（Connection / Authorization / DeviceControl /
                                   AiConfig / AiConversation / AiuiHost / MirrorCoordinator /
                                   FileTransfer / PhotoQuiz）
L2  glasses/                       会话层：状态与协议（CxrLHiRokidSession / LinkProtocol /
                                   GlassesHandshake / AsrBridgeCoordinator /
                                   AiuiFrontendController / PhotoQuizFlow）
L1  connection/                     通道与仲裁（ChannelArbiter / ConnectionRouteManager）
L0  platform/                       能力与 hook 适配（CapabilityProbe / SdkBridge / SdkFieldMap /
                                   HidBridge / AdbTransport / ShellOps / RomAdapter / AvrcpLyricBridge）
```

约束：**每一层只依赖下一层**。UI（L5）不 import 传输层（L1），更不 import 反射（L0）；`platform/` 是全仓唯一允许出现 `getDeclaredField` / `Class.forName` / `setAccessible` 的包，SDK / ROM 升级只改这一个包。跨 feature 共享的长生命周期对象统一由 `app/AppContainer`（手动 DI，不引 Hilt）装配，feature 层禁止自建。

> 包名以代码为准：会话层落在 `glasses/`、通道层落在 `connection/`，分别对应架构设计稿里的 `session/` 与 `transport/`。

### 8.2 工具按域拆分与风险闸门

**注册（`ToolRegistry`）**：全量 **46 个工具 / 12 个域**（info / knowledge / glasses / timer / media / display / web / files / aiui / phone / research / **vision**；另有 `mcp` 域承载运行期动态工具，见 §8.2.1）。域集合决定会话装配：

| 会话 | 装配域 | 说明 |
|---|---|---|
| 主 Agent（在线模型） | `SESSION_AGENT_DOMAINS` = 全部域 | 眼镜语音 / 手机聊天 |
| 本地模型 | `SESSION_LOCAL_DOMAINS` = 空集 | 本地小模型背不动数十个 schema |
| AIUI / 代码生成 | `SESSION_AIUI_DOMAINS` = aiui + files + info | 命中技能后切到子集省 token |

**执行（`ai/tools/`）**：`ToolRegistry.execute` 解析参数后按 `toolNames` 路由到 13 个**静态** `ToolProvider` 之一（Info / Knowledge / Glasses / Timer / Media / Display / Web / Files / Aiui / Phone / Status / **Subagent** / **Vision**；接口 `ToolProvider.kt` + schema `ToolSchemas.kt`），外加 1 个**动态** provider（`McpToolProvider`，工具集运行期才知道，见 §8.2.1）。
**新增工具 = 在对应 Provider 的 `tools()` 里加一条 `ToolEntry` + 在 `execute` 加一个分支**（风险档/副作用/要眼镜/过程文案都在那条声明里，六张表由它派生）；网关 / 协议 / JS / skill 全不动。
⚠️ 但**模型可见文案仍是手工同步的**：`OpenAiService` 系统提示词、其他 schema 的交叉引用、`assets/skills/aiui-dev/lab-runtime.md`。漏了这些不会编译错，只会让模型不知道该在什么时候用这个工具。
⚠️ **改完必跑** `skills/rokidlab-chat-standalone-mode/scripts/check_tool_wiring.py`：它守住「每个 provider 的 `toolNames` ↔ `tools()` 双向相等」「声明必带 risk/schema/statusText」「字符串资源存在」等自洽性。工具名支持三种写法（字面量 / `接收者.常量` / 同 object 内**裸常量名**）。

**2026-09-20 补齐的三块对话能力**（此前是缺口，不是配置问题）：
- **`look_at_view`（`vision` 域）**：把眼镜相机暴露成模型可主动调用的工具 —— 此前模型对「看看面前有什么」**无工具可调**，只能编出「没有相机权限」。出图两条路径：确认模型支持看图 ⇒ base64 暂存 `ThreadLocal`，由**对话主循环**补一条带 `image_url` 的 user 消息交给主模型；否则兜底本地 OCR 转文字。⚠️ **不可**在工具内回调 `sendAiTextMessage`（`aiSendLock` 非重入 ⇒ 死锁）。
- **文件工作区（`ai/FileWorkspace.kt`，新增 6 个文件工具）**：`list_files` / `read_text_file` / `search_files` / `edit_text_file` / `delete_file` / `move_file`。两个边界 —— `project`（`filesDir/aiui_projects/<项目>/` 私有镜像，全能力）与 `downloads`（MediaStore，只列/读/删）。**无 `MANAGE_EXTERNAL_STORAGE` ⇒ 不承诺"读任意文件"**，边界必须写进各工具 schema。
- **技能管理三件套（伪工具）**：`install_skill` / `list_skills` / `delete_skill`，可在对话里装/看/删技能（此前只能进设置页）。执行体复用既有 `installFromMarkdown` / `installFromZip` / `listSkills` / `delete`。

**外部 MCP（动态工具集，v3.9 新增）**：`ai/mcp/`（`McpClient` 传输 / `McpServerStore` 落盘 / `McpRegistry` 注册与分发）+ `ai/tools/McpToolProvider`；设置页在「乐奇聊天 → 设置 → 外部 MCP」。
连接链路：`McpClient.initialize`（读 `Mcp-Session-Id`）→ `tools/list`（分页）→ 每个工具过 `ToolSchemaValidator`，**不合规的整个丢弃**（服务端 schema 校验是整请求级，1 个坏节点会让整轮 400、全部工具失效）。
⚠️ **`McpToolProvider` 不写进 `ToolRegistry.providers` 静态列表**（那是 `object` 初始化时求值的编译期常量，装不下运行期数据），而是由 `McpRegistry.rebuildIndex()` 调 `ToolRegistry.setDynamicProviders()` 注入 —— **漏调的表现是「设置页看得见新工具、模型却永远不调」且不报错**。
⚠️ 安全**靠准入，不靠审批闸门**（闸门 fail-open，见 §8.2）：地址必须 https（`network_security_config` 禁明文；**唯一例外＝回环 http**，见下）、工具首次出现时显式写 `false`、信任标记由用户显式给出。单 server 上限 30 个工具（每个 schema 都进**每一轮**请求）。
> ⚠️ **回环例外不要照抄成「按 `BuildConfig.DEBUG` 放开 http」**：debug 变体的 `network_security_config`
> 被 `src/debug/` 整体覆盖成 `cleartextTrafficPermitted="true"`，那样写就等于「调试能跑、发布才挂」。
> 判据是**两份变体的白名单交集**：回环（`localhost` / `127.0.0.1` / `::1`）两边都有 ⇒ 行为一致，故只放它。
> 判定只有一处 —— `McpRegistry.isUrlAllowed()`，UI 必须复用它而不是自己写 `startsWith`。

**风险分级（`ToolRisk` / `ToolRiskMap`）**：

| 档位 | 含义 | 例子 |
|---|---|---|
| `READ_ONLY` | 纯读取 | `get_weather` / `search_knowledge_base` |
| `LOCAL_SIDE_EFFECT` | 本机可控/可撤销副作用 | `set_timer` / `set_phone_volume` / `call_phone` |
| `EXTERNAL_SIDE_EFFECT` | 不可撤销，必须过确认闸门 | `delete_file`（删除不可恢复） |

未登记兜底：**真实工具的漏登记在结构上已不可能** —— 风险档是每个工具自己声明结构体（`ToolEntry.risk`）里的必填字段，跟工具定义写在一起；**完全未知的名字**（模型幻觉/攻击构造）→ `EXTERNAL_SIDE_EFFECT`（最保守），但它会先被 `UnknownToolGuard` 单调拒绝，不会触发 35 秒确认。

**策略闸门（`ApprovalGate.preExecute`，唯一审批入口）**：每次工具执行（AIUI 页面路径 / 对话路径 / 伪工具）都先过它：

1. 页面准入（仅 AIUI 页面）：域白名单 + 黑名单
2. 未知名：既不是真实工具也不是伪工具 → 单调拒绝（旧实现会让它走风险兜底、白等一次确认）
3. per-source 滑动窗口限流：AIUI 页面 30/min（防页面死循环刷工具）、对话路径 120/min（兜底失控循环）—— **被拒的调用不扣配额**
4. 本机模式（仅对话路径）：用户开了「本机模式」时，需要眼镜的工具单调拒绝
5. 风险闸门：`EXTERNAL_SIDE_EFFECT` 必须经用户确认（内置工具当前**只有** `delete_file` 为此档；外部 MCP 工具默认为此档，但闸门 fail-open ⇒ 不等于真会弹确认，见下）
6. 审计：每次决策打一行日志（ALLOW/DENY + `[ORIGIN]` + 原因），系统日志面板（`LogCollector`）可观测；TAG 为 `ApprovalGate`

合成规则（`ApprovalGate.compose`）：任一 guard 返回 `Deny` **立即短路**（单调最终拒绝，对齐 DSH 的 `ctx.tools.guard()`）；否则取**第一个** `Ask`；都不表态则 `Allow`。

**确认通道（`GlassToolConfirmChannel`）**：实现 `ApprovalGate.ConfirmResolver`。下行 `LinkProtocol.TOPIC_TOOL_CONFIRM`（caps = [requestId, 工具名, 摘要]），眼镜端显示摘要 + TTS 播报，短按 = 允许 / 双击·长按 = 取消，眼镜端 30s 超时视为取消；上行 `LinkProtocol.TOPIC_TOOL_CONFIRM_RESULT`（caps = [requestId, "yes"/"no"]）。通道**常驻**（`GlassToolConfirmChannel.global`），会话上线 `bind`、下线 `unbind`，`ApprovalGate.confirmationResolver` 指向它。摘要由闸门随 `confirm(prompt)` 传入，**产地是工具自己的 `ToolEntry.summarize`**。

> ⚠️ **降级语义（务必如实理解）**：确认通道**不可用**（会话不在线，或眼镜端旧版经能力握手判定不支持）或确认**超时未响应**时，`ApprovalGate.resolveAsk` 返回 **`Allow`**（fail-open），由工具自身在未获确认时只做**无副作用动作**（如 `call_phone` 只打开拨号盘、绝不自动拨出）；只有用户**显式取消**（`wasCancelled()` 为 true）才 `Deny`。
> 这条语义的**唯一产地**是 `ApprovalGate.resolveAsk` 的 KDoc 与其实现（`ToolPolicy` 已删除，旧的「类注释写降级为拒绝、实现却是放行」的矛盾已消除）。
> ⚠️ 另需知道：**内置工具里只有 `delete_file` 是 `EXTERNAL_SIDE_EFFECT`**（2026-09-20 起，删除不可恢复故走确认闸门；`check_tool_wiring.py` 与 `ApprovalGateTest` 的 E1/E3b 一起钉住这条事实）。要让别的内置工具也走眼镜确认，只需在它的 `ToolEntry.risk` 里声明该档，**并同步补一条 E3b 式的确认用例**。
> ⚠️ **外部 MCP 工具是唯一会被登记为该档的**（未标「信任」时），但**这不等于多了确认步骤** —— 闸门 fail-open ⇒ 无通道时照样放行。因此第三方工具的保护必须落在**准入**上（https-only + 默认关 + schema 全过校验），详见 §8.2 的「外部 MCP」段。

### 8.3 通道仲裁 ChannelArbiter

手机侧蓝牙栈对「同一设备 + 同一 SCN」只允许一条客户端 RFCOMM 通道。此前由各消费方手工 `reserveTunnel() / releaseTunnel()`（一个 `AtomicInteger` 计数）保证，分散在多个 Activity/Service + 兜底轮询里，漏调用 `release` 即永久泄漏且无从观测。`connection/ChannelArbiter` 把它收敛为「按优先级持有租约」：

| 优先级 | 用途 | 让路接入点 |
|---|---|---|
| `BACKGROUND` | ASR 兜底轮询（断连期间积压文件补读）：更高优先级占用时应让路 | `CxrLHiRokidSession` 的 `adbClientProvider` 内 `shouldYield(BACKGROUND)` |
| `NORMAL` | ADB 工具页、AI 工具查询、AIUI 工具、定时任务等常规控制面 | `CxrLHiRokidSession.getAdbShellClient()` 内 `shouldYield(NORMAL)` |
| `LONG_LIVED` | 屏幕镜像 / 手机投屏 / 文件浏览：需长时间独占 RFCOMM SCN | 由 `domain/MirrorCoordinator` 取租约 |

- `acquire(owner, priority)` **非阻塞、必成功**，返回 `ChannelLease`（`close()` 幂等，重复调用安全）
- `shouldYield(priority)`：存在**严格更高**优先级持有者时为 true → 调用方自行退避（与 `AsrBridgeCoordinator` 的退避语义一致）；同级互不让路
- **只有 `LONG_LIVED` 需要 `acquire()`**：`NORMAL` 各消费者（ADB 工具页 / AI 工具 / AIUI 工具 / 定时任务）共享同一条 ADB 会话（`platform/AdbTransport` 为唯一所有者），彼此互不让路，持常驻租约只会让 `BACKGROUND` 兜底轮询被误伤
- `snapshot()` 暴露当前持有者；`LONG_LIVED` 持有超 **10 分钟**打「疑似未释放」告警
- 设计取舍：这是**协作式闸门**而非互斥锁，避免长连接建链期被锁阻塞
- 与 L0 `platform/AdbTransport` 的分工：`AdbTransport` 管「会话所有权 + 串行化」（谁在用 socket、何时重建/释放），`ChannelArbiter` 管「优先级让路」；长连接上场前需**同时**做两件事 —— 取 `LONG_LIVED` 租约 + `releaseAdbShellClient()` 腾出 RFCOMM

### 8.4 双端协议同源与能力握手

**LinkProtocol（v2）**：把散落的「裸 CXR 频道名」和 `__LAB_*` 控制帧标记收敛为常量，手机端 `phone-app/.../glasses/LinkProtocol.kt` 与眼镜端 `RokidLink/.../rokidlink/LinkProtocol.kt` 是**双端同源副本**（除 package 行与空行外逐字节一致）。

- **构建期守护**：`phone-app/build.gradle.kts` 注册 `checkProtocolSynced`，校验 `LinkProtocol.kt` + `AiChannel.kt` 两端同源，并**禁止业务文件出现裸协议字面量**，挂在 `preBuild` 上（手机端构建即拦截）。眼镜端独立构建，不参与该校验 —— 改协议常量后务必手动同步两端。
- **另一道 preBuild 门禁**：`checkI18nKeysSynced`，校验 phone-app 与 RokidLink 两模块 `values/` ↔ `values-en/` 的 `<string name>` 集合完全相等（见 §1.1 多语言）。
- **第四道 preBuild 门禁**：`checkNoBareCatch`，扫描双端 `src/main` 全量空 catch，未带 `// catch-ok: <原因>` 标注的数量以 `bareCatchBudget = 47` 为**棘轮预算**（只降不升，新增即失败，见 `RULES.md` §12.14）。**四道门禁都挂在 `preBuild`**，本地构建即拦截；出 release 包另有两道 `packageRelease` 发布闸门（详见 §8.5 末）。
- **常量**：频道名 `CXR_CHANNEL_AI / SYS / WIFI / JSAI / AI_RENDER`；标记 `MARKER_MUSIC_STOP` / `MARKER_ABORT_AI` / `MARKER_PHOTO_ASK` / `MARKER_TOOL_CALL`(`"__LAB_TOOL__"`) / `MARKER_ASR_READY`；握手 topic `TOPIC_HELLO` / `TOPIC_HELLO_REQ`；确认 topic `TOPIC_TOOL_CONFIRM` / `TOPIC_TOOL_CONFIRM_RESULT`
- **能力位 `Cap`**：`TOOL_CONFIRM` / `LYRIC_OVERLAY` / `AIUI_HOST` / `SELF_HEAL_PING` / `SHOW_IMAGE` / `OPEN_APP`，`PROTOCOL_VERSION = 2`（v1 为无能力协商的旧眼镜端），`Cap.ALL` 为当前版本默认能力全集

**GlassesHandshake 三态**：眼镜端服务就绪时主动上报 `TOPIC_HELLO`（caps = [version, capsBitmask, linkVersion]）；手机端连接建立后也会下发 `TOPIC_HELLO_REQ` 主动询问（覆盖「眼镜端后启动」场景）。`supports(bit)` 的返回值语义：

| 返回值 | 状态 | 调用方策略 |
|---|---|---|
| `null` | 尚未握手，能力未知 | **乐观**：按支持处理，失败再兜底 |
| `true` | 已握手且支持该能力 | 正常路径 |
| `false` | 已确认不支持 | **快速降级**：立即拒绝，不再空等超时 |

旧版眼镜端（v1）不应答握手：手机端发出请求后 **4s**（`LEGACY_DETECT_DELAY_MS`）仍未收到通告即 `markLegacy()` 判为旧版 —— 避免每次工具确认都空等 35s。断开 / 销毁时 `reset()` 复位，下次连接重新握手。

### 8.5 单元测试与回归护栏

v3.5 起测试从「只覆盖最好测的纯逻辑层」扩到**历史真出过 bug 的高危文件**，当前 **14 个测试类 / 155 个 `@Test`**（phone-app 150 + RokidLink 5）。

```powershell
# 在仓库根 d:\rokidapp 执行
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.11.9-hotspot"
& D:\gradle-8.7\bin\gradle.bat :cxrl:RokidLab:phone-app:testDebugUnitTest :cxrl:RokidLab:RokidLink:testDebugUnitTest --offline
```

| 测试类 | 例数 | 锁住什么 |
|---|---|---|
| `adb/AdbSyncProtocolTest` | 15 | `AdbShellClient.pullFile` 的 `FAIL` / `CLSE` / 读流异常分支（历史 A4 事故：远端 FAIL 被当成功 → 产出 0 字节文件）+ 提取残包回归锁（未 `DONE` 断流 / 字节数不符 / 畸形帧）与分片收尾语义（`pullFileSharded`） |
| `adb/AdbFileManagerSyncTest` | 6 | `AdbFileManagerClient` sync 帧编解码 + `drainStalePackets` 陈旧 CLSE 排空 + `parseDateTime` |
| `hid/HidReportTest` | 12 | `BtHidCompat.normalize` 截断/补零/未声明返 null、`declaredLength` 全矩阵、描述符 Report ID 与 `declaredReportIds` 交叉校验 |
| `glasses/AiChannelTest` | 21 | `AiChannel` 跨端载荷 v0/v1 矩阵 + 常量名稳定性（改名即断双端） |
| `ai/ToolRiskMapTest` | 3 | `ToolRiskMap.unregisteredTools()` 必须为空（§12.10）+ 风险表 / 无人值守只读名单 |
| `ai/approval/ApprovalGateTest` | 35 | 合成语义（Deny 短路 / 首个 Ask 胜出 / null 不表态）+ 伪工具已知 + fail-open 三态 + per-source 限流 + 页面准入文案 + 风险表三消费者 |
| `store/ChatHistoryStoreTest` | 14 | 聊天历史 JSONL 落盘格式：同 id 后写覆盖先写、**旧版 JSON 数组迁移不丢历史**、崩溃截断半行不毁历史、`clear()` 删文件 |
| `RokidLink/AiChannelProtocolTest` | 5 | 眼镜端能解析手机端下发的 v1 载荷（边界矩阵留在 phone-app 侧，避免重复维护） |

**写测试的三条硬约束**（详见 `RULES.md` §12.15）：

1. **执行位置**：必须在仓库根 `d:\rokidapp` 跑（`RenewCXRLSample`，任务名 `:cxrl:RokidLab:phone-app:*`）。`cxrl\RokidLab` 不再是独立 Gradle 根（其 `settings.gradle.kts` / `gradle/libs.versions.toml` 已删除），在该目录执行会沿目录树上溯命中根构建，任务名必须带 `:cxrl:RokidLab:` 前缀。
2. **Android 桩**：`phone-app` 已开 `testOptions { unitTests.isReturnDefaultValues = true }`（否则 `Log` 一碰即抛 `RuntimeException("Stub!")`）；ADB 客户端用 `internal fun attachStreamsForTest(input, output)` 注入脚本化对端（`AdbTestPeer.kt`），无需真机与 socket。
3. **改字节必改测试**：动 HID 描述符 / sync 帧格式 / 跨端载荷格式的提交必须同步更新测试；#8 实测已推翻 `buildQtiCompatibleDescriptor` 旧注释声称的「≤ 64 字节」，实为 **67（无 Mouse）/ 121（含 Mouse）** 字节。

> 尚未覆盖：`KeyButtonService` / `CxrLHiRokidSession`；`androidTest` 为 0。
> ⚠️ `ChatStateHolder` **本体现在可测**（见 `store/ChatStateHolderTraceTest`）：`isReturnDefaultValues = true`
> ⇒ `Looper.myLooper()` 与 `getMainLooper()` 同为 null、`runOnMain` 走内联分支；`appContext` 为 null ⇒ 落盘静默跳过。
> 写这类测试要在 `@Before`/`@After` 清 `messages` 并 `finishTrace()` 释放锚点（单例跨用例复用）。

**构建期门禁（四道，挂 `preBuild`，本地构建即触发）**：`checkProtocolSynced`（双端协议同源 + 禁裸协议字面量）、`checkI18nKeysSynced`（两模块 zh↔en key 集合相等）、`checkKeyPathEmptyCatch`（6 个关键链路文件空 catch 零容忍）、`checkNoBareCatch`（全仓空 catch 棘轮预算 47，只降不升）。

**发布闸门（两道，挂 `packageRelease`，双端共用 `gradle/local-gates.gradle.kts`）**：`checkGitClean`（`git status --porcelain` 必须为空，否则出包即失败；临时验证可 `-PallowDirtyWorktree=true`）+ `packageRelease` 依赖 `testDebugUnitTest`（单测没绿出不了包）。

> **为何不建托管 CI**（#10 结论）：四道门禁已挂在 `preBuild`，本地每次构建都会跑，托管 CI 属重复执行；且 Android CI 需复刻 SDK/NDK/16KB 校验环境、Gitee Go 免费额度有限，而本项目是单作者、唯一发布路径就是本地构建 —— 收益为负。真正缺的「出包必跑测试 / 脏工作区不许出包」用两道 Gradle 闸门即可闭合。

### 8.6 聊天历史落盘与线程模型

**问题**（#9 修复前）：`ChatStateHolder.add()` 在**主线程**调 `persist()`，而 `persist()` 把**整个列表**重新 JSON 序列化后 `writeText` —— 每条消息都是 O(n) 写盘（累计 O(n²)），长会话下卡顿掉帧；且 `clear()` 只写空数组、不删文件。

**现状**：格式与回放逻辑抽到纯 JVM 的 `store/ChatHistoryStore.kt`（可单测），`ChatStateHolder` 只负责"何时写、在哪个线程写"。

```
调用线程（Compose 主线程）                单线程 daemon: chat-history-writer
─────────────────────────────            ───────────────────────────────────
add / addImage / finalizeLastAi
   ├─ messages.add / 替换（快照列表）      
   ├─ ChatHistoryStore.toLine(msg)   ──►  appendLine(file, line)   ← JSONL 追加一行
   └─ writer.execute { ... }              
                                          
init(context)（Application.onCreate）
   └─ writer.execute {                    
         readHistory(file)                ← 读盘
         rewrite(file, 每 id 一行)         ← 旧格式迁移 + 压实
         mainHandler.post { applyLoaded } ─► messages.addAll(...)  （回主线程）
      }
```

- **格式**：JSONL，每行一条完整消息；**同 id 后写覆盖先写**（`finalizeLastAi` 用一行"覆盖行"修正流式消息，位置不变）。
- **迁移**：旧版"整份 JSON 数组"的 `chat_history.json`（首字符 `[`）在首启被识别并重写为 JSONL，**不丢历史**。
- **容错**：崩溃留下的半行 / 非法行被跳过，其余历史照常恢复。
- **不变量**：`SnapshotStateList` 只在主线程读写；后台任务只碰字符串；`clear()` 必须删文件。
- ⚠️ **一轮的「过程」落在哪条消息上由 `ChatStateHolder` 内部的 `traceAnchorId` 锚点决定，不看位置**。
  历史教训（2026-09-20 真机 bug「图片已经显示出来了，过程里还在思考」）：原先按"列表末尾那条
  非用户非状态消息"定位，而**位置不是身份** —— 一轮进行中只要**别的**消息被追加，"末尾"就换人，
  同一个 `tool:<call_id>` 的 `RUNNING` 留在旧气泡、`OK/FAILED` 写进新气泡；`AgentStep` 的
  「同 key 覆盖」只在单条消息内生效 ⇒ 覆盖失效，旧气泡**永久转圈**。
  两条**互相独立**的插入路径都踩过（与调用了哪个工具无关）：① `show_image` 的图片气泡；
  ② 拍照流程的状态气泡（`onStage` → `add` / `onStageText` → `updateLastStatus` 在末尾不是状态气泡时会**新增**一条）。
  ⇒ 现在新增"轮中途插消息"的能力不必再动过程代码，但**禁止**把落点改回"取末尾那条"。
- ⚠️ **收尾只结清锚点那一条**（`finishTrace`）。**不要**改成"扫全表把见到 `RUNNING` 的都标 OK" ——
  那是拿兜底盖症状（会把"这里为什么会有残留"一起抹掉）。锚点制让残留**结构上不再产生**。
- ⚠️ **回放时的 `RUNNING` 归一是崩溃修复，不是本 bug 的解法**：正常收尾必写终态，所以盘上
  还留着 `RUNNING` 只可能是那一轮被杀进程（`addImage` 会在轮中途带着 `RUNNING` 落一次盘，
  之后才被终态覆盖）。别拿"读的时候会兜住"当理由省掉收尾。
- ⚠️ **「过程」卡片显示的是用户视角的名字，落盘的仍是标识符**：`AgentStep.title` 对工具步骤存的是
  模型的 wire name（`get_current_time` / `mcp__<serverId>__<工具名>`），**禁止把显示名写进去**
  （会污染历史数据：改名/换语言后旧消息不跟着变，也断掉与日志的对应）。显示名由 UI 在渲染时查表：
  `ChatBubble.stepText()` → `ToolRegistry.displayNameOf()` —— 就是**工具设置页那一套名字**
  （静态工具取 `ai_tool_*_name`、MCP 取 `"服务器名 · 原始工具名"`），查不到**原样回退**。
  ⇒ 旧历史消息无需迁移即自动变成友好名；代价是界面名 ≠ 日志名，要原始标识符排查走 `SessionTraceDialog`。
  思考行的两态文案（"正在思考" / "思考完毕"）在 `ChatBubble.stepLabel()` 本地化，服务层只发语义。

> 约束详见 `RULES.md` §12.16；回归测试见 `store/ChatHistoryStoreTest`（24 例）+ `store/ChatStateHolderTraceTest`（6 例）。
> ⚠️ `ChatStateHolder` 本体可测（`isReturnDefaultValues = true` ⇒ `runOnMain` 内联、`appContext` 为 null ⇒ 落盘跳过）；
> 写这类测试必须在 `@Before`/`@After` 清 `messages` 并 `finishTrace()` 释放锚点（单例跨用例复用）。
