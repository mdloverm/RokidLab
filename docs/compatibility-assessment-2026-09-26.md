# RokidLab 兼容性专项评估报告

> 评估日期：2026-09-26 · 评估范围：phone-app + RokidLink · 代码版本 v4.1 (versionCode 26/19)
> 重点：Android 新老版本与各厂商机型的兼容性

---

## 一、总体结论

**兼容性工程质量：优秀（同类侧载工具类 App 中属第一梯队）。**

核心基线：`minSdk 29`（Android 10）/ `targetSdk 34` / `compileSdk 34` / 仅 `arm64-v8a`。

代码中几乎每一个 Android 大版本的破坏性变更都有对应的版本分支和守卫（通知权限、PendingIntent 可变性、动态 receiver 导出标志、FGS 类型、exact alarm 双权限、分区存储、蓝牙运行时权限、16KB 页面……），且保活链路针对国产 ROM 做了实测级适配。**没有发现会在真机上直接崩溃的 P0 级兼容性缺陷**；发现 1 个确认的功能性 bug（隐藏 API 反射类名写错，有降级不崩）和若干值得跟进的 P2 项。

老机型覆盖下限：**Android 10（2019）+ 64 位 CPU**。Android 9 及以下、32 位机型不在支持范围（属产品决策，见 §5.2）。

---

## 二、版本逐版本矩阵（phone-app）

| Android 版本 | 该版本的关键变更 | 本项目状态 |
|---|---|---|
| **10 (API 29, minSdk)** | 分区存储落地、明文流量默认禁 | ✅ MediaStore 分支齐全（FileWorkspace/ArchiveTools/ChatMediaSaver 均 Q+ 分支）；networkSecurityConfig 显式声明 |
| **11 (API 30)** | 包可见性过滤、MANAGE_EXTERNAL_STORAGE | ✅ 有 `<queries>` 精确声明 + QUERY_ALL_PACKAGES 兜底；`Environment.isExternalStorageManager()` 运行时守卫（ProotShell:255） |
| **12 (API 31)** | PendingIntent 必须声明可变性、蓝牙运行时权限拆分、exact alarm 需用户授 | ✅ 全仓 8 处 PendingIntent 均显式带 IMMUTABLE/MUTABLE；BLUETOOTH_CONNECT 分支齐全（BluetoothHidManager）；SCHEDULE_EXACT_ALARM + canScheduleExactAlarms 守卫（TimerScheduler:205） |
| **13 (API 33)** | 通知运行时权限、per-app language、可预测返回 | ✅ POST_NOTIFICATIONS 运行时请求（MainActivity:378 + AppPermission TIRAMISU 条目）；AppCompatDelegate 走 appcompat 兼容层 |
| **14 (API 34, targetSdk)** | FGS 必须声明类型、动态 receiver 必须声明导出性 | ✅ FGS 类型齐全（mediaProjection×2、specialUse+SUBTYPE property）；非系统广播 3 处均 33+ 分支 RECEIVER_NOT_EXPORTED，系统广播豁免项合规 |
| **15 (API 35)** | edge-to-edge 强制（仅 targetSdk 35）、16KB 页面 | ✅ targetSdk 34 不受强制；MainActivity 已有 API 35 前瞻分支；16KB 通过 useLegacyPackaging=true 解决（见 §4） |
| **16 (API 36)** | 16KB 页面成新机默认 | ✅ 已提前处理：`useLegacyPackaging = true`（so 压缩存储、安装时解压，官方文档认可路线）+ opencv 4.12.0 / onnxruntime 1.22.0 16KB 对齐版本 |

RokidLink（眼镜端）：minSdk 28，跑在固定 ROM 上，无跨机型差异问题；BLUETOOTH_CONNECT、POST_NOTIFICATIONS、connectedDevice FGS 类型均已声明。

---

## 三、厂商 ROM 兼容性（国产机型重点）

已落地的适配（质量高，多处在注释中标注了真机实测结论）：

1. **厂商检测**：`ManufacturerUtils.detect()` 覆盖小米/Redmi/POCO、华为、荣耀、OPPO/realme/一加、vivo/iQOO、魅族、三星、Google。
2. **保活四重防线**（针对国产 ROM 激进的省电策略）：
   - 前台服务（specialUse 类型）+ START_STICKY；
   - `onTaskRemoved` 后 2s AlarmManager 闹钟重启（MIUI 神隐/一键优化抑制 STICKY 重建时的双保险）；
   - PARTIAL_WAKE_LOCK 防止 HyperOS/MIUI cgroup v2 冻结（注释有实测依据）；
   - BOOT_COMPLETED 开机自启。
3. **设置页跳转候选链**：自启动/电池/悬浮窗/权限四类，每类按「ROM 版本贴近度」排候选链，末尾兜底应用详情页——避免了「单支 Intent 在某个 ROM 版本失效后静默没反应」的经典坑。
4. **vivo 10 分钟后台硬限制检测**、**三星 31+ 特判**、**ColorOS 13+ 判定**均已实现。
5. **无 GMS 依赖**：OCR 本地（onnxruntime）、模型走 Gitee 下载 ⇒ 华为/鸿蒙机可完整使用（前提是鸿蒙 4.x 及以下仍兼容 APK；HarmonyOS NEXT 不跑 Android 应用，不在兼容范围）。

---

## 四、发现的问题

### P1（建议尽快修）

**① RomAdapter.setFrameRatePowerSavingsBalanced 反射类名写错（确认 bug）**
`RomAdapter.kt:53`：`Class.forName("android.view.WindowLayoutParams")` —— 该类不存在，正确类名是 **`android.view.WindowManager$LayoutParams`**。后果：API 35+ 设备上此调用**必然** ClassNotFoundException → 永远走 `Capability.Unavailable` 降级。不崩、有日志，但 API 35 高刷省电开关是条死分支。修复为一行：`Class.forName("android.view.WindowManager\$LayoutParams")`。
（同文件 `setRequestedFrameRate` 用 `View::class.java` 是对的，不受影响。）

### P2（跟进项，按优先级排序）

**② 厂商枚举死分支**：`detect()` 把 `oneplus` 品牌映射进 OPPO（ColorOS 合并后合理），`Manufacturer.ONEPLUS` 枚举值成为永远命不中的死代码。另外老一加机型（氢 OS 时代，Android 10/11 的 OnePlus 8/9）不带 `com.coloros.safecenter`，其自启动页候选链会全部落空 → 落到兜底应用详情页（功能可用但引导体验降级）。建议要么删掉死枚举，要么给一加补氢 OS 时代候选。

**③ 老机型覆盖下限需产品确认**：minSdk 29 + 仅 arm64-v8a ⇒ Android 9-、32 位机型（2018 年前的主力机 + 部分 2019 低端 Android 10 机）装不上。2026 年这部分占比已小，但若目标用户含「旧备用机做眼镜伴侣」场景，可评估 minSdk 降到 26-28 的成本（主要是分区存储与 notification channel 回补）。

**④ targetSdk 35 迁移债（已部分准备）**：升 35 时 edge-to-edge 将被强制（需处理 insets），predictive back 当前是显式 opt-out（`enableOnBackInvokedCallback="false"`，合规），迁移时需改造返回逻辑。MainActivity 的 API 35 高刷分支说明前瞻工作已开始。

**⑤ 上架 Play 的政策雷区**（当前侧载分发则无碍）：QUERY_ALL_PACKAGES、MANAGE_EXTERNAL_STORAGE、REQUEST_IGNORE_BATTERY_OPTIMIZATIONS、specialUse FGS、SEND_SMS 这五项在 Play 都是高审查项。建议在文档中固化「本项目为侧载分发」的前提，防止将来误上架。

**⑥ 保活 WakeLock 无超时**：`acquire()` 永久持有。这是防 cgroup 冻结的刻意设计（注释有论证），但意味着只要保活开关开着就持续耗电。建议至少在设置页展示「保活=持续耗电」的提示（若已有则忽略）。

**⑦ SNAPSHOT 依赖可复现风险**：`cxr-service-bridge:1.0-20260715.121510-107` 是固定时间戳 SNAPSHOT，Nexus 清理后构建即断。注释里已自知，建议找时间验证 release 1.0 并切换。

**⑧ 隐藏 API 反射依赖 blocklist 政策**：SystemProperties.get、setRequestedFrameRate、preferredDisplayModeId 等走反射，若将来 Google 调整 blocklist 可能失效——均已 `Capability` 包装优雅降级，属可接受风险。

**⑨ 平板/折叠屏**：GamepadActivity 硬编码 `landscape`，Android 12+ 大屏会忽略并 letterbox（不崩、可玩性无碍）；整体 UI 未针对大屏/分屏做适配，折叠屏内屏展开时是拉伸布局。

---

## 五、明确合规、无需担心的项（排查过）

- **动态 receiver**：33+ 三处非系统广播均正确 RECEIVER_NOT_EXPORTED；`BluetoothHidManager` 的 discovery/bond receiver 收的是系统保护广播（豁免项）；`PhoneTools:495` 的 null-receiver 粘性广播读取不适用该规则。
- **PendingIntent**：AVRCP 媒体键、安装回调两处**刻意**用 FLAG_MUTABLE（系统要求可改写），其余全部 IMMUTABLE——不是遗漏。
- **Android 14 FGS 严格化**：`LabKeepAliveService` 在 API 29-33 上把 SPECIAL_USE 常量传给 `startForeground`，老平台会把未知位掩掉，等价于无类型启动，不会崩（34+ 才校验 manifest 类型，manifest 已声明）。
- **16KB 页面**：压缩存储路线对**全部** so（含 CXR bridge 的 11 个库）统一生效，Android 16 新设备可正常安装。
- **权限模型**：`AppPermission` 集中登记 + minSdk 标注 + PermissionBridge 统一引导，`AppPermission.canScheduleExactAlarms/canDrawOverlays/isExternalStorageManager` 全走 AOSP 契约 API（跨 ROM 语义一致）。

---

## 六、建议行动清单

| 优先级 | 动作 | 成本 |
|---|---|---|
| 1 | 修 RomAdapter.kt:53 类名（§四①） | 1 行 |
| 2 | 真机回归矩阵建议：Android 10（老）、13（过渡）、14（主力）、HyperOS 2 / HarmonyOS 4 各一台，重点验保活自愈与安装链 | 半天 |
| 3 | 清理 ONEPLUS 死枚举或补氢 OS 候选链 | 半小时 |
| 4 | 验证 cxr-service-bridge release 1.0 替换 SNAPSHOT | 半天（含真机回归） |
| 5 | 侧载分发前提写入 README/DEV_GUIDE（§四⑤） | 文档 |
| 6 | targetSdk 35 升级排期时把 edge-to-edge insets 与 predictive back 迁移列入 | 专项 |

---

*本报告基于静态代码审查 + 构建配置分析；未执行真机测试（遵循项目约定，实测由用户执行）。*
