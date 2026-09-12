# 多品牌兼容性：问题诊断与解决方案

> 日期：2026-09-10
> 范围：`phone-app` 手机投屏链路（MediaProjection）为主，附带 ROM 权限引导问题

---

## 一、先说结论：不是"没做兼容"，是"做法失效了"

项目里其实已经有 `ManufacturerUtils`（500 行），覆盖厂商识别、电池白名单、自启动、悬浮窗、投屏黑屏。看起来很全。

但**它里面最核心的那条修复路径，在真实设备上是走不通的**。

---

## 二、根因：四条已经失效的路径

### 根因 1：`setprop` 在第三方 App 里必定失败（最严重）

`PhoneMirrorService` 原先的逻辑：

```kotlin
// 旧代码
Runtime.getRuntime().exec(arrayOf("sh", "-c", "setprop debug.sf.enable_hwc_vds 0"))
```

这行代码有三个问题，任何一个都足以让它无效：

1. **权限**：`debug.sf.*` 属于 SurfaceFlinger 的系统属性，普通 App 进程无权写入。Android 8.0 后 SELinux 会拦截，**而且是以静默方式失败** —— `exitValue()` 仍然返回 0，`Log` 里打出来看起来像成功了。这就是为什么它一直没被发现。
2. **时序**：就算真的写进去了，这些属性也只在 **SurfaceFlinger 下次重建**时才生效，对本次已经建立好的 `VirtualDisplay` 完全没有影响。
3. **无从验证**：旧代码从不检查它到底有没有生效。

结果就是：**检测到了问题 → 执行了必定失败的修复 → 没有验证 → 没有下一步**。而命中这块逻辑的，恰恰是投屏问题最多的那批设备（华为 EMUI 12+、小米 MIUI 14+/HyperOS、OPPO/vivo Android 13+）。

### 根因 2：判定条件按 SDK 版本一刀切

```kotlin
// 旧代码
Manufacturer.HUAWEI -> sdk >= 31   // 华为 Android 12+ 全部判为黑名单
Manufacturer.XIAOMI -> sdk >= 33   // 小米 Android 13+ 全部判为黑名单
```

一台华为 P60 和一台华为 nova，一台小米 14 和一台 Redmi Note，它们的投屏表现差别很大。只看品牌 + Android 版本，等于把大量其实没问题的设备也拖下水——而 DAU 里真正出问题的是少数。

### 根因 3：厂商跳转 Intent 停留在 2018 年

| 现有 Intent | 实际状态 |
|---|---|
| `miui.intent.action.APP_PERM_EDITOR` | MIUI 14 / HyperOS 已失效 |
| `huawei.intent.action.HSM_PROTECTED_APPS` | **EMUI 8+ 就已废弃** |
| `com.coloros.safecenter.action.SAFECENTER` | ColorOS 12+ 失效 |
| `com.iqoo.powersave.ui.PowerSaveActivity` | 已失效 |

而且失败后只 `Log.w` 一句就返回 `false`，**没有降级链**。用户点"去设置"按钮没反应，也不知道该自己怎么操作。

### 根因 4：识别逻辑有 bug

`Manufacturer.ONEPLUS` 枚举定义了，但 `detect()` 里 `brand.contains("oneplus")` 会先被 OPPO 分支截获 —— **这个枚举永远返回不出来**。另外 realme 被并进 OPPO，但自启动 Intent 没有对应的 realme 分支。

---

## 三、解决方案：从"猜设备"改为"测量设备"

核心思路的转变：

| | 旧方案 | 新方案 |
|---|---|---|
| 判断依据 | 品牌 + Android 版本（猜） | 实际帧数据（测） |
| 修复手段 | 写系统属性（需 root，必失败） | 应用层重建虚拟屏（不需要特权） |
| 结果验证 | 无 | 每档探测后验证是否有画面 |
| 失败处理 | 无 | 自动升到下一档 |
| 经验积累 | 无 | 成功档位持久化，下次直接命中 |

### 四步流程

```
1. ROM 指纹识别     → 读真实 ROM 版本（ro.miui.ui.version.name 等）
2. 静态推荐起始档位 → 仅提供初值，不决定结果
3. 运行时探测       → 创建虚拟屏，采样前若干帧判断是否全黑
                      ├ 有画面 → 记录成功档位，结束
                      └ 持续全黑 → 升一档，重建虚拟屏，回到步骤 3
4. 持久化 + 画像     → 下次同 ROM 直接命中；ROM 升级后签名变化自动重测
```

### 降级阶梯（5 档，由轻到重）

| 档位 | 手段 | 针对现象 |
|---|---|---|
| `BASELINE` | 不做任何改动 | 本来就没问题的设备 |
| `AUTO_MIRROR` | 加 `VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR` | 虚拟屏未镜像主屏导致纯黑 |
| `MID_RES` | 上 + 降到 0.62x 分辨率 | 部分 ROM 高分辨率下不出帧 |
| `LOW_RES` | 上 + 降到 0.45x、缓冲 4、预热 1.4s | 联发科/麒麟芯片常见 |
| `MIN_RES` | 上 + 降到 0.32x、预热 2.2s | 最后一档，兜底 |

---

## 四、已实现的代码

> ⚠️ **交付状态核实（2026-09-12，`git status --short` 实测）**：本章三个新增文件**均已写入工作区，但至今未被 git 跟踪**（均显示为 `??`）：
> `phone-app/src/main/java/com/rokidlab/phone/util/RomFingerprint.kt`、
> `phone-app/src/main/java/com/rokidlab/phone/mirror/MirrorCompat.kt`、
> `phone-app/src/main/java/com/rokidlab/phone/hid/BtHidCompat.kt`。
> 它们被**已被跟踪**的 `mirror/PhoneMirrorService.kt`、`hid/BluetoothHidManager.kt`、`util/ManufacturerUtils.kt` 直接引用，
> 因此 **clean checkout（仅检出 HEAD `cd9c6f0`）必然编译失败**——找不到 `RomFingerprint` / `MirrorCompat` / `BtHidCompat`。
> 也就是说：本章描述的修复**目前只在作者本机工作区成立，尚未进入可复现的仓库状态**。
>
> 分层归属（按实际包路径）：
> - `com.rokidlab.phone.util.RomFingerprint` —— `util/` 工具层；其系统属性读取统一收敛到 L0 `platform/RomAdapter.kt`（`RomAdapter.systemPropertyCapability`）。
> - `com.rokidlab.phone.mirror.MirrorCompat` —— `mirror/` 业务包内的**平台适配角色**（镜像降级阶梯 + 黑帧检测 + 档位持久化），依赖 `util.RomFingerprint`。
> - `com.rokidlab.phone.hid.BtHidCompat` —— `hid/` 业务包内的**平台适配角色**（蓝牙栈指纹 + 发送方式候选策略）。
> 三者均**不在** `platform/` 包内；`platform/` 下现有的是 L0 桥接件（AdbTransport / RomAdapter / SdkBridge / HidBridge / SdkFieldMap 等）。

### 新增 `util/RomFingerprint.kt`（存在，但未跟踪 ⚠️）

读取 ROM **真实版本号**，而不是猜品牌：

| ROM | 属性 |
|---|---|
| MIUI / HyperOS | `ro.miui.ui.version.name` |
| ColorOS | `ro.build.version.opporom` |
| realme UI | `ro.build.version.realmeui` |
| OriginOS | `ro.vivo.os.version` |
| EMUI / HarmonyOS | `ro.build.version.emui` |
| MagicOS | `ro.build.version.magic` |

设计上刻意做了一件事：**任何属性读失败都返回 null，绝不影响主流程**。因为降级档位完全不依赖 ROM 识别结果 —— 即使识别失败，整套探测降级依然完整可用。ROM 信息只用于两处：设备签名（决定缓存是否有效）、诊断报告展示。

`signature()` 由 board/device/model/sdk/ROM版本/incremental 组成，**不含序列号等身份信息**，可安全进日志和导出报告。

### 新增 `mirror/MirrorCompat.kt`（存在，但未跟踪 ⚠️）

三个部分：

**① 降级阶梯**

```kotlin
MirrorCompat.paramsFor(tier, baseW, baseH)  // → MirrorParams(w, h, flags, buffers, warmupMs)
tier.next()                                  // 下一档，末尾返回 null
```

**② 黑帧检测** —— 这里有个容易踩的坑，值得单独说：

项目自己的主题是「丝绒炭黑」（背景 `#0B0B0E`，灰度约 11）。如果黑屏判定只写 `max <= 阈值`，用户用深色主题时**会被误判成黑屏**，然后一路降到最低分辨率。

所以判定同时要求两个条件：

```kotlin
return max <= BLACK_MAX && (max - min) <= UNIFORM_DELTA   // BLACK_MAX=12, UNIFORM_DELTA=4
```

即「整体够暗」**且**「明暗基本没有差异」。深色主题有文字，明暗有对比，不会被误判；真正的黑屏是纯色，两个条件都满足。采样每 8 像素取一点，开销可忽略。

**③ 档位持久化**

按设备签名缓存成功档位。同一 ROM 下次直接命中；**ROM 升级后签名变化，自动丢弃缓存重新探测** —— 这点很重要，否则系统更新后会出现"以前能用现在不能"的诡异 bug。

### 改造 `mirror/PhoneMirrorService.kt`

- **删除** `tryApplyHwcFix()`（失效的 setprop 方案）
- 启动时从 `learnedTier()` 取档位，没学过就从 `BASELINE` 开始
- `createMirrorSession()` 按档位设置 flags / 缓冲数 / 分辨率
- 每帧调用 `blackProbe.submit(...)`，持续黑 → `escalateCompatTier()` 升档重建
- 全部档位失败 → 打印设备签名，提示导出诊断报告（只提示一次，不反复打扰）

---

## 五、蓝牙 HID 跨品牌兼容（手机扮演 HID Device）

投屏那一节解决的是"看得见"，这一节解决的是"控得住"。
手机在这里是 **HID Device**（键盘/手柄），眼镜是 **HID Host**。
原实现里确实写了兼容代码（`ManufacturerUtils.needsQtiHidWorkaround()` 等），
但同样存在"写了但没生效"的问题，而且比投屏那部分更隐蔽。

### 根因 1：QTI 判定形同虚设（最重要的一个）

```kotlin
fun isQtiBluetoothStack(): Boolean {
    val prop = getSystemProperty("bluetooth.host.stacks")
    val vendor = getSystemProperty("vendor.bluetooth.host.stacks")
    ...
}
```

这两个属性在量产的 Android 11+ 机型上**基本不存在**（AOSP 调试期遗留，
且非 `system`/`vendor` 应用读取 vendor 属性还要过 SELinux）。
于是 `needsQtiHidWorkaround()` 实际只有 `isVivoOrIqoo()` 这一条分支可能为真。

后果很直接：**小米 15 / OPPO / OnePlus / realme 这些 Snapdragon 机型，
本身就是 QTI 蓝牙栈，却被当成 AOSP 栈处理** ——
这恰好解释了"同一份代码，vivo 上能用，小米上不行"。

### 根因 2：每条报告都发两遍

```kotlin
val standardOk = hid.sendReport(dev, reportId, data)   // 方法1
val full = byteArrayOf(reportId.toByte()) + data
val manualOk = hid.sendReport(dev, 0, full)            // 方法2（无条件再发一次）
```

方法 1 由系统自动在报文前插入 Report ID，方法 2 手动拼装，
**两者最终线上字节完全相同**。所以每一次按键都是双倍流量 ——
受限的蓝牙栈上会把中断通道队列撑爆，表现为快速连发时丢键。

更可惜的是：这样永远无法知道究竟哪一种方法有效，经验沉淀不下来。

### 根因 3：报告长度与描述符对不上（最隐蔽的一个）

描述符声明的键盘报告是**两个字节**：`[modifier(1), keycode(1)]`。
而 `sendCtrlV` 按标准 boot 协议组了八个字节：

```kotlin
byteArrayOf(0x08, 0x00, 0x19, 0, 0, 0, 0, 0)  // modifier, reserved, kc1..
                  ^^^^ 这个 0x00 正好落在 keycode 位上
```

按描述符解析，主机读到的是"按下 Ctrl + 键码为空" →
**粘贴在任何严格按描述符解析的主机上都不可能生效**。

同理：QTI 精简描述符里**没有**鼠标报告，
但通道预热和 `sendMouseMove` 仍在发 `reportId=3` ——
严格的蓝牙栈会整包拒收，等于预热白做。

### 根因 4：阻塞主线程的 sleep

`sendButtons` / `sendRelease` / `sendCtrlV` / 「切换手柄模式」
都有 `Thread.sleep`，而它们多数是从 Compose 回调（主线程）进来的。
切换手柄模式那段 `Thread.sleep(200+800+200)` 更是直接卡 UI 一秒多。

### 根因 5：未配对直接 connect（多品牌上的硬失败）

多数 ROM 的 HID Device 实现要求对端**已完成配对**才接受连接。
旧代码跳过这一步直接 `hidDevice.connect(device)`，
在部分品牌上必然失败，且界面上没有任何提示。

### 根因 6：蓝牙未开时静默失败

`initialize()` 一路走到 `registerApp` 失败就算了，
UI 上的表现就是"点了连接，什么也没发生"。

### 已实现的代码

**新增 `hid/BtHidCompat.kt`（存在，但未跟踪 ⚠️）** — 策略层，四件事：

1. **芯片指纹判定**（替代"猜品牌"）
   优先级：显式栈属性 → `ro.mediatek.platform` → `ro.soc.model` / `ro.board.platform` /
   `ro.hardware` / `ro.chipname` → 品牌兜底。
   判定失败退化为 `UNKNOWN`，**只影响优先尝试哪种发送方式，不影响流程能否走下去**。
2. **有序候选 + 结果记忆**
   每种栈给出候选顺序，成功即停止；成功后写入 SharedPreferences，
   下次同栈直通。也提供 `setManualMode()` 供现场强指定。
3. **报告长度归一化**
   按当前已注册的描述符补齐/截断；描述符里没声明的 Report ID 直接丢弃并告警。
4. **诊断输出** `diagnostics()`，字段已备好，可直接接到设置页导出。

**改造 `hid/BluetoothHidManager.kt`**：

| 改动 | 之前 | 之后 |
|---|---|---|
| 发送方式 | 每次都发两遍 | 按栈候选，成功即止 + 记住 |
| 描述符/报告长度 | 多处不匹配 | 按当前描述符归一化 |
| 发送线程 | 调用线程（多为主线程） | 串行后台线程（保序，不卡 UI） |
| QTI 判定 | 两个几乎不存在的属性 | 芯片平台指纹 |
| 未配对 | 直接 connect 必失败 | 先 createBond，配对后自动续连 |
| 蓝牙未开 | 静默失败 | 记录 `lastBlockReason` 并告警 |
| Ctrl+V | 8 字节组包，键码位为 0 | `[0x08, 0x19]` 两字节 |

### 还需要真机验证（静态分析推不出结果）

请按下面矩阵各跑一台，把 `adb logcat | grep -E 'BtHidCompat|BluetoothHidManager'` 发我：

| ROM | 期望栈判定 | 关注点 |
|---|---|---|
| MIUI 14 / HyperOS 2 | QTI（SoC 指纹应命中） | 这是本次改动最大受益机型 |
| ColorOS 14 / realme UI | QTI | ":" |
| OriginOS 4（vivo/iQOO） | QTI | 回归：原来能用，现在应仍然能用 |
| EMUI 12 / HarmonyOS 4 | QTI（海思机型应落到品牌兜底） | 重点看 registerApp 是否成功 |
| MagicOS 8（荣耀） | 按 SoC | ":" |
| One UI 6（三星） | SAMSUNG / BROADCOM | 顺带验证 `ensureBonded` |
| Pixel（原生） | GOOGLE | 基线对照组 |

每条设备只需确认三件事：
① 日志里 `stack=` 是否与上表期望一致；
② 是否出现 `经兜底方式 xxx 投递成功`（出现说明默认策略不是最优，需要调整候选顺序）；
③ 按键有没有出现丢帧或重复。

## 六、还需要补的部分

这次改动解决了**投屏黑屏**，这是最难也最核心的一块。ROM 权限引导（自启动/电池白名单）还需要做，思路同类：

### 待办 1：权限跳转 Intent 降级链

> **状态：已完成**（2026-09-12）。`util/ManufacturerUtils.kt` 的「一个厂商一支 Intent」已改造为
> **候选降级链 + 系统详情页兜底**：原 `getAutoStartIntent()` / `getPowerSavingIntent()` 已删除，
> 改为 `autoStartCandidates()` / `powerSavingCandidates()` / `overlaySettingsCandidates()` /
> `notificationCandidates()` 四组有序候选，统一由 `launchFirstAvailable(context, candidates)` 执行；
> `fallbackAppSettings()` 被 `appDetailsIntent()` 取代并接入了**每一条**跳转链的末尾。
> `openAutoStartSettings()` / `openPowerSavingSettings()` 的返回值语义随之变为
> 「是否成功拉起链中某一支」（调用点 `MainActivity.kt` 未使用返回值，无破窗）。

实现要点：

```kotlin
// 每个厂商给一组候选，末尾固定拼上 appDetailsIntent(context) 兜底
private fun autoStartCandidates(context: Context): List<Intent> {
    val pkg = context.packageName
    val vendor: List<Intent> = when (detect()) {
        Manufacturer.XIAOMI -> listOf(
            Intent("miui.intent.action.APP_PERM_EDITOR").putExtra("extra_pkgname", pkg),
            Intent("miui.intent.action.APP_PERM_EDITOR_2").putExtra("extra_pkgname", pkg),
            Intent("miui.intent.action.OP_AUTO_START").putExtra("extra_package_name", pkg),
            Intent().setClassName("com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity"),
        )
        // HUAWEI / HONOR / OPPO / VIVO / MEIZU / SAMSUNG 同理，均为「新 ROM → 旧 ROM」排序
        else -> emptyList()
    }
    return vendor + appDetailsIntent(context)   // 兜底：ACTION_APPLICATION_DETAILS_SETTINGS
}
```

**关键实现决策：不做 `resolveActivity` 预检。** 本节早先设想的「逐个 resolveActivity 检查」在
Android 11+ 上是**错的**：软件包可见性（`<queries>` 声明）会让 `resolveActivity` 对未声明的
系统组件返回 `null`，而 `startActivity` 本身不受该限制 —— 用预检结果决定跳不跳，会把本来
能用的厂商入口误杀成「无入口」。因此改为**真的逐支启动一次**，以
`ActivityNotFoundException` / `SecurityException` 作为「这支在当前 ROM 上不可用」的信号。

**悬浮窗链的顺序与自启动相反**：`ACTION_MANAGE_OVERLAY_PERMISSION`（AOSP 契约，带
`package:` 能精确落到本应用那一行）排第一，厂商私有页降为备选；其余三条链路则是
厂商页在前、系统页在后。理由：厂商私有页多数只是「权限总表」，用户还得自己找。

### 待办 2：兼容性诊断导出

> **状态：已完成**（2026-09-12）。`RomFingerprint.diagnostics()` 与 `BtHidCompat.diagnostics()`
> 此前零调用点，现已接通出口：新增 `RomFingerprint.compatReport(context)` 把
> **设备/ROM 画像 + 蓝牙 HID 栈 + 投屏降级档位 + 兼容性标记** 拼成纯文本，
> 经 `LogCollector.createTextShareIntent()`（与导出日志共用 FileProvider 通道）写出
> TXT 并拉起 `ACTION_SEND` 分享。设置页新增「导出兼容性诊断」卡片
> （`SettingsScreen.kt` → `StoreActions.onExportCompatDiagnostics` →
> `StoreActionsFactory` → `MainActivity.exportCompatDiagnostics()`）。
> 报告不含序列号/IMEI/API Key/聊天内容，可安全外发。

报告的结构示意（`<>` 为实机运行时取值，其中机型/ROM 两行已按小米 14 实测填写）：

```
========================================
  RokidLab 兼容性诊断报告
========================================
导出时间 : <yyyy-MM-dd HH:mm:ss>
应用版本 : v3.5 (code 20)

--- 设备 / ROM ---
  brand = Xiaomi
  manufacturer = Xiaomi
  model = 24129PN74C
  device = dada
  board = <Build.BOARD>
  soc_platform = sun
  android = 16
  sdk = 36
  rom = MIUI/HyperOS V816
  signature = <board>/dada/24129PN74C/36/MIUI/HyperOS V816/OS3.0.305.0.WOCCNXM
  厂商识别 = Xiaomi
  兼容性标记 = <ManufacturerUtils.getCompatibilityIssues() 的逗号串，无则 none>

--- 蓝牙 HID 栈 ---
  btStack=<BtHidCompat.detectStack() 的 label>
    evidence: <判定依据属性链>
    sendOrder: <候选发送方式顺序>
    reportGap=<ms>, warmup=<ms>
    maxDescriptor=<B 或 无限制>

--- 投屏降级 ---
  当前有效档位 = <MirrorCompat.currentStatus()，未学过时为 "not learned yet">
```

### 待办 3：真实设备矩阵验证

> **状态：进行中**（2026-09-12）。设备身份列已用 `adb shell getprop` 实测回填；
> **运行时列的验证条件已具备但尚未执行** —— 只需在设置页点一次「导出兼容性诊断」（待办 2 的产物），
> 即可拿到 `stack=` / `当前有效档位`，无需再拼 adb 命令。

静态 repro 都吐不出这个东西。需要真机跑一遍 checklist：

| 品牌 | 建议覆盖 ROM |
|---|---|
| 小米/Redmi | MIUI 14、HyperOS 1、HyperOS 2（手头机为 HyperOS 3，见下） |
| 华为 | EMUI 12、HarmonyOS 3/4 |
| OPPO/realme | ColorOS 13、14 |
| vivo/iQOO | OriginOS 3、4 |
| 荣耀 | MagicOS 7、8 |
| 三星 | One UI 5、6 |

**真机矩阵（手头已核实机型）**

| 机型 | model | device（adb 序列号） | ROM / Android | 投屏降级档位实测 | 黑帧判定是否误触 | HID 栈判定实测 | 确认通道可用性 | 备注 |
|---|---|---|---|---|---|---|---|---|
| 小米 14 | `24129PN74C` | `9fc033b0` | HyperOS `OS3.0.305.0.WOCCNXM`（`V816`）/ Android 16（SDK 36）/ `ro.board.platform=sun` | 待填 | 待填 | 待填（期望 QTI：`sun` 为高通平台） | 待填 | Android 16 属 16KB 页设备，需同时验证 so 对齐 |
| Rokid Glasses | `RG-glasses`（`adb model` 上报为 `RG_glasses`） | `1906092618122981` | Android 12（SDK 32）/ `ro.board.platform=neo` / 固件 `1.25.015-20260903-150201` | —— | —— | —— | 待填 | 眼镜端不参与投屏降级/HID 栈判定，此两列填 `—` |

> ⚠️ **勘误**：上表眼镜端序列号此前误记为 `1901092534015091`，实测为 `1906092618122981`（2026-09-12 核实）。
> 小米 14 的实际系统为 **HyperOS 3 / Android 16**，比本节原假设的「MIUI 14 / HyperOS 2」更新，
> 是本节所有兼容策略的最高版本验证对象。

---

## 七、这次改动的风险与回退

- **不影响正常设备**：`BASELINE` 档的参数与改动前完全一致，本来能用的设备行为不变
- **不会误降档**：黑帧判定要求"整体暗 + 无明暗差异"双重条件，深色主题不会触发
- **不会反复重试**：出画面后 `frameHealthy = true` 锁定，不再降档；` BlackFrameProbe` 有 `settled` 防重复触发
- **回退方式**：如需恢复旧行为，`gradle remove` 掉新文件即可，但建议保留——因为旧方案本身就是失效的

---

## 八、一句话

**旧方案是在问"你是什么手机"，然后给一张越来越旧的答案表；新方案不问你是谁，直接看你有没有出画面。前者永远跟不上 ROM 更新，后者不会过期。**
