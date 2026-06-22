# RokidLab 开发者文档

## 目录

1. [语言包与翻译](#1-语言包与翻译)
2. [主题系统](#2-主题系统)
3. [蓝牙手柄开发指南](#3-蓝牙手柄开发指南)
4. [眼镜端游戏开发](#4-眼镜端游戏开发)

---

## 1. 语言包与翻译

### Crowdin 翻译平台

RokidLab 使用 Crowdin 进行社区协作翻译。

**翻译提交地址：**
```
https://crwd.in/rokidlab/de7a762ecacaa13d21021e1b5f66b0132805017
```

### 语言文件位置

- 中文（默认）：`phone-app/src/main/res/values/strings.xml`
- 英语：`phone-app/src/main/res/values-en/strings.xml`

### 添加新语言

1. 在 `LocalizationManager.kt` 的 `AppLocale` 枚举中添加新语言：

```kotlin
// d:\rokidapp\cxrl\RokidLab\phone-app\src\main\java\com\rokidlab\phone\util\LocalizationManager.kt

enum class AppLocale(
    val code: String,
    val displayName: String,
) {
    ZH_CN("zh", "简体中文"),
    EN("en", "English"),
    // 添加新语言：
    JA("ja", "日本語"),
}
```

2. 在 `res/` 下创建对应 values 文件夹和 `strings.xml`，如 `res/values-ja/strings.xml`，以英文版为模板翻译。

3. 在 Crowdin 项目中添加新语言，译者可直接在平台上翻译。

---

## 2. 主题系统

### 架构

主题系统由三部分组成：

| 文件 | 用途 |
|------|------|
| `BrewColors.kt` | 配色接口，定义所有颜色字段 |
| `VelvetDarkColors.kt` | 默认暗色主题实现 |
| `CoolBlueColors.kt` | 浅蓝主题实现 |
| `BrewThemeManager.kt` | 主题管理器，切换主题 |

### 颜色字段说明

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

### 添加新主题

**第一步：创建配色实现类**

在 `design/theme/` 下新建文件，实现 `BrewColors` 接口：

```kotlin
// d:\rokidapp\cxrl\RokidLab\phone-app\src\main\java\com\rokidlab\phone\design\theme\ForestGreenColors.kt

package com.rokidlab.phone.design.theme

import androidx.compose.ui.graphics.Color

object ForestGreenColors : BrewColors {
    // 底色系统
    override val bg: Color = Color(0xFF0A1A0F)
    override val panel: Color = Color(0xFF0F2214)
    override val panelAlt: Color = Color(0xFF142C1A)
    override val panelHi: Color = Color(0xFF1A3722)

    // 文字系统
    override val textBright: Color = Color(0xFFE8F0E8)
    override val text: Color = Color(0xFFC0D0C0)
    override val muted: Color = Color(0xFF809880)
    override val dim: Color = Color(0xFF405540)

    // 边框
    override val border: Color = Color(0xFF2A4A30)

    // 七模块七色
    override val store: Color = Color(0xFFE8914A)        // 暖橙
    override val mirror: Color = Color(0xFF4AE8B5)       // 翠绿
    override val projection: Color = Color(0xFFB58AFF)   // 紫罗兰
    override val fileManager: Color = Color(0xFFFFB84D)  // 琥珀
    override val adbTools: Color = Color(0xFF5CB8FF)     // 天蓝
    override val hidGamepad: Color = Color(0xFFFF6B8A)   // 粉红
    override val settings: Color = Color(0xFF8AB8D8)     // 钢灰蓝
}
```

**第二步：注册主题枚举**

在 `BrewThemeManager.kt` 的 `BrewTheme` 枚举中添加新条目：

```kotlin
enum class BrewTheme(val displayNameResId: Int, val descriptionResId: Int) {
    VELVET_DARK(R.string.theme_velvet_dark, R.string.theme_velvet_dark_desc),
    COOL_BLUE(R.string.theme_cool_blue, R.string.theme_cool_blue_desc),
    // 添加新主题：
    FOREST_GREEN(R.string.theme_forest_green, R.string.theme_forest_green_desc),
}
```

**第三步：关联配色到枚举**

在 `BrewThemeManager` 的 `currentColors` 属性中添加新分支：

```kotlin
val currentColors: BrewColors
    get() = when (_currentTheme) {
        BrewTheme.VELVET_DARK -> VelvetDark
        BrewTheme.COOL_BLUE -> CoolBlue
        // 添加：
        BrewTheme.FOREST_GREEN -> ForestGreenColors
    }
```

**第四步：添加多语言字符串**

在 `strings.xml` 和 `values-en/strings.xml` 中添加主题名称和描述：

```xml
<!-- values/strings.xml -->
<string name="theme_forest_green">森林绿</string>
<string name="theme_forest_green_desc">深绿自然主题</string>

<!-- values-en/strings.xml -->
<string name="theme_forest_green">Forest Green</string>
<string name="theme_forest_green_desc">Deep forest green theme</string>
```

**第五步：强制更新配色常量映射**

如果新增的模块色需要映射到功能色，检查 `StoreTheme.kt` 中的全局颜色快捷访问：

```kotlin
// 新增功能色映射（如需要）
val BrewGreen: Color get() = BrewThemeManager.currentColors.mirror  // 成功
```

**注意：** 功能色（`BrewRed`、`BrewSuccess` 等）默认映射到模块色，一般无需修改。如需独立控制功能色，需在 `BrewColors` 接口中新增字段。

---

## 3. 蓝牙手柄开发指南

### 连接方式

RokidLab 通过 Android `BluetoothHidDevice` API 将手机注册为蓝牙 HID 设备，眼镜作为 HID Host 接收输入。

```
手机（HID Device）──蓝牙──▶ 眼镜（HID Host）
```

### HID 描述符

手机端 HID 描述符包含 3 个 Report：

| Report ID | 类型 | 用途 | 眼镜端设备 |
|:----------:|:----:|:----:|:----------:|
| 2 | Consumer Control | ↑↓←→ / Select / Start | `event2` Consumer Control |
| 1 | Keyboard | A/B/C/X/Y/Z/L/R | `event3` Keyboard |
| 3 | Mouse | 鼠标模式 | `event4` Mouse |

### 按键映射表

#### ROKID 手柄模式（方向键走 Consumer Control，功能键走 Keyboard）

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

### 按键常量定义

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

## 4. 眼镜端游戏开发

### 推荐方案：键盘监听（兼容最好）

Rokid 眼镜的 Linux 内核 HID 驱动不支持标准 Gamepad Input Device。所有按键通过 **Keyboard** 和 **Consumer Control** 两个输入设备发送，眼镜端接收后转换为 Android 键码。

| 编程框架 | 监听方式 | 示例代码 |
|:---------:|:---------|:---------|
| **Unity** | `Input.GetKeyDown()` | `Input.GetKeyDown(KeyCode.Z)` |
| **Android View** | `onKeyDown()` | `KEYCODE_Z` (52) |
| **Android Compose** | `onKeyEvent` | `KeyEvent.Key.Z` |

### Android View 示例

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
            // ... 同上
            else -> super.onKeyUp(keyCode, event)
        }
    }
}
```

### Unity 示例

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

### 关键须知

1. **游戏手柄模式非标准 Gamepad**：Rokid 眼镜的 Linux 内核不支持 `Usage Game Pad (0x05)`，因此无法使用标准 Android Gamepad API（`KEYCODE_BUTTON_A` 等）。请使用键盘键码。

2. **Select 和 Start 键码相同**：两者都通过 Consumer Control 发送 `0x41 0x00`（MenuPick），眼镜端转换为 `KEYCODE_DPAD_CENTER (23)`。如需区分，建议通知用户将 Select 映射为"确定"，Start 映射为"暂停"。

3. **单键释放**：松开按键即发送释放报告（Consumer 和 Keyboard 同时释放）。

4. **鼠标模式**：RokidLab 在手柄界面提供"鼠标"标签页，触控板模式使用 Mouse Report ID 3，非游戏场景适用。

### 调试工具

眼镜连接电脑后可用 `getevent` 实时监控输入事件：

```bash
# 查看所有输入设备
adb shell getevent -p

# 实时监控键盘事件
adb shell getevent -l /dev/input/event3

# 实时监控 Consumer Control 事件
adb shell getevent -l /dev/input/event2
```

### 快速开始模板

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
            // 方向键
            KeyEvent.KEYCODE_DPAD_UP      -> onAction("UP", isDown)
            KeyEvent.KEYCODE_DPAD_DOWN    -> onAction("DOWN", isDown)
            KeyEvent.KEYCODE_DPAD_LEFT    -> onAction("LEFT", isDown)
            KeyEvent.KEYCODE_DPAD_RIGHT   -> onAction("RIGHT", isDown)
            // A → z → KEYCODE_Z
            KeyEvent.KEYCODE_Z            -> onAction("A", isDown)
            // B → x → KEYCODE_X
            KeyEvent.KEYCODE_X            -> onAction("B", isDown)
            // C → c → KEYCODE_C
            KeyEvent.KEYCODE_C            -> onAction("C", isDown)
            // X → a → KEYCODE_A
            KeyEvent.KEYCODE_A            -> onAction("X", isDown)
            // Y → s → KEYCODE_S
            KeyEvent.KEYCODE_S            -> onAction("Y", isDown)
            // Z → d → KEYCODE_D
            KeyEvent.KEYCODE_D            -> onAction("Z", isDown)
            // L → q → KEYCODE_Q
            KeyEvent.KEYCODE_Q            -> onAction("L", isDown)
            // R → w → KEYCODE_W
            KeyEvent.KEYCODE_W            -> onAction("R", isDown)
            // Select / Start
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
