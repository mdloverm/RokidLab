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
3. [眼镜端游戏开发](#3-眼镜端游戏开发)
   - [推荐方案：键盘监听](#31-推荐方案键盘监听)
   - [Android View 示例](#32-android-view-示例)
   - [Unity 示例](#33-unity-示例)
   - [关键须知](#34-关键须知)
   - [调试工具](#35-调试工具)
   - [快速开始模板](#36-快速开始模板)
4. [商店应用提交指南](#4-商店应用提交指南)
   - [提交步骤](#41-提交步骤)
   - [完整 JSON 示例](#42-完整-json-示例)
   - [字段详解](#43-字段详解)
   - [更新版本](#44-更新版本)
   - [注意事项](#45-注意事项)

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

## 3. 眼镜端游戏开发

### 3.1 推荐方案：键盘监听

Rokid 眼镜的 Linux 内核 HID 驱动不支持标准 Gamepad Input Device。所有按键通过 **Keyboard** 和 **Consumer Control** 两个输入设备发送，眼镜端接收后转换为 Android 键码。

| 编程框架 | 监听方式 | 示例代码 |
|:---------:|:---------|:---------|
| **Unity** | `Input.GetKeyDown()` | `Input.GetKeyDown(KeyCode.Z)` |
| **Android View** | `onKeyDown()` | `KEYCODE_Z` (52) |
| **Android Compose** | `onKeyEvent` | `KeyEvent.Key.Z` |

### 3.2 Android View 示例

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

### 3.3 Unity 示例

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

### 3.4 关键须知

1. **非标准 Gamepad**：Rokid 眼镜内核不支持 `Usage Game Pad (0x05)`，请使用键盘键码。
2. **Select 和 Start 键码相同**：两者都映射为 `KEYCODE_DPAD_CENTER (23)`。建议 Select 作"确定"，Start 作"暂停"。
3. **单键释放**：松开按键即发送释放报告。
4. **鼠标模式**：RokidLab 提供"鼠标"标签页，使用 Mouse Report ID 3，非游戏场景适用。

### 3.5 调试工具

```bash
# 查看所有输入设备
adb shell getevent -p

# 实时监控键盘事件
adb shell getevent -l /dev/input/event3

# 实时监控 Consumer Control 事件
adb shell getevent -l /dev/input/event2
```

### 3.6 快速开始模板

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

## 4. 商店应用提交指南

### 4.1 提交步骤

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

### 4.2 完整 JSON 示例

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

### 4.3 字段详解

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

### 4.4 更新版本

修改对应应用的以下字段：

1. `version` — 新版本号
2. `releases` — 追加新 release 记录
3. `artifacts[0].url` — 新 APK 下载地址
4. `artifacts[0].sizeBytes` — 新 APK 文件大小
5. `artifacts[0].versionCode` — 新 versionCode
6. `artifacts[0].sha256` — 新 SHA256（可选）

### 4.5 注意事项

- 远程注册表的 `apps` 数组只需维护**你的应用**，不需要包含全部应用
- 远程应用 `id` 与本地内置应用相同时，远程数据覆盖本地数据
- 手机端 RokidLab 切换商店源或下拉刷新时自动获取最新注册表
- 应用图标和截图建议托管在你自己的代码仓库中，用 raw URL 引用
