# RokidLab 功能与 Bug 专项排查（2026-09-13）

> **范围声明**：本轮**只看功能正确性与 Bug**。凭据 / 明码类问题（Gitee PAT / IFDIAN / KUWO 三个 token、`gradle.properties` 签名口令）按用户明确指示**整体排除**，不再列为问题、不建议轮换，也不计入本报告。
>
> - 基准：HEAD = `6e46eee`，工作区 **29 项未提交改动**（+1685 / −544）
> - 方法：只读静态排查（Read / Grep / Git 只读）+ 关键结论逐条源码实证
> - 所有结论均附 `文件:行号` 证据

---

## 1. TL;DR —— 现在必须修 4 条

| # | 缺陷 | 触发条件 | 用户会看到什么 | 优先级 |
|---|---|---|---|---|
| **F1** | 推送 `.aix` 后**宿主没打开却上报 `"OK"`** | 落盘成功但拉起失败（含 F2） | 弹「打开成功 / 正在眼镜上演示」，**眼镜上什么都不出现** | **P0** |
| **F2** | ADB `am start` 直启兜底路径**已失效**（`exported=false` 之后） | 眼镜端 `balExempt=false`，或 bridge 返回非 0 | AIUI 宿主打不开；且**被 F1 掩盖成"成功"** | **P1**（BAL 拿不到则 P0） |
| **F3** | `TimerScheduler` 兜底短连接失败后**不降级、不记账** | 眼镜离开 WiFi 而手机仍在 WiFi（缓存未失效） | 定时任务（拉 App / 发通知 / 按键）**静默失败最长 60 秒** | **P1** |
| **F4** | 文件管理器下载**只信 `DONE`、不做字节对账、失败不删半成品** | 蓝牙隧道下 adbd 提前收流 | 「下载成功」但存下**截断文件**；失败残留半成品 | **P1** |

**一句话**：F1 与 F2 是同一个故障的两面 —— **拉起失败被伪装成成功**，这正是项目历史上反复踩过的「AI 工具假成功」模式；`AdbShellClient.pullFile` 已经修好了同类问题，但文件管理器那条路径漏改了。

---

## 2. F1（P0）— 拉起失败被无条件上报为 `"OK"`

**证据**：`phone-app/.../glasses/AiuiFrontendController.kt:301-304`
```kotlin
is com.rokidlab.phone.platform.Capability.Available -> {
    if (openAfter) openHostBestEffort(client, name, launchParams, balExempt)  // ← 返回值被丢弃
    return "OK"                                                              // ← 无条件 OK
}
```

**消费方确实按 `"OK"` 判成功**：
- `store/AiuiManagePage.kt:202-208`：`val ack = ...pushAixToRokidLinkHost(pkg)` → `if (ack?.trim() == "OK") 0 else -1` → 成功即显示 `aiui_open_ok`
- `ai/tools/AiuiToolProvider.kt:61-66`：`ack?.trim() == "OK"` → 返回「好的，正在眼镜上演示「XXX」（手柄可控宿主）」

**用户可见现象**：手机端提示打开成功，眼镜上黑屏无反应。用户会反复重试，最终判定为「功能没实现」。

**最小修复**：让 `openHostBestEffort` 的返回值参与判定 —— 拉起失败时不要返回 `"OK"`（例如返回 `"PUSHED_OPEN_FAILED"`，或把 open 结果拼进返回串），使上层能区分「落盘成功」与「已拉起」。

---

## 3. F2（P1）— `exported=false` 之后，ADB 直启兜底已死

**证据链**：
- `RokidLink/src/main/AndroidManifest.xml:76-82`：`AiuiLinkActivity android:exported="false"`
- `AiuiFrontendController.kt:217`：`am start -n com.rokidlab.rokidlink/.AiuiLinkActivity --es aix_path ...`
- `AiuiFrontendController.kt:208` 注释仍写「AiuiLinkActivity 为 exported=true，shell 可拉起」—— **失实**

**路径判定**（`openHostBestEffort`，`:189-202`）：
```kotlin
if (balExempt) {
    val r = openAiuiHost(fileName, launchParams)
    if (r == 0) return true                                   // bridge 成功
    Log.w(TAG, "bridge openAiuiHost returned $r, fallback to ADB am start")  // ← 关键
}
val c = client ?: return false
return launchHostViaAdb(c, glassesHostPath(fileName), launchParams)   // ← 已死
```
- `balExempt == true`：走 bridge 主路径，正常。**但 bridge 一旦返回非 0，回落的就是这条死路径** → 从"可降级"变成"硬失败"。
- `balExempt == false`：**跳过 bridge，直接走死路径** → 宿主必然打不开。

**是否有别的路径兜住**：有。`RokidLink/.../KeyButtonService.kt:474 AIUI_HOST_TOPIC = "rokidlab_aiui_host"`，收到后由**同进程**调 `AiuiLinkActivity.open(...)`（非导出无妨）。这是正常工作的主路径。

**结论**：**不会让 100% 用户"AIUI 打不开"**（主路径正常时无感），但只要某台真机 `SYSTEM_ALERT_WINDOW` 未授权成功、或 bridge 调用失败，就会打不开 —— **且被 F1 伪装成成功**。

**最小修复**：兜底改走 `rokidlab_aiui_host` topic（由同进程 `KeyButtonService` 调 `AiuiLinkActivity.open()`）；同时删掉 `:208` 的失实注释。

**待真机确认**：`ShellOps.isSystemAlertWindowAllowed` 在 RG 眼镜上能否拿到 `allow`，决定 F2 是 P1 还是 P0。

---

## 4. F3（P1）— 定时任务兜底短连接不降级、不记账

**证据**：`phone-app/.../adb/TimerScheduler.kt:251-268`
```kotlin
val route = app.routeManager.resolve(ip, ADB_PORT)                 // :251
... AdbShellClient(appContext, pair.first, pair.second)            // :258
if (client.connect()) { block(client) }
else { Log.w(TAG, "withAdbClient: connect failed") }               // :267 ← 只有一行日志
```
无 `noteWifiFailure()`、无 `tunnelTo()` 降级。

**机制修正（重要）**：这里端口就是 `ADB_PORT = 5555`，而 `probeWifiHost(wifiIp)` 实际就是 `probeTcp(wifiIp, 5555)`（`ConnectionRouteManager.kt:254-257`）→ **选路本身不乐观**。真实失败模式是**陈旧缓存**：`ROUTE_CACHE_TTL_MS = 60_000`（`:81`）且缓存失效只依赖 `lastWifiFailureAt`；眼镜离网而手机 WiFi 未掉线时 `ConnectivityManager.onLost` 不触发 → 缓存继续指向死 WiFi 最长 60s。

**被放大的场景**：用户正在投屏/文件浏览（持 `LONG_LIVED` 租约）→ `getAdbShellClient()` 的 `shouldYield` 让路返回 null → 定时任务**必然**走这条 ② 兜底路径。

**用户可见现象**：定时任务（`LaunchApp` / `ExecuteShell` / `Tap` / `SendKeyEvent` / `SendNotification`）静默失败，App 内只留一行 `Log.w`，用户无任何提示，最长持续 60s。

**最小修复**：`:267` 的 `else` 分支补 `noteWifiFailure()` + 一次 `tunnelTo(ADB_PORT)` 重试；或直接复用 `AdbTransport`。

---

## 5. F4（P1）— 文件管理器下载：不对账、失败不删半成品

**证据**：`phone-app/.../adb/AdbFileManagerClient.kt:488-521`
```kotlin
"DONE" -> {                          // :488
    ...
    downloadSuccess = true           // :495 ← 收到 DONE 即判成功，无远端字节数对账
    break@download
}
} catch (e: Exception) {             // :514
    ...
    try { sendPacket(CMD_CLSE, ...) } catch (_: Exception) {}   // :517 只关流
    false                            // :519 ← 不删 localPath 半成品
}
```

**对比同项目已修好的那条路径** —— `AdbShellClient.kt:371-372` 的注释白纸黑字写着：
> 「sync 流的 `DONE` 不足以证明拉全了 —— 蓝牙隧道下 adbd 会在送出 1~2 个 64KB 分片后直接把流收掉，此时既没有 `DONE` 也没有 `FAIL`。只有拿远端 `stat` 对账」

且 `:706` 补充了「**也可能发了 DONE 却提前放弃（A4/C7 事故形态）**」，收尾以 `expectedSize` 为唯一凭证；失败时 `localFile.delete()`（`:714/:726`）。

**结论**：同一隧道、同一事故形态，`AdbShellClient.pullFile` 已修，`AdbFileManagerClient.downloadFile` 漏改 → 文件管理器可能「下载成功」拿到截断文件，失败时在 `cacheDir` 残留半成品（磁盘泄漏，且会被下一次预览覆盖）。

**最小修复**：`downloadFile` 收尾与 `pullFile` 同源 —— 传入 `expectedSize`（`FileItem.size` 已有）做字节对账；`catch` 里 `runCatching { File(localPath).delete() }`。另 `downloadFileShell`（base64 降级，`:526-551`）同样无对账。

---

## 6. 降级契约消费者总表（补齐上轮遗留缺口）

**契约源头**：`ConnectionRouteManager.kt:144`
```kotlin
if (wifiIp.isNotBlank() && (probeTcp(wifiIp, wifiPort) || probeWifiHost(wifiIp))) { ...Wifi... }
```
`probeWifiHost(wifiIp) = probeTcp(wifiIp, 5555)`（`:254-257`）。

**关键澄清**：
- `wifiPort == 5555` 时判据退化为「5555 真可连」→ **不存在乐观选错**，风险只是陈旧缓存窗口。
- 只有 `wifiPort ∈ {8848, 7654, 7656, 7658}` 才会「adbd 通即选 WiFi，目标端口可能 ECONNREFUSED」→ 这才是乐观降级的适用面。

| 调用点 | 端口 | 失败处理 | `noteWifiFailure` | BT 重试 | 判定 |
|---|---|---|---|---|---|
| `ai/AiuiProject.kt:498` | 8848 | ✅ | ✅ `:509` | ✅ `:510` | 合规 |
| `ai/AiuiProject.kt:579` | 8848 | ✅ | ✅ `:596` | ✅ `:597` | 合规 |
| `glasses/AiuiFrontendController.kt:324` | 7658 | ✅ | ✅ `:328` | ✅ `:332` | 合规 |
| `mirror/PhoneMirrorService.kt:700-744` | 7654 | ✅（3 次失败后降级） | ✅ `:717` | ✅ `:720` | 合规 |
| `hid/GamepadActivity.kt:480-517` | 7656 | ✅ | ✅ `:513` | ✅ `:514` | 合规 |
| `glasses/CxrLHiRokidSession.kt:361-393` | 5555 | ✅ 经 `AdbTransport` | ✅ `:391` | ✅ | 合规 |
| `platform/AdbTransport.kt:119-196` | 5555 | ✅ `isCachedWifiRouteAlive` | ✅ `:160/:170/:194` | ✅ | **合规（实现扎实）** |
| `app/MainActivity.kt:225` | mirrorPort | 降级在 Service 内 | n/a | ✅ | 合规 |
| **`adb/TimerScheduler.kt:229-274`（② 兜底）** | 5555 | ❌ 只 `Log.w` | ❌ | ❌ | **⛔ 违反（F3）** |
| **`feature/FileManagerStateHolder.kt:87-107`** | 5555 | ❌ 报「连接失败」 | ❌ | ❌ | ⛔ 违反（轻度） |
| **`feature/ScreenMirrorStateHolder.kt:95-155`** | 5555 | ❌ 报「连接失败」 | ❌ | ❌ | ⛔ 违反（轻度） |
| **`mirror/PhoneMirrorActivity.kt:155-176`** | 7654 | ❌ 无任何降级 | ❌ | ❌ | ⛔ 违反（**疑似死代码**） |

**V2 补充**：`PhoneMirrorActivity.kt:90-96` 调 `PhoneMirrorService.startService(this, ipAddress, port, ...)` **未传 `isBluetooth`**（对比 `MainActivity.kt:248` 传了 `isBluetooth = isBT`）。且全仓无 `createIntent` 调用者 → **疑似死代码**，需确认后再决定修或删。

**统一修复建议**：别让每个调用方各写一遍降级 —— 在路由层抽一个原语（如 `routeManager.withRouteOnce { }`，内部完成「WiFi → catch → noteWifiFailure → BT 重试一次」）。

---

## 7. 次级问题（P2）

| 项 | 证据 | 用户可见影响 |
|---|---|---|
| **ASR 下行可被同网段主机挤掉** | `RokidLink/.../AsrPushServer.kt:144` `server.bind(InetSocketAddress(WIFI_PORT))`（绑 `0.0.0.0:7660`）+ `:173-178` `attachClient` **替换式单客户端** | 同网段任意主机连上 7660 即关掉真手机的连接 → **眼镜上 AI 回答文字停更**；并可读取文本 |
| **5555 消费者的陈旧缓存窗口** | `ConnectionRouteManager.kt:81` TTL 60s + 失效仅靠 `lastWifiFailureAt` | 眼镜离网但手机在网时，「连接失败」最长持续 60s，重试同样失败 |
| **`AdbTransport.get()` 持类锁做阻塞探测** | `platform/AdbTransport.kt:119` `@Synchronized`；内部 `isCachedWifiRouteAlive`（`:191` `probeTcp` 2s 超时）与 `endpointProvider → runBlocking { resolve }` 都在锁内 | 空闲 >5s 的取用会锁住约 2s，阻塞并发 `get()/release()` → AI 工具变慢（当前调用方均在后台线程，无 UI 卡死） |
| **副作用确认闸门空转** | `ai/ToolRisk.kt:31-78` 无任何真实工具登记 `EXTERNAL_SIDE_EFFECT`；`ai/ToolPolicy.kt:104-122` 只在 EXTERNAL 时走确认 | 防护形同虚设（**注意**：fail-open 是刻意设计，故这不是"功能被挡"，而是"以为有防护"） |
| **酷我直链强制 https** | `ai/KuwoMusicApi.kt:86-96` `toHttps()` | 若部分 CDN 节点不支持 https → 「能搜到、放不了」 |
| **`downloadFileShell` 无对账** | `AdbFileManagerClient.kt:526-551` | 仅在 sync 流打不开时兜底；历史实测「送出 6MB 后截断」 |

---

## 8. 已确认**没问题**的部分（避免误改）

这些是团队成员本轮核实后判定「改动正确 / 实现扎实」的，不要动：

- **共享 ADB 会话 `platform/AdbTransport.kt:119-196`** —— 连接失败同时 `onRouteFailure()`（清线路缓存）与 `onWifiFailure()`（WiFi 端点额外记账），并有 WiFi 空闲存活探测防「看起来健康的死 socket」。是本次 WiFi 改造里最扎实的一块。
- **`AsrPushClient.kt:167-174`** —— 修掉了「`connect()` 前先置 conn 导致 `isConnected` 建链期假 true → 跳过文件兜底 → 丢字」的老 bug。
- **`AsrBridgeCoordinator.kt:286-287`** —— 先 `stop()` 再新建，无双客户端互挤。
- **`ChatImageCache.kt:84-115`** —— 改为「先算 inSampleSize 再解码 + `bmp.recycle()`」，修 OOM 闪退。
- **`KeyButtonService.kt` onCreate 补 `BtTunnelService.start(this)`** —— 修复眼镜进程被强杀后隧道回不来。
- **删除 `AdbPasteCompat.kt` 无能力丢失** —— 粘贴改走共享会话 `input keyevent KEYCODE_PASTE`（`GamepadActivity.kt:526-528`），失败还有 HID Ctrl+V 兜底（`:538-541`）；全仓无悬挂引用。
- **协议层降级链完整** —— 8848 / 7654 / 7656 / 7658 四类乐观端口消费者全部实现了 BT 重试。

---

## 9. 建议修复顺序

1. **F1（P0）** —— 一行改动量级（让返回值参与判定），消除「假成功」，这是最容易被用户当成"功能没做"的问题。
2. **F2（P1）** —— 与 F1 同属一条链路，一起改最省事：兜底改走 `rokidlab_aiui_host` topic + 删失实注释。
3. **F3 + V3（P1/P2）** —— 抽 `routeManager.withRouteOnce {}` 原语，一次性覆盖 TimerScheduler / FileManagerStateHolder / ScreenMirrorStateHolder 三个违反点，并顺手解决陈旧缓存窗口。
4. **F4（P1）** —— 让 `downloadFile` 与 `pullFile` 收尾同源（字节对账 + 失败删半成品）。
5. **P2 逐项** —— ASR 监听面收紧（校验对端或仅在 RFCOMM 不可用时才开 TCP）、`AdbTransport` 探测移出锁、确认 `PhoneMirrorActivity` 是否死代码。

---

## 10. 需真机确认

1. **`balExempt` 实际取值**（决定 F2 是 P1 还是 P0）—— 需确认 RG 眼镜上 `isSystemAlertWindowAllowed` 能否拿到 `allow`。
2. **`PhoneMirrorActivity` 是否死代码** —— 若是，V2 无实际影响可直接清理。
3. **F4 是否真会「收到 DONE 但截断」** —— 若真机从未出现，截断风险降为理论值，仅剩「失败不删半成品」的磁盘泄漏。
4. **酷我 CDN 全节点 https 可用性** —— 需真机放歌验证。
5. **`RokidLinkController.queryInstalledApps` 的 `onComplete` 是否有超时保证** —— 不回调会使引导安装流程无限挂起（无超时保护）。

---

*本报告为只读排查产物，未修改任何文件。凭据 / 明码类问题按用户指示已整体排除。*
