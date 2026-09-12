# RokidLab 代码实现审查报告

> 审查日期：2026-09-10
> 背景：眼镜型号固定，**变量在手机侧**（不同品牌 ROM）。
> 范围：`phone-app` + `RokidLink` 共 4.8 万行 Kotlin，全量静态扫描 + 关键路径逐行核对。
> 本篇只写**能给出行号的真问题**。上次已说的（setprop 失效、`isQti` 判定错、token 泄露、双发报告、`Ctrl+V` 组包错误）不再重复。

> **复核说明（2026-09-12，HEAD=cd9c6f0 / v3.5）：** 本文 A1-A4 / B1-B5 / C1-C8 的原始行号基于旧 `MainActivity(2003 行)` 与旧 `CxrLHiRokidSession(3550 行)`，phase3/5 拆分后已整体漂移。本次逐条取证复核，在每条标题下标注三态：**【已修】/【仍在】/【随重构消失】**，并给出当前真实文件路径与行号。**下文正文中的旧行号仅作历史留证，勿再据此定位。**

---

## 一、结论先行

先看一个反常识的事实：**这个项目最严重的兼容性问题，不是没适配某个 ROM，而是几条"本来在所有手机品牌上都不成立"的代码。**

也就是说，有些问题在小米上表现出来叫"小米不兼容"，但根因对华为、OPPO、vivo 一样成立。搞清楚这点很重要——如果按"逐个品牌打补丁"的思路去修，会一直修不完。

本次共确认 **4 个致命缺陷、5 个逻辑错误、8 处明显笨拙实现**。其中最严重的一条（A1）会让 App 在用户误触一次权限弹窗后**永久卡死且无法自恢复**，而这恰好非常容易被误判为"某品牌不兼容"。

---

## 二、致命缺陷（必须马上修）

### A1. `runWithPrerequisites` 权限拒答后会永久死锁 —— 最严重

> **复核（2026-09-12）：【已修】。** 现行位置 `phone-app/src/main/java/com/rokidlab/phone/app/MainActivity.kt:740-763`（`runWithPrerequisites` / `proceedIfPrerequisitesReady` / `consumePendingAction`）。两处 launcher 否定分支已显式清空：权限回调 146 行、蓝牙回调 166 行 `pendingAction = null`。旧行号 1641-1649 / 146-152 / 163-166 中，后两者随 phase5 拆分漂移，64xx 段已不存在。

**位置**：`phone-app/src/main/java/com/rokidlab/phone/app/MainActivity.kt:1641-1649`、`146-152`、`163-166`

```kotlin
private fun runWithPrerequisites(action: () -> Unit) {
    if (pendingAction != null) return  // <- 卡死判定
    pendingAction = action
    when {
        !permissions.all(::hasPermission) -> permissionLauncher.launch(permissions)
        !isBluetoothEnabled() -> enableBluetoothLauncher.launch(...)
        else -> consumePendingAction()
    }
}
```

两个 launcher 的**否定分支都没有清空 `pendingAction`**：

```kotlin
// permissionLauncher 回调 (146-152)
if (permissions.all(::hasPermission)) consumePendingAction()
else { log(...); Toast(...) }          // <- 失败时 pendingAction 保持非 null

// enableBluetoothLauncher 回调 (163-166)
if (isBluetoothEnabled()) consumePendingAction() else log(...)   // <- 同样不清空
```

**为什么这是死锁而不是简单的状态残留**：唯一能复位它的 `consumePendingAction()` 只能由这两个 launcher 回调触发，而这俩 launcher 只能由 `runWithPrerequisites` 启动——但它的第一行就 `return` 了。**一旦触发，进程内再无恢复路径，必须杀进程重启。**

**影响面**：`runWithPrerequisites` 共 **10 个调用点**，涵盖授权、安装/卸载、启动/停止 RokidLink 等**全部核心操作**（行 372 / 375 / 383 / 782 / 1062 / 1303 / 1485 / 1506 / 1743）。

**与多品牌的关系**：Android 12+ 需要 `BLUETOOTH_CONNECT` 运行时权限。MIUI / ColorOS / OriginOS / MagicOS 的权限弹窗样式各异，用户误点"拒绝"的概率不低；且多数 ROM 在用户选择"拒绝且不再询问"后**不再二次弹窗**。用户之后的表现就是"所有按钮点了都没反应"——**这极其容易被归因成"这个牌子不兼容"**。

**修法**：两个否定分支各加一行 `pendingAction = null`。更进一步，把 `pendingAction` 换成带序号的请求令牌，避免排队覆盖。

---

### A2. `screencap` 兜底投屏路径完全失效 —— 这是"多品牌投屏黑屏"的一条主因

> **复核（2026-09-12）：【已修】。** 现行位置 `phone-app/src/main/java/com/rokidlab/phone/adb/AdbScreenMirrorClient.kt:608-705`（`startStreaming`）。命令改为 `shell:while true; do screencap -p; done`（620 行），按 PNG 签名 `89 50 4E 47 0D 0A 1A 0A` 与 `IEND(AE 42 60 82)` 切帧解码（658 行、672-695 行），已去硬编码 480×640。旧行号 594 / 649-688 已失效（另见文末 A2 根因更正）。

**位置**：`phone-app/src/main/java/com/rokidlab/phone/adb/AdbScreenMirrorClient.kt:594`、`649-688`

降级链路是：`ScreenMirrorActivity.kt:257` 在 `useSurface == null`（H.264 解码器不可用/Surface 未就绪）时走 `startStreaming()` → 发 `shell:while true; do screencap; done`。

这段代码假设 `screencap` 输出的是「12 字节头 + w×h×4 原始 RGBA」并按此解析：

```kotlin
val dest = "shell:while true; do screencap; done\u0000"      // :594
...
if (w == 480 && h == 640 && fmt == 1) {                       // :649
```

**两个独立的致命错误，各自都足以让它归零**：

1. **格式错了**：Android 的 `screencap` 输出的是 **PNG**，不是裸 RGBA。PNG 魔数是 `89 50 4E 47 0D 0A 1A 0A`，按 little-endian 当宽度读出来是个巨大值。
2. **尺寸错了**：硬编码 `w == 480 && h == 640`。现代手机是 1080×2400、1080×2340…，**没有一台满足这个条件**。就算格式对了也匹配不上。

**结果**：`frameFound` 恒为 false → 缓冲区涨到 3MB 后丢弃 → 循环永远等不到任何一帧 → **黑屏**。

**为什么这条特别值得重视**：

- 作者**已经预见到了**这条路径会静默黑屏，还在 UI 侧专门做了防护（`:60-61` 注释写明"H.264 解码器不可用、降级 screencap 模式时的帧回调，UI 侧把它绘制到 TextureView，避免降级路径静默黑屏"）。但 UI 层再怎么画也没有用——**数据源从来就没有产出过一帧**。
- 这正好解释了为什么在部分品牌上投屏始终黑屏：scrcpy 主路径依赖 `app_process` 注入 + H.264 硬编/解，在华为/荣耀/HarmonyOS 以及部分 MIUI 上容易不可用，此时回退到这条**必然黑屏**的路径。
- 更糟的是 `while true; do screencap; done` 是个死循环，在手机端持续执行 `screencap`（要编码 PNG），**CPU 开销很大，却产出零个可用帧**。即使被迫降级，也不该用这种方式。

**修法**：放弃解析 `screencap` 的裸输出。可行方案有两个：
- 保持 `screencap`，但把输出当 PNG 用 `BitmapFactory.decodeStream` 解（需要先缓冲出完整一张）；
- 更好：改用 `screenrecord --output-format=h264 -` 走软件 AVC 解码，或直接用 `ImageReader` 的 MediaProjection 路径（你们已经有了）。

另外，无论如何要**用实际宽高做参数**，不要写死 480×640。

---

### A3. 工具调用无条件重试，会导致重复拨号/重复发短信

> **复核（2026-09-12）：【已修】+【随重构消失】。** 原 `CxrLHiRokidSession.runTool` 已随 phase3 迁移到 `phone-app/src/main/java/com/rokidlab/phone/domain/AiConversationService.kt:374-396`：`ToolRegistry.SIDE_EFFECT_TOOLS`（`ai/ToolRegistry.kt:92-97`，共 14 个副作用工具）命中则 `maxAttempts = 1`（378 行）不重试，仅只读/幂等工具重试一次。
> 另：原文所述执行分派的「巨型 `when`」已在 **Phase 4** 结构性拆分为 10 个域 `ToolProvider`——`ToolRegistry.execute` 现仅做参数归一化 + 按 `toolNames` 路由（`ai/ToolRegistry.kt:449-484`，`providers` 列表 450-461 行）。故旧 `ToolRegistry.kt` 内巨型 `when` 分支 **【随重构消失】**；`AiConversationService.runTool` 仅保留 3 个特例分支（`manage_memory` / `SkillRegistry` ×2）后委派 `ToolRegistry.execute`。旧行号 1692-1722 已失效。

**位置**：`phone-app/src/main/java/com/rokidlab/phone/glasses/CxrLHiRokidSession.kt:1692-1722`

```kotlin
fun runTool(tc: ToolCallInfo): String {
    var lastError: Exception? = null
    repeat(2) { attempt ->                      // <- 无条件重试一次
        if (attempt > 0) { Thread.sleep(500); Log.w(...) }
        try {
            return when (tc.name) { ... else -> ToolRegistry.execute(...) }
        } catch (e: Exception) { lastError = e }
    }
}
```

注释写得很清楚，重试是为了应对"网络抖动/**ADB 隧道瞬断**等瞬时错误"。**问题恰恰出在这里**：隧道瞬断最典型的形态就是「命令已送达、回执丢失」——远端已经执行了，只是结果没回来。此时重试 = **必然重复副作用**。

`ToolRegistry.kt:1233` 的 `call_phone` 就是走隧道 `am start` 拨号的。同理还有发短信类工具。

**这是设计缺陷而非边缘情况**：你重试的场景，恰恰是副作用最可能已经发生的场景。风险还在放大——工具入口允许域名是 `DOMAIN_ALL`，LLM 可以自主触发。

**修法**：给工具加"是否可重放"标记。只读类（检索、查询、读文件）可重试；副作用类一律不重试，把失败如实回报给模型，由 LLM 决定是否重来（它有上下文，知道自己在做什么）。

---

### A4. `pullFile` 把失败当成功，产出 0 字节文件

> **复核（2026-09-12）：【已修】。** 现行位置 `phone-app/src/main/java/com/rokidlab/phone/adb/AdbShellClient.kt:445-538`（`pullFile`）。`FAIL` 分支在 500-506 行读 `errLen` + `errMsg`，删空文件并 `return false`（525）/异常路径 538 行。旧行号 468-481 已失效。

**位置**：`phone-app/src/main/java/com/rokidlab/phone/adb/AdbShellClient.kt:468-481`

```kotlin
when (syncId) {
    "DATA" -> { ... }
    "DONE" -> { writeMessage(CMD_OKAY, ...); break }
    else -> break          // <- FAIL 落到这里，直接 break
}
...
Log.i(TAG, "pullFile success: $remotePath -> $localPath (${localFile.length()} bytes)")
return true                // <- 失败也 return true
```

远端文件不存在时 adbd 回 `FAIL`，代码既不消费也不区分，直接跳出循环返回 `true`，而 `FileOutputStream` 已经创建了一个 **0 字节文件**。上层 `extractApkToDownloads` 于是向用户报告"下载成功"。

**讽刺的是同一个项目里已经有一份正确的实现**：`AdbFileManagerClient.kt:424` 的 `downloadFile` 正确处理了 `FAIL`（读出 errLen + errMsg 并抛异常）。同一个功能两份手写实现，一份对一份错——这本身就是个问题（见 C8）。

---

## 三、逻辑错误

### B1. 眼镜端 3 秒后无条件重建桥接，重复订阅导致消息倍发

> **复核（2026-09-12）：【已修】。** 现行位置 `RokidLink/src/main/java/com/rokidlab/rokidlink/KeyButtonService.kt:886-911`：新增 `pendingReconnect` 去重（887 行声明、898-899 行 `removeCallbacks` 后重建）、延迟任务首行 `if (bridgeConnected) return` 守卫（901 行）。旧行号 823-828 已失效。

**位置**：`RokidLink/src/main/java/com/rokidlab/rokidlink/KeyButtonService.kt:823-828`

```kotlin
override fun onDisconnected() {
    ...
    mainHandler.postDelayed({
        bridge = null
        initCxrBridge()          // <- 不检查期间是否已自然重连
    }, 3000)
}
```

闪断场景下：0s 断开 → 1s 自动重连成功（`onConnected` 已把一切恢复就绪）→ 3s 定时任务到 → **丢弃刚连好的 bridge，重建一个新的**。

旧 bridge 从未 `disconnectCXRDevice()` / `unsubscribe`，订阅在 cxr-service 侧残留 → 反复断连后订阅数累积 → **同一条 config/tts/ai 被下发多份**。

另外 `postDelayed` 没有去重，频繁断连会堆叠多个重建任务。

修法：延迟任务里加 `if (bridgeConnected) return@postDelayed` 守卫；重建前先清理旧实例。

---

### B2. `MusicPlayerController` 并发竞态 + MediaPlayer 泄漏

> **复核（2026-09-12）：【已修】。** 现行位置 `phone-app/src/main/java/com/rokidlab/phone/ai/MusicPlayerController.kt`：新增 `opLock`（98 行）串行化，`play`（107 行）与 `stop`（194 行）均在 `synchronized(opLock)` 内。旧行号 81 / 142 已失效。

**位置**：`phone-app/src/main/java/com/rokidlab/phone/ai/MusicPlayerController.kt:81`、`142`

`play()` 首行调用 `stop()`，随后 `player = mp`；但 `player` 只有 `@Volatile`，`play/stop` 均非同步方法。而工具是并发执行的（`CxrLHiRokidSession.kt:1811` 明确说"非 ADB 工具真并发"），于是 `play_song` 与 `stop_music` 可以并行：线程 A 建好 mpA 写入 `player`，线程 B 读走旧值 release 后置 null，A 的 `onPrepared` 仍会对新 mpA `start()`……

后果：双音轨叠加、MediaPlayer 泄漏（再也无法 release）、播放状态错乱。

修法：用单个 `MediaPlayer` 实例 + 一把锁串行化所有操作，或干脆限定在单线程上下文里执行。

---

### B3. 空 `arguments` / 缺 `tool_call_id`——国产模型常见兼容坑

> **复核（2026-09-12）：【已修】（部分位置随重构消失）。** 空 `arguments` 兜底在 `phone-app/src/main/java/com/rokidlab/phone/ai/ToolRegistry.kt:471-480`（`execute` 内：空串按 `{}`，非法 JSON 兜底空对象并告警）。`tool_call_id` 兜底在 `ai/OpenAiService.kt:444-447`（`resolveToolId`，缺失时生成 `call_<uuid>`），回填调用点 562 行。旧 `ToolRegistry.kt:812` 已漂移；旧 `SseStreamAccumulator.kt` 文件已不存在，**【随重构消失】**。

**位置**：`ToolRegistry.kt:812`、`OpenAiService.kt:398`、`SseStreamAccumulator.kt:539/547`

```kotlin
val args = JSONObject(arguments)     // arguments == "" 时抛异常
```

Ollama 及部分国产模型对**无参工具**常返回 `arguments: ""`，`JSONObject("")` 直接抛异常 → 整个工具调用轮次失败。

回填处的 `tool_call_id` 也是 `optString("id")` 缺省 `""`：无 id 时部分服务端返回 400，或导致多次调用结果归因错乱。

修法：空串按 `{}` 处理；id 缺失时自行生成 `call_xxx` 兜底。

---

### B4. 记忆删除的 LIKE 通配符未转义

> **复核（2026-09-12）：【已修】。** 现行位置 `phone-app/src/main/java/com/rokidlab/phone/ai/LongTermMemoryManager.kt:202-207`：改为 `content LIKE ? ESCAPE '!'`（204 行）并对查询串转义（`escaped`）。旧行号 203 → 现行 204。

**位置**：`phone-app/src/main/java/com/rokidlab/phone/ai/LongTermMemoryManager.kt:203`

```kotlin
d.delete("memories", "content LIKE ?", arrayOf("%$q%"))
```

`%` 和 `_` 在 SQLite LIKE 里是通配符。删除内容为「进度 50%」的记忆时按 `50%` 匹配不到；片段含通配符则会**误删**。

修法：`ESCAPE '!'` 并转义（推荐），或改用 `instr()`/`replace()` 子串匹配。

---

### B5. 表达式结果未 return，错误提示错误

> **复核（2026-09-12）：【已修】。** 现行位置 `phone-app/src/main/java/com/rokidlab/phone/ai/AiuiProject.kt:485-486`：改为 `return@runBlocking if (notFound == candidates.size) "眼镜上未找到该应用的 .aix 文件" else lastErr ?: "删除失败"`。旧行号 484 → 现行 485-486。

**位置**：`phone-app/src/main/java/com/rokidlab/phone/ai/AiuiProject.kt:484`

```kotlin
if (notFound == candidates.size) "眼镜上未找到该应用的 .aix 文件"
else lastErr ?: "删除失败"
```

这是条**表达式语句**，返回值被丢弃，函数末尾实际返回 `lastErr ?: ...`。两候选都返回 NOT_FOUND 时，用户看到的是原始 `"NOT_FOUND"` 而不是友好文案。

---

## 四、做笨了 / 实现绕远路

全项目 **63 处 `Thread.sleep`**（phone 50 + link 13，2026-09-12 实测），分开看：

### C1. 用 sleep 编排协议时序 —— 每次对话固定付 2.6 秒

> **复核（2026-09-12）：【仍在】（随 phase3 迁移）。** 6 处固定等待现位于 `phone-app/src/main/java/com/rokidlab/phone/domain/AiConversationService.kt`：285(300ms)、725(600ms)、735(400ms)、805(300ms)、820(500ms)、830(500ms)，合计 2600ms。原 `CxrLHiRokidSession.kt:2021/2031/2101/2116/2126/1605` 行号全部失效（Session 已降至 887 行）。全仓 `Thread.sleep` 实测 **63 处**（phone 50 + link 13），非旧文所述 57 处。

**位置**：`CxrLHiRokidSession.kt:2021(600ms)`、`2031(400ms)`、`2101(300ms)`、`2116(500ms)`、`2126(500ms)`、`1605(300ms)`

AI 下行链路里插入了一串固定等待：300+600+400+300+500+500 ≈ **2.6 秒**，与网络状况无关，**每次对话都要付**。

注释里说这些间隔是"降低链路抖动时两条指令一起丢失的概率"。这个方向本身没错，但 sleeve road 用猜时间来对齐是不可靠的：快了没用，慢了又白等。正确做法是等眼镜端的确认回执（`id` 握手）而不是等墙钟。这也解释了为什么你们要写 generation 抢占、为什么流式回复要套这么多打补丁式逻辑。

### C2. 同一个 JSON 被解析两到三次

> **复核（2026-09-12）：【仍在】。** 现行位置 `RokidLink/src/main/java/com/rokidlab/rokidlink/AiuiLinkActivity.kt:433-441`：`contains` 判类型（433）→ `JSONObject(json)`（435）→ `hostMessage($json)` 传 JS 再解析（441）。

**位置**：`AiuiLinkActivity.kt:433-439`
先用 `contains` 判断 type，再 `JSONObject(json)` 解析一遍，然后 `hostMessage(json)` 传给 JS 又解析一次。

### C3. 主线程读 assets

> **复核（2026-09-12）：【仍在】。** 现行位置 `RokidLink/src/main/java/com/rokidlab/rokidlink/AiuiLinkActivity.kt:333-334`（`createWebView()` 内 `assets.open("ink/index.html").bufferedReader().use { it.readText() }`）。

**位置**：`AiuiLinkActivity.kt:333-334`，`createWebView()` 里 `assets.open("ink/index.html").bufferedReader().use { it.readText() }`。

### C4. 约 120 行死代码

> **复核（2026-09-12）：【已修】。** 已删除 `connectToWifiApi29` 及 4 个 helper（`enableWifiViaReflection`/`enableWifiViaSettingsApi`/`enableWifiViaCXRBridge`/`enableWifiViaShellCommand`），`connectToWifi()` 仅保留 `connectToWifiLegacy()` 单一活路径；同时清理随之无用的 `android.provider.Settings`、`android.net.wifi.WifiNetworkSpecifier` import。文件末尾由 2283 行收敛至 2153 行。

**原位置**：`KeyButtonService.kt:1545-1662`，`connectToWifiApi29` 及其四个 `enableWifiVia*` 变体**从未被调用**（实际只走 `connectToWifiLegacy`）。

### C5. `listFiles` 路径未转义

> **复核（2026-09-12）：【已修】。** 现行位置 `phone-app/src/main/java/com/rokidlab/phone/adb/AdbFileManagerClient.kt:210-211`：`val sh = "ls -la ${shellEscape(inputPath + "/")}"`，已与同文件 `deleteFile`（519 行）/`copyFile`/`renameFile` 统一走 `shellEscape`。原行号 211 未漂移。

**位置**：`AdbFileManagerClient.kt:211`，`ls -la "$inputPath/"` 直接拼接。而同文件的 `deleteFile:519`、`copyFile:579`、`renameFile:633` **都做了 `shellEscape`**。路径含引号或 `$(...)` 会断裂。

### C6. `payloadLength` 缺上界校验

> **复核（2026-09-12）：【已修】。** 两处均已补双重校验：`AdbFileManagerClient.kt:1373-1374`（`< 0` 抛异常 + `> AppConfig.ADB_MAX_PAYLOAD` 抛异常）、`AdbShellClient.kt:665-667`（合并为一次 `payloadLength < 0 || payloadLength > AppConfig.ADB_MAX_PAYLOAD`）。`AdbScreenMirrorClient.kt` 另有 `MAX_ADB_PAYLOAD` 局部常量保护。旧引用 `AdbShellClient.kt:629` 已漂移为 665。

**位置**：`AdbFileManagerClient.kt:1373`、`AdbShellClient.kt:629` 直接 `ByteArray(msg.payloadLength)`。对照 `AdbScreenMirrorClient.kt:857` **是有 `> MAX_ADB_PAYLOAD` 保护的**。头部一旦损坏就有 OOM 风险。

### C7. 传输无完整性校验

> **复核（2026-09-12）：【仍在】。** `AdbFileManagerClient.downloadFile`（332-487 行）虽累计 `totalReceived`（411 声明、444 累加），但仅用于进度日志（446-451 行），未与远端文件大小比对；shell 兜底 `downloadFileShell`（489-512 行）解码 base64 后直接落盘，亦无长度校验；`AdbShellClient.pullFile`（445-538 行）同样只写不校验（A4 修复只保证失败时删空文件，不校验完整性）。**对照上传侧已有校验**：`AdbFileManagerClient.kt:1173-1183` 用 `wc -c < path` 取 `remoteSize` 并比对 `remoteSize == file.length()`——下载侧确实缺失。

`downloadFile` / `pullFile` 都没校验"收到字节数 == 远端文件大小"，静默截断不易察觉。

### C8. 同一份 `pull` 实现写了两遍

> **复核（2026-09-12）：【仍在】。** 两份实现并存且均在被使用：`AdbShellClient.pullFile`（445-538 行）与 `AdbFileManagerClient.downloadFile`（332-487 行，含 shell 兜底 `downloadFileShell` 489-512 行）。A4 修复后两者失败语义已对齐（均删空文件并 `return false`），但代码重复本身未消除。

`AdbShellClient.pullFile` 与 `AdbFileManagerClient.downloadFile` 是大同小异的两份手写实现，结果**一份对一份错**（见 A4）。这类重复是滋生不一致的温床。

---

## 五、建议的修复顺序

| 顺序 | 问题 | 理由 |
|---|---|---|
| 1 | **A1 pendingAction 死锁** | 一行改动，消除"点了没反应"的永久卡死 |
| 2 | **A2 screencap 兜底失效** | 直接关系多品牌投屏黑屏，收益最高 |
| 3 | **A3 工具重试副作用** | 会造成重复拨号这类真实用户损害 |
| 4 | **A4 pullFile 误报成功** | 一行，且已有正确实现可抄 |
| 5 | B1 桥接重复订阅 | 影响稳定性，改法明确 |
| 6 | B2 播放器竞态 | 并发易触发 |
| 7 | B3 空参/id 兼容 | 换国产模型/q本地模型时必现 |
| 8 | B4 / B5 | 小改 |
| 9 | C 类 | 有余力再做 |

**一个总体判断**：这个项目的作者显然很清楚每一处坑在哪——注释里全是精确的实测经验（"订阅返回 0 但实际不投递，仅进程重启可恢复"这种话，是踩过才知道的）。现在的问题不是缺经验，而是**这些宝贵的经验只沉淀成了注释，没有沉淀成机制**。

`checkAiChannelSynced` 已经证明作者懂得用 gradle 任务来防止双端协议漂移。建议把同样的思路用到 A2 上：给 screencap 兜底路径加一个 CI 或启动时的自检（发一帧，解出来验证宽高），让"这条路径是坏的"变成一个会响的警报，而不是一个沉默的黑屏。

---

## 修复进度（2026-09-12 复核，HEAD=cd9c6f0 / v3.5）

> 下表为 2026-09-12 逐条复核结果，文件路径/行号已按 phase3/5 重构后的真实位置更新。A3 的实现已由 `CxrLHiRokidSession.runTool` 迁移至 `domain/AiConversationService.kt`。

| 项 | 文件 | 状态 |
|---|---|---|
| **A1** pendingAction 永久死锁 | `phone-app/.../app/MainActivity.kt:740-763` | ✅ 已修：否定分支清空 `pendingAction`（146/166 行）+ 收敛到 `proceedIfPrerequisitesReady()` |
| **A2** screencap 降级恒黑屏 | `phone-app/.../adb/AdbScreenMirrorClient.kt:608-705` | ✅ 已修：改用 `screencap -p` 输出 PNG，按签名/IEND 切帧解码，去硬编码分辨率 |
| **A4** pullFile 把 FAIL 当成功 | `phone-app/.../adb/AdbShellClient.kt:445-538` | ✅ 已修：显式识别 `FAIL` → 删空文件、`return false`（525/538 行） |
| **B1** 眼镜端断连无条件重建倍发 | `RokidLink/.../KeyButtonService.kt:886-911` | ✅ 已修：加 `pendingReconnect`（887 行）去重 + `if (bridgeConnected) return` 守卫（901 行）+ 重建前清理旧 bridge |
| **B4** 记忆删除 LIKE 通配符 | `phone-app/.../ai/LongTermMemoryManager.kt:202-207` | ✅ 已修：`ESCAPE '!'`（204 行）+ 转义 |
| **B5** 表达式结果未 return | `phone-app/.../ai/AiuiProject.kt:485-486` | ✅ 已修：`return@runBlocking if (...) ...` |
| **A3** 工具重试致重复副作用 | `phone-app/.../domain/AiConversationService.kt:374-396` / `ai/ToolRegistry.kt:92-97` | ✅ 已修：`ToolRegistry.SIDE_EFFECT_TOOLS` 标记 14 个副作用工具，命中则 `maxAttempts = 1`（378 行） |
| **B2** 播放器并发竞态 | `phone-app/.../ai/MusicPlayerController.kt` | ✅ 已修：`opLock`（98 行）串行化 `play`（107 行）/`stop`（194 行），杜绝双音轨叠加与 MediaPlayer 泄漏 |
| **B3** 空 arguments / 缺 tool_call_id | `ai/ToolRegistry.kt:471-480` / `ai/OpenAiService.kt:444-447,562` | ✅ 已修：空串按 `{}`、非法 JSON 兜底空对象；`resolveToolId` 为缺失 id 生成 `call_<uuid>`（旧 `SseStreamAccumulator.kt` 已不存在） |
| **C5** 路径未转义 | `phone-app/.../adb/AdbFileManagerClient.kt:210-211` | ✅ 已修：`ls` 路径走 `shellEscape`（与 delete/copy/rename 一致） |
| **C6** payloadLength 无上界 | `phone-app/.../adb/AdbFileManagerClient.kt:1373-1374` / `adb/AdbShellClient.kt:665-667` | ✅ 已修：`< 0` 与超 `ADB_MAX_PAYLOAD`(1MB) 均抛异常，防头部损坏导致 OOM |
| **C4** ~120 行死代码 | `RokidLink/.../KeyButtonService.kt` | ✅ 已修：删除 `connectToWifiApi29` + 4 个 `enableWifiVia*` 备胎（-130 行）与无用 import |

> ⚠️ A2 根因更正：原审计写" screencap 输出 PNG 不是裸 RGBA"仅对带 `-p` 成立；原命令没带 `-p`，实际是裸 RGBA + 12 字节头，格式假设本没错，**真 bug 是硬编码 480×640 + 裸流缓冲装不下整帧**。最终仍选 PNG 方案（更稳）。

### 待修（2026-09-12 复核确认【仍在】，需单独评审）

- **C1** **63 处** sleep 编排协议时序（每次对话固定 ~2.6s）——属协议握手重排，需改为等眼镜端确认回执，改动面大，建议单独一轮。
- **C2** 同一 JSON 解析 2~3 遍（`RokidLink/.../AiuiLinkActivity.kt:433-441`）——纯性能优化，低风险可后续做。
- **C3** 主线程读 assets（`RokidLink/.../AiuiLinkActivity.kt:333-334`）——可移到后台线程，低风险。
- **C7** 传输无完整性校验（`AdbFileManagerClient.downloadFile`:332-487 / `AdbShellClient.pullFile`:445-538 均未比对"收到字节数 == 远端大小"）——需加长度/CRC 校验，建议与 C8 一并做。
- **C8** `pullFile`/`downloadFile` 双实现（`AdbShellClient.kt:445-538` 与 `AdbFileManagerClient.kt:332-487` 并存）——建议统一为一份，消除不一致温床。
