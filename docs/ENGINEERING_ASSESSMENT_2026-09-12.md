# RokidLab 全项目工程质量评估与优化方案

- **评估对象**：`D:\rokidapp\cxrl\RokidLab`（Gradle 8.7 / JDK 17 / Kotlin 2.0.20 / Compose BOM 2024.08.00 / AGP 8.5.2 / compileSdk 34 / minSdk 29 / v3.5）
- **评估日期**：2026-09-12
- **评估方式**：**只读静态分析**——源码阅读（Read/Glob/Grep）+ 脚本统计（行数、方法长度、密钥扫描、APK 体积解析）+ Git 历史核对。**未修改任何源码、未执行构建、未连接真机。**
- **评估口径**：本轮唯一工作目录为 Git 工作区。行数均为实测值（含空行按 `wc -l`；`CxrLHiRokidSession.kt` 另附非空行数）；体积按十进制 MB。
- **与既有文档的关系**：仓库内已存在 `docs/ARCHITECTURE_REVIEW.md` 与 `docs/CODE_AUDIT.md`（2026-09-10 自评）。本报告**独立复核**并更新其滞后数据；凡与自评冲突处均已实测澄清，并在 §6 标注。

---

## 1. 结论 TL;DR

**主观评级：B-** —— 技术实现能力 A 级，工程保障体系 C 级。

一句话概括：**这是一个深度与完成度都很高、但长期"靠作者脑子维护"的资深项目**——**2026-09-12 集中整改后，测试护栏（14 类 / 155 例）与构建门禁（4 道 `preBuild` + 2 道发布闸门）已补上**，剩余短板集中在状态层（P1-5）、上帝类（P1-1/P1-2）与凭据轮换（P0-2/P0-3）。

**真实亮点（不常见，应当保留）**
- 四道 `preBuild` 门禁（协议同源 + i18n key + 关键链路空 catch + 全仓空 catch 预算）与两道 `packageRelease` 发布闸门（洁净工作区 + 必须跑绿单测）——用构建失败强制纪律，是很成熟的工程直觉（`phone-app/build.gradle.kts` / `gradle/local-gates.gradle.kts`）。
- `platform/Capability.kt` / `SdkBridge.kt` / `RomAdapter.kt` / `ShellOps.kt` —— 用**显式降级**取代静默反射与空 catch，方向完全正确。
- `ToolRegistry` 域装配 + schema 缓存、`ToolRiskMap` 启动自检（`LabApplication.kt:134-141`）。
- `useLegacyPackaging=false` 的 16KB 页面对齐处理、R8 keep 规则针对性配置。
- JVM 单测（**2026-09-12 已增至 155 个 / 14 个测试类**，覆盖 ADB sync、HID 报表、`AiChannel` 版本矩阵、`ToolRiskMap`、聊天历史落盘格式与迁移）、3260 行成体系文档、Git 提交信息规范（vX.Y 前缀 + 内容摘要）。

**最大的三个风险**
1. **真实可达的攻击面**：release 包内置一个 `exported=true` 的调试广播接收器，经它可在眼镜上执行**任意 shell 命令**（P0-1）；眼镜端 `AiuiLinkActivity` 导出且接受任意 `aix_path`，页面 JS 可直调副作用工具（P0-4）。
2. **密钥管理失控且已知未改**：Gitee PAT（4 处明文，含写权限）、爱发电 token、酷我 token 全部硬编码入库；release 签名口令与别名亦已随 `gradle.properties` 提交并推送到远端（P0-2 / P0-3）。
3. **核心链路无测试保护**：20+ 个"上帝类"（单文件最高 2,282 行 / 87 个函数）承载全部 AI 与设备控制能力，而测试全部集中在最好测的 `ai/` 纯逻辑层（P1-11）。**2026-09-12 已补两批**（#8：ADB sync / HID 报表 / `pullFile` FAIL / `AiChannel` v0·v1 矩阵 / `ToolRiskMap` 38 例；#9：`ChatHistoryStore` 落盘格式与迁移 14 例），但 `KeyButtonService` / `ChatStateHolder`（本体）/ `CxrLHiRokidSession` **仍无一有测试**。

---

## 2. 量化概览

| 指标 | 数值 | 备注 |
|---|---|---|
| 源码文件（.kt/.java，排除 build 与 `.gradle/` 生成物） | **195** | phone-app main 160 + test 14；RokidLink main 19 + test 1；phone-app `src/debug` 1（`DebugAiuiReceiver`） |
| 源码总行数 | **≈52,586** | 含测试 2,052 行（#8 新增测试 ~646 行、#9 新增 `ChatHistoryStore` + 其测试 ~337 行） |
| 平均文件行数 | ≈ **270** | |
| 最大文件 | **2,282** `RokidLink/.../KeyButtonService.kt` | |
| 方法总数（解析） | 1,719 | ≥150 行 **16 个**；≥100 行 **32 个** |
| TODO / FIXME / XXX | **16** | Top：`adb/ui/SysInfoDialog.kt`(4)、`hid/GamepadActivity.kt`(3) |
| `runCatching` | **315** | |
| 空 `catch(...) {}` | **68**（`catch (_:` 65） | 静默失败主来源 |
| `Log.*` 调用 | **1,281** | 无结构化日志 |
| 裸 `Thread {}` / `Executors` | **96 / 9** | `Dispatchers.IO` 61，`runBlocking` 13 |
| `Thread.sleep` | **50** | 最多 `AdbScreenMirrorClient`(9)、`BluetoothHidManager`(8) |
| `mutableStateOf` / `mutableStateListOf` | **317 / 4** | |
| `StateFlow` / `collectAsState` / ViewModel | **0 / 0 / 0** | 状态层完全靠 Compose state + 单例 |
| 顶层 `object` 单例 | **≈73** | |
| 测试类 / `@Test` | **14 / 155** | 2026-09-12 两批：9 / 102 → 13 / 141（#8：新增 `AdbSyncProtocolTest`(8) / `AdbFileManagerSyncTest`(6) / `HidReportTest`(12) / `ToolRiskMapTest`(8)，`AiChannelTest` 17→21、`RokidLink/AiChannelProtocolTest` 4→5）→ 14 / 155（#9：新增 `ChatHistoryStoreTest`(14)） |
| 工具数（`ToolRegistry.toolList`） | **34** | 分 10 个域，由 `ai/tools/` 下 10 个 `ToolProvider` 装配；`manage_memory` 为独立特例分支（不入 toolList） |
| instrumented 测试（androidTest） | **0** | 目录不存在 |
| CI 配置 | **无（有意）** | 仅 `crowdin.yml`，无 `.github/`；#10（2026-09-12）经评估**不建托管 CI**，改为四道 `preBuild` 门禁 + 两道发布闸门（详见 §[P1-4]） |
| 构建期门禁 | **4 道 · `preBuild`** | `checkProtocolSynced` / `checkI18nKeysSynced` / `checkKeyPathEmptyCatch` / `checkNoBareCatch`（空 catch 棘轮预算 47） |
| 发布闸门 | **2 道 · `packageRelease`** | `checkGitClean`（工作区必须干净）+ `dependsOn testDebugUnitTest`（双端共用 `gradle/local-gates.gradle.kts`） |
| release APK | **92.9 MB** | debug 142.6 MB |
| RokidLink APK | 8.7 MB (release) / 12.3 MB (debug) | 内嵌进 phone-app assets 的 `RokidLink.apk` 与 `RokidLink-debug.apk` **逐字节相同**（12,301,311 字节，MD5 `41D20777631CCFADCFEA03DB2FD5B6E3`） |

### 上帝类 Top 10（实测行数 / 函数数）

| # | 文件 | 行数 | `fun` | 职责 |
|---|---|---|---|---|
| 1 | `RokidLink/.../KeyButtonService.kt` | **2282** | 87 | CXR 桥接 + 按键路由 + ASR 桥 + TTS + 悬浮层(歌词/图片) + WiFi 连接 + 自愈重启 + 工具确认 UI |
| 2 | `phone-app/.../adb/AdbFileManagerClient.kt` | **1556** | 32 | 自实现 ADB sync 协议：列目录/上传/下载/删除/改名/建目录/存储信息/exec |
| 3 | `phone-app/.../hid/BluetoothHidManager.kt` | **1490** | 56 | HID 描述符构造 + 键鼠/手柄/摇杆报表 + 扫描绑定 + ROM 兼容重试 |
| 4 | `phone-app/.../settings/DeveloperScreen.kt` | **1279** | 13 | 开发者上架页（单 Composable **610 行**）+ Gitee API 提交 |
| 5 | `phone-app/.../store/LocalModelPage.kt` | **1116** | 20 | 本地模型页（单 Composable **783 行**） |
| 6 | `phone-app/.../app/MainActivity.kt` | **1104** | 60 | 导航 + 投屏 + 权限 + 自更新 + 镜像源 + 保活 |
| 7 | `phone-app/.../glasses/GuideScreen.kt` | **1084** | 11 | 引导页（含 WiFi 配置） |
| 8 | `phone-app/.../filemanager/FileManagerActivity.kt` | **1020** | 44 | 文件管理 Activity |
| 9 | `phone-app/.../glasses/CxrLHiRokidSession.kt` | **887**（非空行；含空行 1003） | 78 | CXR 连接引擎 facade（**已拆分两轮**，见 §6） |
| 10 | `phone-app/.../adb/AdbScreenMirrorClient.kt` | **952** | 21 | ADB 投屏客户端（scrcpy H.264 + screencap 降级） |

其后：`domain/AiConversationService.kt`(881)、`hid/GamepadActivity.kt`(809)、`mirror/PhoneMirrorService.kt`(756)、`ai/AiuiProject.kt`(725)、`feature/FileManagerStateHolder.kt`(698)、`store/ChatSettingsDialog.kt`(689，单 Composable **567 行**)、`ai/LocalOllamaManager.kt`(689)、`ai/ToolRegistry.kt`(678)、`ai/SkillRegistry.kt`(676)。

---

## 3. 分级问题清单

### P0 —— 阻断 / 不可逆风险（5 条）

#### [P0-1] release 内置可远程执行任意 shell 的调试广播接收器（`exported=true`）
- ✅ **已修（2026-09-12）** —— 隔离到 debug 源集，三步动作：
  1. 新建 `phone-app/src/debug/java/com/rokidlab/phone/ai/DebugAiuiReceiver.kt`（源码原样迁移 + KDoc 说明 debug-only）
  2. 新建 `phone-app/src/debug/AndroidManifest.xml` 承载 receiver 声明
  3. 删除 `phone-app/src/main/java/.../ai/DebugAiuiReceiver.kt`，并从 `src/main/AndroidManifest.xml` 移除该 receiver
- **修复验证**：在 `phone-app/build/intermediates/**/AndroidManifest.xml` 中检索 `DEBUG_CMD`，命中文件仅剩 3 个 **debug** 产物（`merged_manifest/debug`、`merged_manifests/debug`、`packaged_manifests/debug`），**release 合并清单零匹配** → release 包既不包含该类，也不导出该 action，任意本机 App 无法再投递。
- **原证据（修复前）**
  - `phone-app/src/main/AndroidManifest.xml:79-85`
    ```xml
    <receiver android:name=".ai.DebugAiuiReceiver" android:exported="true">
        <intent-filter><action android:name="com.rokidlab.phone.DEBUG_CMD" /></intent-filter>
    </receiver>
    ```
  - `phone-app/.../ai/DebugAiuiReceiver.kt:104-115` 分支 `act="shell"` → 取 `cmd` extra → `app.cxrL.getAdbShellClient().executeShellCommand(cmd, 15000)`，在**眼镜端**执行任意命令。
  - 同文件另有两个高危分支：`38-64`（向眼镜直传任意 `.aix`）、`66-101`（在 Termux 内 `apt-get install ollama`）。
  - 关键事实：该 receiver 位于 `src/main`（**修复前项目无 `src/debug` 源集**），因此 **release 包同样包含**，且无 `android:permission` 保护。
- **原影响**：手机上任一 App（自定义 action 广播**无需权限**）即可借 Lab 的 ADB 隧道在眼镜端执行任意命令 → 设备控制权完全外泄。
- **采用的修复方向**：移入 `src/debug/`（比「保留在 main + `android:permission` + `BuildConfig.DEBUG` 守卫」更彻底 —— release 包直接不存在该类与声明，无运行期绕过面）。

#### [P0-2] 三个第三方 Token 明文入库，且被点名后仍未轮换
- **证据**（全仓 grep，排除 build）
  | Token | 位置 | 权限/影响 |
  |---|---|---|
  | Gitee PAT `<GITEE_TOKEN>`（值已从本文档抹除） | **1 处**：`phone-app/.../settings/DeveloperScreen.kt:53`（客户端内）；`docs/ARCHITECTURE_REVIEW.md:37`、`RULES.md:401`、`.traelink/rules.md:15` 三处文档面均已于 2026-09-12 改为 `${GITEE_TOKEN}` 占位 | projects 写权限，可改 Registry 仓库 |
  | 爱发电 `IFDIAN_TOKEN` | `phone-app/.../settings/SettingsScreen.kt:532` | 赞助接口签名 |
  | 酷我 API `KUWO_API_TOKEN` | `phone-app/.../util/AppConfig.kt:9` | 音乐搜索 API |
  - `RULES.md:402` 还给出了 `git remote set-url https://dlover1314:{TOKEN}@…` 的用法（带写权限）。
  - ✅ 文档面 3 处（`ARCHITECTURE_REVIEW.md:37`、`RULES.md:401`、`.traelink/rules.md:15`）**已于 2026-09-12 替换为 `${GITEE_TOKEN}` 占位**；但**源码内 1 处（`DeveloperScreen.kt:53`）仍在，且三个 token 都未轮换** —— 占位不改变"旧值已随 Git 历史与 APK 扩散"这一事实。
- **影响**：三个 token 随源码、文档、APK 全方位扩散；Gitee PAT 具写权限，可被用于篡改 Registry 仓库（进而影响全部 Lab 用户的应用分发）。
- **修复方向**：立即吊销并重建三个 token；全部改为占位 + `local.properties` / 环境变量注入；对已泄露的 commit 历史执行 `git filter-repo` 清洗或确认远端为私有仓库。

#### [P0-3] release 签名配置：机器绝对路径 + 已提交的弱口令回退值
- **证据**
  - `phone-app/build.gradle.kts:38-44`（`RokidLink/build.gradle.kts:20-23` 同款）
    ```kotlin
    storeFile = file("D:\\rokidapp\\release.keystore")
    storePassword = providers.gradleProperty("RELEASE_KEYSTORE_PASSWORD").orElse("rokid123").get()
    keyAlias      = providers.gradleProperty("RELEASE_KEY_ALIAS").orElse("rokidbrew").get()
    keyPassword   = providers.gradleProperty("RELEASE_KEY_PASSWORD").orElse("rokid123").get()
    ```
  - `gradle.properties:5-7` 明文写有同款口令。
  - **本次已实测确证（见 §6 修正）**：`gradle.properties` **虽在 `.gitignore:21-22` 中，但已被 Git 跟踪并推送至远端**：
    ```
    $ git ls-files gradle.properties      → gradle.properties
    $ git show HEAD:gradle.properties     → RELEASE_KEYSTORE_PASSWORD=rokid123 …
    $ git branch -r --contains <该文件末次提交>  → origin/master
    ```
- **影响**：release 签名口令与别名等于公开（任何人可用该 keystore + 口令伪造"官方"更新包）；同时 `orElse("rokid123")` 让任何缺配置的机器**静默产出一个签名看似正确的包**；绝对路径 `D:\rokidapp\release.keystore` 使换机器/CI 构建必然失败。
- **修复方向**：① 从 Git 历史中彻底移除并轮换签名密钥（作废旧 keystore）；② 删除三个 `orElse(...)`，缺失即 `error(...)` 中止构建；③ 路径改 `rootProject.file("release.keystore")`；④ 口令走 CI secret / `local.properties`。

#### [P0-4] 眼镜端 `AiuiLinkActivity` 导出 + 接受任意 `aix_path` + JS 桥直调工具
- **证据**
  - `RokidLink/src/main/AndroidManifest.xml:73-79`：`AiuiLinkActivity` `android:exported="true"`。
  - `RokidLink/.../AiuiLinkActivity.kt:156-163`：从 Intent 读取**任意文件路径** `EXTRA_AIX_PATH`，无白名单校验。
  - `:216` `wv.addJavascriptInterface(bridge, "Android")`；`:122-123` 桥的 `callTool(name, argsJson, cbId)` → `ToolGateway`。
  - `ToolGateway.kt:47` `ALLOWED_DOMAINS = ToolRegistry.DOMAIN_ALL`（**域全开**）；`:92-97` 副作用工具仅在 `ToolRegistry.SIDE_EFFECT_TOOLS` 记账，网关**不做拦截**。
- **影响**：眼镜上任一 App 可用自备 `.aix` 拉起该 Activity，页面内 JS 经桥调用 `call_phone` / `search_contacts` / `read_calendar` 等**真实副作用**工具（可拨号、读联系人/日历）。这是"LLM 生成页面 → 直接执行副作用工具"链路的越权出口。
- **修复方向**：`exported="false"`（改由 `KeyButtonService` 内部启动）；`aix_path` 限定在 App 私有目录白名单内；JS 桥按 `ToolRisk` 分级，副作用工具强制眼镜端二次确认。
- **复核更新（2026-09-12）**：`ToolRisk` 分级已落地且**风险闸门已接入双路径**——AIUI 页面路径 `ai/ToolGateway.kt:147`（`ToolPolicy.check(SOURCE_AIUI_PAGE, …)`）、对话路径 `domain/AiConversationService.kt:403`（`ToolPolicy.check(SOURCE_CONVERSATION, …)`）；眼镜端确认通道 `ai/GlassToolConfirmChannel.kt` 已挂载于 `glasses/CxrLHiRokidSession.kt:589-590`。**但闸门仍为 fail-open**：`ToolPolicy.check` 在「无确认通道」或「确认超时」时实际 `return Decision.Allow`，与该类 KDoc 顶部"降级为拒绝 + 明确提示"的描述**相互矛盾**（`ToolPolicy.kt:11-13` vs `:116-121 / :128-131`）。此外 `call_phone` 已于 2026-09-11 降为 `LOCAL_SIDE_EFFECT`，`ToolRiskMap` 中现无任何真实工具登记为 `EXTERNAL_SIDE_EFFECT`，确认闸门在现网拦不到真实工具。**本项由"未落地"上调为"部分落地"，仍未关闭。**
- ✅ **部分已修（2026-09-12）**：
  - `exported="false"`（`RokidLink/src/main/AndroidManifest.xml:78`）+ `isAllowedAixPath()` 规范路径白名单（`AiuiLinkActivity.kt:161-168`，仅接受 `filesDir/aiui_host/` 直属文件，挡 `../` 穿越）—— 此前已完成。
  - 本次新增**导航锁定**：`AiuiLinkActivity` 的 `WebViewClient` 补 `shouldOverrideUrlLoading`，非 `https://ink.local/` 的顶层导航一律阻断。原因：`addJavascriptInterface` 把 `Android` 桥注入 WebView **所有 frame**，页面一旦被链接/脚本重定向到外部站点，对端 JS 即可直接 `Android.callTool` 触达手机端工具，绕开 `exported=false` 的隔离；子资源不受影响。同时 `allowContentAccess` 由 `true` 收紧为 `false`。
  - **残留面**：外部 `<iframe>` 属子框架加载，`shouldOverrideUrlLoading` 拦不到，其脚本仍可触达桥。彻底关闭需改为「仅向页面 realm 注入桥」而非全局 `addJavascriptInterface`，属阶段性改造，未在本轮处理。

#### [P0-5] release 变体全局放开明文流量，且无 `networkSecurityConfig`
- **证据（修复前）**：`phone-app/build.gradle.kts:53-58`（release 分支 `manifestPlaceholders["cleartextTrafficPermitted"] = "true"`，与 debug 同值）；`AndroidManifest.xml:59` `android:usesCleartextTraffic="${cleartextTrafficPermitted}"`；全仓**无** `res/xml/network_security_config.xml`。
- **影响**：商店拉 `apps.json`、DeepSeek AI 请求（携带 API Key 与用户对话）、用户自定义 `baseUrl` 全部允许降级明文 → 可被中间人无成本劫持（含 API Key 泄露）。
- **说明**：ADB(5555) / TextInput(7656) / AIUI(8848) 确实需要明文，需求真实，但**不应一刀切全局放开**。
- **修复方向**：新增 `network_security_config.xml`，用 `<domain-config cleartextTrafficPermitted="true">` 仅放行 `localhost` 与眼镜 IP 段；release 侧 `usesCleartextTraffic=false`。
- ✅ **已修（2026-09-12）**：
  - 新增 `phone-app/src/main/res/xml/network_security_config.xml`：`base-config cleartextTrafficPermitted="false"`，仅白名单 `localhost` / `127.0.0.1` / `::1`（本地 Ollama + 蓝牙隧道转发端口）、`192.168.1.168`（眼镜 WiFi 直连默认 IP）、`192.168.49.1`（眼镜热点网关）、`ip-api.com`（定位工具免费档仅 http）。
  - 新增 `phone-app/src/debug/res/xml/network_security_config.xml`：debug 变体整体放开（含信任用户 CA，便于抓包），release 不受影响。实测 `packaged_res/{debug,release}/xml/network_security_config.xml` 两份内容正确分流。
  - `phone-app/build.gradle.kts` release 占位值改 `"false"`；`AndroidManifest.xml` 挂 `android:networkSecurityConfig`。release 合并清单实测 `usesCleartextTraffic="false"` + `networkSecurityConfig` 均已生效。
  - **NSC 无法表达 CIDR/网段**，而眼镜 WiFi IP 由眼镜经 `TOPIC_GLASSES_IP` 自报、可能是任意局域网地址 → 同步在 `ai/AiuiProject.kt` 的 `uploadOnce` / `deleteOnce` 增加「明文被拒（`UnknownServiceException` / message 含 CLEARTEXT）→ 回落蓝牙隧道 `127.0.0.1`」兜底，避免 AIUI 安装/删除在该场景直接失败。
  - **遗留行为变化（有意）**：用户自定义 `baseUrl` 若为 http 外网地址、`web_fetch` / `show_image` 传入 http 链接，此后会被系统拒绝 —— 与「禁止全局明文」一致，需在发布说明中告知。

---

### P1 —— 严重工程债（14 条）

**[P1-1] 上帝类规模失控，UI 层（Composable）亦在列**
- 证据：见 §2 Top10；另有 `DeveloperScreen.kt:102` 单 Composable 610 行、`LocalModelPage.kt:93` 783 行、`ChatSettingsDialog.kt:76` 567 行、`ChatScreen.kt:95` 367 行（Python 括号配平实测）。
- 影响：单文件多职责，改动回归面大，无法单测。
- 修复方向：先建状态容器（`CxrLStateRepository`）再拆连接/ASR/AIUI 子模块；UI 长 Composable 按区块抽 `SectionXxx()` 子组件。

**[P1-2] 16 个 ≥150 行的超长方法**
- 证据（实测清单，行号:长度）：`LocalModelPage.kt:93`(783)、`DeveloperScreen.kt:102`(610)、`ChatSettingsDialog.kt:76`(567)、`ChatScreen.kt:95`(367)、`SkillsManagePage.kt:62`(252)、`AdbFileManagerClient.kt:877 uploadFile`(249)、`AgentSectionPage.kt:57`(232)、`AiuiManagePage.kt:66`(226)、`SettingsScreen.kt:52`(201)、`KeyButtonService.kt:868 initCxrBridge`(189)、`AdbFileManagerClient.kt:332 downloadFile`(156)、`ScreenMirrorStateHolder.kt:95`(155)、`GamepadActivity.kt:127`(153)、`AiuiLinkActivity.kt:187 createWebView`(153)、`ScreenMirrorStateHolder.kt:98`(152)、`KbManageDialog.kt:55`(151)。
- 修复方向：`uploadFile`/`downloadFile` 按"封装→校验→传输→收尾"分步；Composable 按区块拆分。

**[P1-3] "协议单一事实源"未真正实现 —— `checkProtocolSynced` 覆盖不足**
- 证据：`phone-app/build.gradle.kts:144-215` 的门禁只做两件事：① 逐字节比对 `AiChannel.kt` / `LinkProtocol.kt`（**仅这 2 个文件**）；② 扫描裸字面量，但 `forbiddenChannels = {"Ai","Sys","Wifi","Jsai","Ai_RenderPayload"}`（L172）**不含 `rokidlab_*` topic**，且正则（L173）要求字面量直接出现在 `sendCustomCmd/caps.write(...)` 实参内，赋值形式一律绕过。
- **实证漏网**（眼镜端 `KeyButtonService.kt` companion 自建一套重复常量，与 `AiChannel` 同值但无人守）：
  `:435 TOPIC="rokidlab_key_config"`、`:442 TTS_TOPIC="tts_play"`、`:446 QUIZ_TOPIC="rokidlab_key_quiz"`、`:448 PHOTO_ASK_TOPIC="rokidlab_photo_ask"`、`:450 AI_ASR_TOPIC`、`:462 AI_ASR_POLL_TOPIC`、`:466 SHOW_MAIN_TOPIC="rokidlab_show_main"`、`:468 AI_CONFIG_TOPIC`、`:476 AIUI_HOST_TOPIC`。
  手机端另有裸串：`feature/RokidLinkController.kt:279`、`glasses/CxrLHiRokidSession.kt:101/106`、`glasses/AiuiFrontendController.kt:51/124/149/397/496/555`（`"Sys_AIUI_Start"`、`"Jsai_AddNativeAgent"`…）、`domain/DeviceControlService.kt:273 caps.write("Wifi_Connect")`。
- **影响**：这比"没有门禁"更危险 —— 它给出**虚假安全感**。改一端 `AiChannel` 常量，眼镜端的重复常量不会触发任何构建失败 → 静默跨端错位（正是该机制想防的问题）。
- 修复方向：`rokidlab_*` / `Sys_*` / `Jsai_*` / `Wifi_*` 全部纳入 forbidden 集；扫描规则升级到"字符串常量赋值"级别（匹配 `= "..."` 而非仅实参）；眼镜端逐步改为复用 `AiChannel` 常量。

**[P1-4] 保障机制只有"协议门禁"一条，其余全靠人工约定** —— ✅ **已修（2026-09-12，阶段二 #10）**
- 证据（修复前）：仅 `checkProtocolSynced` 挂在 `preBuild`；无 lint gate、无 i18n 一致性校验。
- 修复方向（已落地）：把已有正确思路机制化 —— 新增 `checkI18nKeysSynced`（zh↔en key 集合相等）、`checkKeyPathEmptyCatch`（关键链路空 catch 零容忍）、`checkNoBareCatch`（全仓空 catch 棘轮预算 `bareCatchBudget=47`，只降不升），四道门禁全部挂 `preBuild`；release 另加两道闸门 `checkGitClean` + `packageRelease dependsOn testDebugUnitTest`（双端共用 `gradle/local-gates.gradle.kts`）。
- **有意不建托管 CI**（用户 2026-09-12 决策）：四道门禁已在本地每次构建触发，单作者 + 唯一发布路径是本地构建 → 托管 CI 属重复执行，且需复刻 SDK/NDK/16KB 校验环境，收益为负。真正缺失的「出包必跑测试 / 脏工作区不许出包」已由两道 Gradle 闸门闭合。

**[P1-5] 状态层：纯 Compose state + 单例，无状态流、无生命周期约束**
- 证据：`StateFlow` **0** / `collectAsState` **0** / ViewModel **0**，而 `mutableStateOf` **317**；全局 `object` ≈73；`ChatStateHolder.kt:37` 用 `mutableStateListOf` 承载全局聊天状态。
- 影响：跨线程写 `SnapshotStateList` / `mutableStateOf` 是未定义行为；无 `collectAsStateWithLifecycle`，后台改写缺少生命周期约束。
- 修复方向：连接/聊天等热点状态引入 `MutableStateFlow`，Compose 侧用 `collectAsStateWithLifecycle`；`ChatStateHolder` 写入统一 post 到主线程。

**[P1-6] `ChatStateHolder` 主线程同步磁盘 IO + O(n²) 持久化** —— ✅ **已修（2026-09-12，阶段二 #9）**
- 原证据：`ChatStateHolder.kt:95-103` `add()`（注释 L107 明确"必须在主线程"）内直接调 `persist()`（L101）；`persist()`（L52-68）把**整个列表**重新 JSON 序列化后 `writeText`；`appendAiDelta`(L138) / `finalizeLastAi`(L156) 同样要求主线程；`MAX_HISTORY=500`(L19)。
- 原影响：每条消息都在主线程做一次全量 500 条 JSON 写盘 → 长会话下卡顿 + 掉帧。
- **已交付**：抽出纯 JVM 的 `store/ChatHistoryStore.kt`（格式 + 回放 + 迁移，无 Android 依赖），`ChatStateHolder` 只负责"何时写 / 在哪个线程写"：
  - **落盘移出主线程**：单线程 daemon `chat-history-writer`（`Executors.newSingleThreadExecutor`），所有文件 I/O 按提交顺序串行执行 —— 既不阻塞主线程，也不会并发写坏文件；序列化在调用线程完成后再把**字符串**交给后台，后台任务绝不触碰 `SnapshotStateList`。
  - **全量重写 → JSONL 增量追加**：每行一条消息，`add` / `addImage` / `finalizeLastAi` 各只追加一行（后者的"覆盖行"由回放时**后写覆盖先写**收敛），写放大从 O(n²) 降为 O(n)。`clear()` 改为删文件（旧的"清空后写空数组"在崩溃窗口内会复活历史）。
  - **加载也移出主线程**：冷启动读盘 + 旧格式迁移 + 压实都在后台做，只有"塞进 `messages`"这一步 post 回主线程；顺带一次**压实**把重复行收敛为每 id 一行、把旧版"整份 JSON 数组"文件迁移为 JSONL（迁移不丢历史，见 `ChatHistoryStoreTest` 14 例）。
  - **失败不再静默**：落盘/加载失败改走 `LogCollector`（用户可见的"聊天记录丢失"必须进日志面板，而非只写 logcat）。
- 残留：`ChatStateHolder` 仍非 ViewModel、`messages` 仍是 `SnapshotStateList`（属 P1-5 的状态层改造，未在本阶段动）；`appendAiDelta` 期间的增量内容仍不落盘（仅 `finalizeLastAi` 落盘），进程被杀会丢掉正在生成的那一条 —— 与旧实现一致，属已知取舍。

**[P1-7] UI 层直接承载业务逻辑 / IO / 线程（典型 3 例）**
- 证据 ①：`app/MainActivity.kt:221-250` —— Activity 回调里 `Thread{ awaitGlassesIp(2s) → runBlocking{ routeManager.resolve } → runOnUiThread{ 改 phoneMirrorState } }`：裸线程 + `runBlocking` + 手动线程切换 + Activity 持有业务状态。
- 证据 ②：`adb/ui/` 下 `AppMgrDialog.kt` / `KeyButtonDialog.kt` / `ShellDialog.kt` / `SysInfoDialog.kt` / `TimerDialog.kt` 全部命中 `Thread{` / `runBlocking` / `File(` / `getSharedPreferences`。
- 证据 ③：`feature/FileManagerStateHolder.kt`(698) / `ScreenMirrorStateHolder.kt`(含 155/152 行方法) 把连接、IO、UI 兜底绘帧揉在一起。
- 修复方向：UI 只调 StateHolder/Service 方法，线程与 IO 下沉；从 UI 路径清除 `runBlocking`。

**[P1-8] 异常处理粒度：68 处空 catch + `runCatching` 滥用 → 静默失败** —— ✅ **关键链路部分已修（2026-09-12）**

> **已修（2026-09-12）**：4 条关键链路（ASR 补读 / RFCOMM 隧道 / ADB sync / AIUI 工具网关）共 16 处 catch 已落 `LogCollector`（带异常对象）；
> 新增 `LogCollector.w(tag, message, throwable)` 重载（保留 W 级别同时带堆栈，避免链路断开这类高频失败污染「仅错误日志」导出）；
> 新增构建期门禁 `checkKeyPathEmptyCatch`（挂 `preBuild`）扫描 6 个关键链路文件，空 catch 必须带 `// catch-ok: <原因>` 标注否则构建失败（实测 20 处全部已标注）；
> `RULES.md` §12.14 新增「异常吞噬约束」；**#10（2026-09-12）已补全仓机器门禁** `checkNoBareCatch`（棘轮预算：实测 67 处空 catch / 已标注 20 处，未标注 47 处冻结为基线，新增一处即构建失败）。**残留**：存量 47 处未标注空 catch 与 315 处 `runCatching` 未逐一处理（按预算只降不升，随改动顺手收敛）；不建托管 CI（见 §[P1-4]）。以下为修复前记录。

- 证据：空 `catch` **68 处**（`catch (_: Exception) {}` 65 处），如 `AdbFileManagerClient.kt:189/460/1051/1070/1103/1119/1237/1505`、`AdbScreenMirrorClient.kt:904/921/930/940`。部分为关 socket 的合理静默，但**大量未被区分**。
- **反证（说明作者知道正确做法）**：`platform/Capability.kt:4`、`SdkBridge.kt:13`、`RomAdapter.kt:18`、`ShellOps.kt:10` 均注释"取代 `catch(...){}` 静默吞掉，改为显式降级"；`ai/tools/DisplayToolProvider.kt:90` 注释"不再静默吞掉失败"——**新树长对了，老树没拔**。
- 影响：ADB / RFCOMM 失败极易表现为"AI 工具假成功"（项目历史已踩此坑），只能靠用户口述反推。
- 修复方向：为 4 条关键链路（ADB sync / RFCOMM 隧道 / ToolGateway / ASR 补读）的 catch 强制落 `LogCollector` 带 tag；空 catch 加 CR 禁例。

**[P1-9] release 包 92.9 MB，其中约 60 MB 来自 OCR 原生栈，无按需下发**
- 证据（解析 `phone-app/build/outputs/apk/release/RokidLab-v3.5-release.apk`）：总 92.9 MB；分组 `lib/arm64-v8a` 44.5 MB、dex 26.7 MB、`assets/*` 22.4 MB。单文件 Top：`libopencv_java4.so` **23.47 MB**、`libonnxruntime.so` **18.21 MB**、`assets/ch_PP-OCRv4_rec_infer.onnx` **10.86 MB**、`assets/RokidLink.apk` 8.74 MB、`ch_PP-OCRv4_det_infer.onnx` 4.75 MB。依赖声明见 `build.gradle.kts:260-267`。
- 影响：OCR 仅"答题"场景使用，却人人常驻；侧载分发成本高。
- 修复方向：OCR 栈（opencv + onnx + 模型 ≈60 MB）改为 dynamic feature / 首次使用时下载；`RokidLink.apk` 同样可改按需拉取。
- **复核更新（2026-09-12）**：`phone-app/src/main/assets/RokidLink.apk` 与 `RokidLink/build/outputs/apk/debug/RokidLink-debug.apk` **逐字节相同**——均为 **12,301,311 字节**、MD5 `41D20777631CCFADCFEA03DB2FD5B6E3`。即当前内嵌进 release 包的就是 debug 版眼镜端，**问题依然成立**（且比"体积大"更严重：内置的是未经 release 构建/混淆的眼镜端）。

**[P1-10] 依赖可复现性：时间戳 SNAPSHOT + 多仓混用**
- 证据：`phone-app/build.gradle.kts:239-244` 显式锁定 `com.rokid.cxr:cxr-service-bridge:1.0-20260715.121510-107`，注释自陈"Nexus 清理该 SNAPSHOT 会构建失败"；且同 artifact 的 release `1.0` 与 SNAPSHOT 内容不等（注释 L240-243）。`settings.gradle.kts:18-21` 混用 `maven.rokid.com` + `jitpack.io`(dadb) + `google`/`mavenCentral`。
- 修复方向：将 bridge 私有镜像到自控仓库或 `libs/` 固化；锁定 release 版本并做真机回归。

**[P1-11] 测试盲区：覆盖了"最好测的"，回避了"最易坏的"** —— ✅ **第一批回归单测已补（2026-09-12）**
- 原证据：`phone-app/src/test` 8 个类全部集中在 `ai/`（AgentSessionHistory / Calculator / GoldenAgentEval 23 例 / SkillFetcher / SkillMarkdown / SseStreamAccumulator / TruncateToolOutput）+ `glasses/AiChannelTest`；`RokidLink/src/test/AiChannelProtocolTest`。**androidTest 0、CI 0**。§2 中所有高风险文件**无一有测试**。
- **已交付（2026-09-12）**：按原「最该补的 5 个」全部落地，测试类 9 → **13**、`@Test` 102 → **141**（phone-app 136 + RokidLink 5），全部首次通过（EXIT=0）：
  - ① `adb/AdbFileManagerSyncTest.kt`（**6 例**）—— `AdbFileManagerClient` sync 帧编解码 + `drainStalePackets` 陈旧 CLSE 排空（历史「陈旧 CLSE 被误读成本次响应」）+ `downloadFile` 的 DATA/DONE/FAIL 分支 + `parseDateTime` 两格式与非法输入。
  - ② `hid/HidReportTest.kt`（**12 例**）—— `BtHidCompat.normalize` 截断/补零/未声明返 null、`declaredLength` 全矩阵、`modeOrder` 默认与人工强制、**描述符声明的 Report ID 与 `declaredReportIds` 交叉校验**（5 种描述符，扫描 `0x85,<id>` 字节）。
  - ③ `glasses/AiChannelTest.kt`（17 → **21 例**）—— 补 `glasses_ip` / `show_image` / `open_app` 三类 v1 载荷 roundtrip + 非法载荷整体拒绝（未来版本/缺字段/空串/空白/null/v0 形态），并新增「跨端 topic 与 cmd 常量稳定（改名即断双端）」。
  - ④ `ai/ToolRiskMapTest.kt`（**8 例**）—— 把 `LabApplication.kt:134-141` 的运行时自检提为单测（`ToolRegistry.toolList ≥ 30` 且 `unregisteredTools()` 必须为空，对应 `RULES.md` §12.10）+ `ToolPolicy.check()` 的 fail-open 语义（无通道放行 / 显式取消拒绝 / 超时降级 / 确认放行 / 只读不触发闸门 / AIUI 页面 30 次上限）。
  - ⑤ `adb/AdbSyncProtocolTest.kt`（**8 例**）—— `AdbShellClient.pullFile` 的 FAIL 分支回归锁（远端 `FAIL` 必须返 false 且清掉半成品文件，锁死历史 A4 事故「远端 FAIL 被当成功 → 产出 0 字节文件」）；含 OPEN 被 CLSE 拒、RECV 无 OKAY、读流异常等边界。
  - `RokidLink/AiChannelProtocolTest.kt`（4 → **5 例**）—— 眼镜端只需能解析手机端下发的 v1 三类通道（边界矩阵留在 phone-app 侧避免重复维护）。
- **测试基础设施补强**：新增 `testOptions { unitTests.isReturnDefaultValues = true }`（否则 `android.util.Log` 一碰就抛 `RuntimeException("Stub!")`）；引入 `org.json:json:20231013`（真实 JSON 实现替代 android.jar 桩）；ADB 客户端新增 `internal fun attachStreamsForTest(input, output)`，使 sync 协议可在 JVM 用脚本化对端（`AdbTestPeer.kt`）驱动。
- **顺带纠正一处失实注释**：`BluetoothHidManager.buildQtiCompatibleDescriptor` javadoc 原声称「总长度 ≤ 64 字节（QTI 的 HID_DEV_MTU_SIZE）」，实测为**无 Mouse 67 字节 / 含 Mouse 121 字节**（脚本统计，`HidReportTest` 已锁定精确值）。真机容错实际来自「注册被拒 → 回退无 Mouse 版」重试链而非长度硬约束，注释已按实测改写。
- **登记待评估（非契约，勿静默漂移）**：`AdbShellClient.pullFile` 在数据循环中收到 CLSE 时返回 **true（0 字节）**，而 `AdbFileManagerClient.downloadFile` 同场景返回 **false** —— 语义不一致，已在 `AdbSyncProtocolTest` 注释中钉住行为并指向本条待评估。
- **第二批（#9，2026-09-12）**：`store/ChatHistoryStoreTest.kt`（**14 例**）—— JSONL 单条往返 / 同 id 后写覆盖先写且位置不变 / 无 id 条目不去重 / 非法行与空行跳过 / 崩溃截断半行不影响已落盘历史 / 上限裁剪 / **旧版 JSON 数组格式识别与迁移不丢历史** / 迁移后可变 JSONL 追加 / `rewrite(空)` 删文件（clear 后重启不复活）/ `readHistory` 缺文件返空 / 自动建父目录。
- **残留**：`KeyButtonService` / `ChatStateHolder`（本体，本次只测了抽出的 `ChatHistoryStore`）/ `CxrLHiRokidSession` 三个最高风险文件仍无测试；`androidTest` 仍为 0；不建托管 CI（阶段二 #10 已改为本地闸门，见 §[P1-4]）。

**[P1-12] 文档与代码漂移**
- 证据：`RULES.md` 第一节包结构只列 `adb/app/design/filemanager/glasses/hid/mirror/model/network/settings/store/util` —— **缺 `ai/ connection/ domain/ feature/ keepalive/ music/ platform/`（含体量最大的 `ai/`）**；`docs/ARCHITECTURE_REVIEW.md:262-263` 记的 README 行数/安装文件名已过时；`RULES.md:401-402` 仍留 token 与带 token 的 git 命令。
- 影响：新人按文档找代码会扑空；文档本身成为泄密载体。
- 修复方向：包结构树改由 CI 生成；文档内禁止出现"会变的数字"（行数/文件名）与任何密钥。

**[P1-13] 死代码 / 只写不读字段**
- ✅ **已修（2026-09-12）**
- 证据 ①：`RokidLink/.../KeyButtonService.kt` `connectToWifiApi29` 及 4 个 `enableWifiVia*`（`enableWifiViaCXRBridge` / `ViaShellCommand` / `ViaReflection` / `ViaSettingsApi`）——全仓 grep **无任何外部调用点**，约 **120 行死代码**。→ 已删除，`connectToWifi()` 仅保留 `connectToWifiLegacy()` 单一活路径，并清理随之无用的 `android.provider.Settings`、`android.net.wifi.WifiNetworkSpecifier` import。
- 证据 ②：`phone-app/build.gradle.kts` 注入 `BuildConfig.ROKIDBREW_REGISTRY_URL`，但**全仓 0 引用**（`grep BuildConfig.` 仅命中 `VERSION_NAME/CODE`）；真实更新地址单源在 `model/Models.kt:156 SELF_UPDATE_URL` —— "可配置 registry"机制形同虚设。→ 已删除 4 个顶层 val（`defaultRegistryUrl`/`registryUrl`/`debugRegistryUrl`/`releaseRegistryUrl`）、`asBuildConfigString()` 扩展与两个 buildType 内的 `buildConfigField`，URL 收敛到 `SELF_UPDATE_URL` 单源。
- 附带清理（同轮死代码扫描）：删除零引用文件 `settings/SystemLogPanel.kt`（178 行）、`store/StoreTargetTags.kt`（74 行）；删除零引用声明 `store/StoreInstallState.kt` 的 `CompactProgressLine`/`appProgress`/`primaryInstallTarget`/`formatBytes`/`rememberInstallState`、`store/HomeWidgets.kt` 的 `ActionButton`/`MiniButton`/`BrutalTextField`、`RokidLink/.../MainActivity.kt` 的 `openWifiSettings`（连带 `REQUEST_WIFI`/`onActivityResult`/`toast_enable_wifi` 双语串）。
- 保留判定：`BrewErrorCard`/`BrewIconButton`/`BrewLoadingCard`/`BrewStatusDot`/`BrewStatusPill`/`CategoryChip`/`CategoryIcon`/`EmptyState`/`SearchBar`/`ConnectionPanel`/`IpAddressInputCard`/`RokidLinkStatusCard` 虽当前无调用点，但在 `UI-DESIGN.md` / `RULES.md` 中被文档化为公开设计系统组件，按「预留 API」保留。
- ✅ **已修（2026-09-12）**（非死代码，属 P0-1 安全项）：`DebugAiuiReceiver` 已整体移入 `src/debug` 源集（类 + manifest 声明均移出 `src/main`），release 包不再包含，`act="shell"` 任意命令分支不再可达。详见上文 §[P0-1]。
- 验证：`:phone-app:compileDebugKotlin :RokidLink:compileDebugKotlin :phone-app:testDebugUnitTest --offline` → EXIT=0。


**[P1-14] i18n 断链仍在（违反自家 `RULES.md` 三.1）**
- ✅ **已修（2026-09-12）**：5 条英文串补齐（`values/strings.xml` 1100 条 = `values-en/strings.xml` 1100 条，反向亦无多余 key）；新增 `checkI18nKeysSynced` 任务挂 `preBuild`，校验 phone-app 与 RokidLink 两模块 zh↔en `<string name>` 集合相等，不一致直接构建失败（详见 `docs/纠错方案.md` §五 同日条目）。
- 遗留：**858 处** .kt 内含中文字面量，例如 `ai/ToolRegistry.kt:408-443 statusText()` 内 30 条硬编码中文，而工具描述走 `R.string` —— 同一文件内标准不统一。**此项未在本次处理**（`statusText` 资源化属独立工作量，需逐条抽取资源 + 翻译）。
- 原证据（修复前）：`values/strings.xml` 1096 条 vs `values-en/strings.xml` 1091 条，缺失 5 条：`guide_ready_title` / `guide_reinstall_link_btn` / `guide_skip_btn` / `unknown_author` / `wifi_config_success`（**逐条实测确认 zh=1 / en=0**）。

---

### P2 —— 改进项（按主题归并）

- **P2-a 全局可变状态盘点**（风险排序）：`store/ChatStateHolder.kt`（全局可变快照列表 + `@Volatile appContext`）、`ai/LocalOllamaManager.kt`（17×`runCatching` 管进程/端口）、`ai/MusicPlayerController.kt`（`play`/`stop` 竞态）、`platform/AvrcpLyricBridge.kt`（13×`runCatching`）、`RokidLink/.../KeyButtonService.kt:408 officialAiSessionActive`（companion `@Volatile var`）、`:509 downTimeMs`、`glasses/GlassToolConfirmChannel.kt:78 appSessionProvider`（companion `var`）—— 跨线程读写靠"约定"而非类型系统。
- **P2-b 可观测性**：1281 处 `Log.*`；tag 多数有常量但仍有裸串（如 `MainActivity.kt:243 Log.i("MainActivity", …)`）；失败路径无结构化字段，`LogCollector` 未与 catch 打通。
- **P2-c 硬编码收敛度不均**：好消息 —— 超时/重试/分辨率等已集中在 `util/AppConfig.kt`（96 行常量）；坏消息 —— 第三方 URL 分散硬编码（`api.deepseek.com` ×7、`gitee.com` ×12、`api.yunmge.com`、`ink.local`），设备型号/竞品包名散落（`com.rokid.sprite.*`、`mark.via.gp`）。
- **P2-d 构建配置小项**：`gradle.properties:3 android.suppressUnsupportedCompileSdk=35` 与 `compileSdk=34` 不一致（抑制了 35 的告警却用 34）；`-Xmx2048m`(L4) 对 188 个 Kotlin 文件 + Compose + R8 偏紧（debug APK 已 142.6 MB）；`jvmTarget=11` 而工程 JDK 17（`build.gradle.kts:67-72`）。
- **P2-e 注释掉的代码 / TODO**：注释代码块 ≈11 处；TODO/FIXME 16 处（Top：`adb/ui/SysInfoDialog.kt` 4、`hid/GamepadActivity.kt` 3）。
- **P2-f 权限面偏大**：`phone-app/AndroidManifest.xml` 含 `QUERY_ALL_PACKAGES`、`REQUEST_INSTALL_PACKAGES`、`SYSTEM_ALERT_WINDOW`、`READ_CONTACTS`、`CALL_PHONE`、`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`；`RokidLink` 含 `SYSTEM_ALERT_WINDOW`、`BODY_SENSORS`。多数有刚需，但 `QUERY_ALL_PACKAGES` / `REQUEST_INSTALL_PACKAGES` 会触发应用市场严格审查（Google Play 需专项豁免，国内商店需说明）。
- **P2-g 眼镜端 exported 面**：`RokidLink/AndroidManifest.xml` 中 `MainActivity`(37)、`PhoneMirrorActivity`(47)、`ScreenMirrorIntentActivity`(55)、`AiuiLinkActivity`(75) 均 `exported=true`，`KeyButtonService`(82) `exported=true` 且无权限 —— 建议除启动器外全部收紧。

---

## 4. 优化方案（三阶段）

> 排期原则：**先护栏（安全 + 可观测 + 测试 + CI），后重构**。护栏先行才能让后续拆类有回归保护；安全项因不可逆，全部置于最前。

### 阶段一：立即（0–3 天 · 改动小 · 回归风险低）

| # | 动作 | 改动面 | 回归风险 | 对应问题 |
|---|---|---|---|---|
| 1 | 吊销并轮换 Gitee PAT / 爱发电 / 酷我三个 token；4 处引用全改占位或注入；清洗或确认远端私有 | 小 | 低（需回归"开发者上架 / 赞助列表 / 音乐搜索"） | P0-2 |
| 2 | ~~`DebugAiuiReceiver` 移入 `src/debug` 或加 `permission` + `BuildConfig.DEBUG` 守卫~~ ✅ 已完成（2026-09-12，采用 `src/debug` 方案） | 小 | 低（仅影响调试链路） | P0-1 |
| 3 | 从 Git 历史移除 `gradle.properties` 并轮换签名密钥；删除三个 `orElse` 弱口令、keystore 改相对路径（两个 module） | 小 | 中（需确认 keystore 位置与口令来源） | P0-3 |
| 4 | ~~补 5 条英文串；新增"zh/en key 相等"Gradle 任务挂 `preBuild`~~ ✅ 已完成（2026-09-12，`checkI18nKeysSynced`，phone-app 1100 / RokidLink 22 条 key 双语一致） | 小 | 低 | P1-14 / P1-4 |
| 5 | ~~删除 `BuildConfig.ROKIDBREW_REGISTRY_URL` 或接线，URL 单源化~~ ✅ 已完成（2026-09-12） | 小 | 低 | P1-13 |

**排序理由**：这 5 项都是"一两行或一处配置"即可消除最高危暴露、或永久免疫某一类问题的动作，投入产出比最高，且完全不触碰核心链路。

### 阶段二：1–2 周（改动中等 · 需回归）

| # | 动作 | 改动面 | 回归风险 | 对应问题 |
|---|---|---|---|---|
| 6 | ~~`networkSecurityConfig` 白名单替代 release 全局明文~~ ✅ 已完成（2026-09-12）；`AiuiLinkActivity` 收 `exported=false` + `aix_path` 目录白名单 + 桥按 `ToolRisk` 分级确认 ✅ 此前已完成 + ~~本次补导航锁定~~ ✅ 已完成（2026-09-12） | 中 | 中（投屏 / AIUI / 本地 Ollama 明文链路需逐条回归） | P0-4 / P0-5 |
| 7 | ~~为 4 条关键链路的 catch 落 `LogCollector`；空 catch 加 CR 禁例~~ ✅ 已完成（2026-09-12） | 中 | 低 | P1-8 |
| 8 | ~~补第一批回归单测（ADB sync / HID 报表 / `pullFile` FAIL / `AiChannel` 版本矩阵 / `ToolRiskMap`）~~ ✅ 已完成（2026-09-12，新增 4 个测试类 + 扩 2 个，测试类 9→13、`@Test` 102→141，双端 EXIT=0） | 中 | 低（纯增测试） | P1-11 |
| 9 | ~~`ChatStateHolder` 持久化移出主线程 + 改 JSONL 增量；写入统一主线程 post~~ ✅ 已完成（2026-09-12，抽出 `ChatHistoryStore`（纯 JVM）+ 单线程 daemon 落盘器，加载/迁移/压实全部后台，新增 `ChatHistoryStoreTest` 14 例，双端 EXIT=0） | 中 | 中（聊天历史兼容需迁移） | P1-6 |
| 10 | ~~建最简 CI（unit test + strings 校验 + `assembleDebug`）~~ ✅ **已完成（2026-09-12，方案按实际收益降级）**：经评估**不建托管 CI**（四道门禁已挂 `preBuild`、本地构建即触发，单作者场景下托管 CI 属重复执行且需复刻 SDK/NDK 环境），改为**本地闸门**：新增 `checkNoBareCatch`（全仓空 catch 棘轮预算，挂 `preBuild`）+ `gradle/local-gates.gradle.kts`（`checkGitClean` 挂 `packageRelease`、`packageRelease` 依赖 `testDebugUnitTest`，双端各自 apply） | 小 | 低 | P1-4 |

**排序理由**：先补护栏再动重构。安全项（6）必须紧随阶段一完成；可观测性（7）与测试（8）是阶段三拆类的**前置条件**；状态与 IO 线程化（9）收益直接可见（卡顿/竞态）。

### 阶段三：长期（数周 · 大改造）

| # | 动作 | 改动面 | 回归风险 | 对应问题 |
|---|---|---|---|---|
| 11 | 抽 `CxrLStateRepository`(StateFlow) → 拆 `KeyButtonService` / `AiConversationService` / `MainActivity`；UI 长 Composable 分区块 | 大 | 高（连接/按键/AI 全链路，需真机矩阵） | P1-1 / P1-2 / P1-7 |
| 12 | OCR 栈（≈60 MB）改 dynamic feature / 按需下载；`RokidLink.apk` 按需下发 | 大 | 中 | P1-9 |
| 13 | 协议单一源治理：眼镜端复用 `AiChannel` 常量；扩展 `checkProtocolSynced` 到 `rokidlab_*` / `Sys_*` / `Jsai_*` | 中 | 中（跨端，需双端同发） | P1-3 |
| 14 | 升级 compileSdk/targetSdk 35+、jvmTarget 17、提升 `-Xmx`；`object` 单例热点迁移到可注入容器 | 大 | 中 | P2-d / P1-5 |

**排序理由**：这些改动面大、真机回归成本高，且强依赖阶段二的测试与 CI 提供安全网。在护栏就位前动手拆 `KeyButtonService` 属于高风险操作。

### 阶段二 / 三整改完成度对照表（2026-09-12 复核）

本报告 §4 阶段二 / 三的部分建议**已在当前工作区实现**（源码为准，注意多数文件尚未提交，见 §9）：

| 建议项 | 状态 | 落地证据 / 局限 |
|---|---|---|
| `ChannelArbiter`（通道仲裁，替代裸计数 `reserveTunnel/releaseTunnel`） | **已实施** | `connection/ChannelArbiter.kt`；由 `connection/ConnectionRouteManager.kt:88` 持有（`val channelArbiter = ChannelArbiter()`），并纳入 `app/AppContainer.kt` 注入。**局限已修（2026-09-12）**：原 `NORMAL` 档位零接入（`acquire` 仅 `MirrorCoordinator`、`shouldYield` 仅 ASR 兜底轮询），现 `CxrLHiRokidSession.getAdbShellClient()` 已加 `shouldYield(NORMAL)` 守卫，ADB 工具页 / AI 工具 / AIUI 工具 / 定时任务在长连接占用时确定性让路 |
| `ToolRegistry.execute` 巨型 `when` 按域拆分 | **已实施** | 拆出 `ai/tools/` 下 **10 个** `ToolProvider`（Info / Knowledge / Glasses / Timer / Media / Display / Web / Files / Aiui / Phone）+ `ToolProvider.kt` 接口 + `ToolSchemas.kt` |
| `AppContainer` 手动 DI（无 Hilt） | **已实施** | `app/AppContainer.kt` 集中装配 L1 通道仲裁等依赖；未引入 Hilt/ViewModel（`StateFlow`/ViewModel 仍为 0） |
| `LinkProtocol` v2 + `checkProtocolSynced` 门禁 | **已实施（有覆盖缺口）** | 双端各持 `LinkProtocol.kt`，门禁 `phone-app/build.gradle.kts:144` 逐字节比对 `AiChannel.kt` + `LinkProtocol.kt`；**但该任务仅挂 phone-app 的 `preBuild`（L213-215），RokidLink 独立构建不触发校验**，眼镜端单独发版时协议仍可能漂移 |
| `GlassesHandshake` 三态握手（未知/支持/旧版降级） | **已实施** | `glasses/GlassesHandshake.kt`（`onHello` / `markLegacy` / `supports` / `reset` / `describe`），被 `CxrLHiRokidSession`、`GlassToolConfirmChannel`、`DisplayToolProvider`、`MediaToolProvider` 消费 |
| 关键链路 catch 落 `LogCollector` + 空 catch 门禁 | **已实施（2026-09-12）** | 新增 `LogCollector.w(tag, message, throwable)` 重载；4 条链路（ASR 补读 / RFCOMM 隧道 / ADB sync / AIUI 工具网关）16 处 catch 落面板并带异常对象；`checkKeyPathEmptyCatch`（挂 `preBuild`）扫描 6 个关键链路文件，空 catch 须带 `// catch-ok:` 标注（实测 20 处全部已标注）。**局限已缓解**：全仓空 catch 由 `checkNoBareCatch` 以棘轮预算兜底（#10），存量 47 处只降不升 |
| 构建保障机制（门禁 + 发布闸门）（#10） | **已实施（2026-09-12）** | 四道 `preBuild` 门禁：`checkProtocolSynced` / `checkI18nKeysSynced` / `checkKeyPathEmptyCatch` / `checkNoBareCatch`；新增 `gradle/local-gates.gradle.kts`（双端各自 `apply`）提供两道发布闸门：`checkGitClean`（`git status --porcelain` 非空即失败）与 `packageRelease dependsOn testDebugUnitTest`。**局限**：不建托管 CI（评估结论：本地已等价覆盖，托管 CI 收益为负）；`checkProtocolSynced` 仍只挂 phone-app 侧 |
| 聊天历史落盘移出主线程 + JSONL 增量写（P1-6） | **已实施（2026-09-12）** | 新增 `store/ChatHistoryStore.kt`（纯 JVM：JSONL 序列化 / 回放 / 旧格式迁移 / 上限裁剪）；`ChatStateHolder` 改为单线程 daemon `chat-history-writer` 串行落盘，`add`/`addImage`/`finalizeLastAi` 各只追加一行，`clear()` 删文件，冷启动在后台完成读取 + 迁移 + 压实后 post 回主线程；失败落 `LogCollector`。新增 `ChatHistoryStoreTest` 14 例（含「旧格式迁移不丢历史」「崩溃截断半行不影响已落盘历史」）；测试类 13→14、`@Test` 141→155，双端 EXIT=0。**局限**：`ChatStateHolder` 仍非 ViewModel（P1-5 未动）；`appendAiDelta` 期间的增量不落盘 |
| 第一批回归单测（ADB sync / HID 报表 / `pullFile` FAIL / `AiChannel` v0·v1 矩阵 / `ToolRiskMap`） | **已实施（2026-09-12）** | 新增 `AdbSyncProtocolTest`(8) / `AdbFileManagerSyncTest`(6) / `HidReportTest`(12) / `ToolRiskMapTest`(8)，扩 `AiChannelTest` 17→21、`RokidLink/AiChannelProtocolTest` 4→5；测试类 9→13、`@Test` 102→141，双端 `compileDebugKotlin + compileReleaseKotlin + testDebugUnitTest --offline` EXIT=0。**局限**：`KeyButtonService` / `ChatStateHolder`（本体）/ `CxrLHiRokidSession` 仍无测试，`androidTest` 为 0（阶段三 #11 的对象） |

> 说明：以上为"建议已落地"的正向复核；§3 的 P0/P1 安全与测试类问题中，**明文流量（P0-5）已于 2026-09-12 修复**，**测试盲区（P1-11）已补两批**（#8：ADB sync / HID 报表 / `pullFile` FAIL / `AiChannel` 矩阵 / `ToolRiskMap`；#9：`ChatHistoryStore` 落盘格式与迁移），**聊天历史主线程 IO（P1-6）已修**，**token 明文（P0-2）与签名口令入库（P0-3）仍在** —— 二者需仓库 owner 在 Gitee / 本地执行不可逆操作，助手无法代办，不在本表状态之列。

---

## 5. 不确定项（未能验证，需真机或作者确认）

1. ~~**`DebugAiuiReceiver` 的实际可达性**：静态看 `exported=true` 且自定义 action 无权限保护即可触达，但"零权限应用能否发送自定义 action 广播""厂商 ROM 是否额外限制"**未真机验证**。~~ **已随 P0-1 修复消解（2026-09-12）**：release 包已完全不含该类与声明（`DEBUG_CMD` 在 release 合并清单零匹配），"能否触达"不再构成暴露面，无需再验证。
2. **`AiuiLinkActivity` JS 桥的完整能力边界**：已确认暴露 `callTool`，但桥是否还有其它危险方法、`.aix` 内容能否越权读任意文件，未逐行审完。
3. **SNAPSHOT 依赖现网可解析性**：`cxr-service-bridge:1.0-20260715.121510-107` 当前能否从 `maven.rokid.com` 拉到，未联网验证。
4. ~~**运行时行为类结论**：`ChatStateHolder` 卡顿的实际量级~~ **其中"`ChatStateHolder` 持久化阻塞主线程"一项已随 P1-6 修复消解（2026-09-12）**——落盘与加载均已移出主线程并改 JSONL 增量写，剩余待真机确认的只有 `OCR 首启耗时`、`投屏 screencap 降级在真机上的效果`。
5. **`FileManagerActivity.kt:174` 附近约 849 行的结构化程度**：括号配平把它算作一个块，实际可能是"多个 Composable 连写"而非单个函数，未逐行核对，故**未纳入 P1-2 清单**。
6. **`gradle.properties` 的处置前提**：本次已确证其被 Git 跟踪并推送（见 P0-3），但**远端仓库是否为公开仓库**未验证 —— 若为公开，则泄露程度等同于全网公开；若为私有，风险降一级但仍需轮换。

---

## 6. 交叉核验记录（team-lead 独立复核，修正架构师原始结论）

本报告的每一项 P0/P1 结论均由评估方独立验证；以下 4 处为**复核后对原始评估的修正或补强**：

| # | 原始结论 | 复核结果 | 处置 |
|---|---|---|---|
| 1 | 「`gradle.properties` 已被 `.gitignore` 忽略，故"明文提交"不成立」 | **不成立**。`.gitignore` 不回溯已跟踪文件；实测 `git ls-files gradle.properties` 有输出、`git show HEAD:gradle.properties` 可读到口令、`git branch -r --contains` 显示已并入 `origin/master` | **升级 P0-3 定级**：从"默认口令回退值"升级为"**密钥已提交并推送**"，并新增"从历史移除 + 轮换密钥"动作 |
| 2 | 「`CxrLHiRokidSession.kt` 行数自 2861 骤降至 887（非空行），原因不明」 | **已查明**：`6f3f195`(v3.2) = 2275 行 → `b19543b`(2026-09-08) 拆为 2788 行并抽出 3 个 coordinator → 现为 **887 行（非空行；含空行 1003）**。`glasses/` 目录已存在 `AsrBridgeCoordinator.kt` / `AiuiFrontendController.kt` / `PhotoQuizFlow.kt` / `GlassesHandshake.kt` / `FullCXRLinkCallback.kt` 等拆分产物 | 确认**已进行过两轮有效拆分**，该文件在 P1-1 中的严重度应下调；`KeyButtonService.kt` 才是真正未治理的头号上帝类 |
| 3 | 5 条英文串缺失（沿用 09-10 自评） | **逐条实测确认**：`guide_ready_title` / `guide_reinstall_link_btn` / `guide_skip_btn` / `unknown_author` / `wifi_config_success` 全部 zh=1 / en=0 | ✅ **2026-09-12 已修**：5 条英文串补齐（phone-app 1100 / RokidLink 22 条 key 双语一致），并新增 `checkI18nKeysSynced` 挂 `preBuild` 永久防漂移 |
| 4 | APK 体积 97.5 MB / 149.5 MB | 与实测一致（**口径差异**）：实际为 92.94 MiB = 97.4 MB（十进制）；debug 142.6 MiB = 149.5 MB | 统一按十进制 MB 表述 |

**额外确认（本次实测）**：项目**修复前不存在 `phone-app/src/debug` 源集**，故 `DebugAiuiReceiver` 无"已被 debug 源集隔离"的可能，P0-1 成立。→ **2026-09-12 已新建 `src/debug`（`AndroidManifest.xml` + `java/com/rokidlab/phone/ai/DebugAiuiReceiver.kt`）并完成隔离，P0-1 关闭。**

---

## 7. 既有成果的信任度核对

对 2026-09-10 自评中声称"已修复"的项目做了抽查：

| 自评声称 | 实测结果 | 结论 |
|---|---|---|
| 投屏降级改用 `screencap -p` | `AdbScreenMirrorClient.kt:620` 确为 `screencap -p` | ✅ 属实 |
| 引入 `SIDE_EFFECT_TOOLS` 记账 | `ai/ToolRegistry.kt:92-97` 存在 | ✅ 属实 |
| 工具空 `arguments` 兜底为 `{}` | `ai/ToolRegistry.kt:471-480` 存在 | ✅ 属实 |
| Token 待轮换 | 三个 token **原样仍在**（含 4 处 Gitee PAT） | ❌ **仍未跟进**（需先在 Gitee 后台撤销轮换，非代码侧可代办） |
| 补 5 条英文串 | ~~zh=1 / en=0，仍缺~~ | ✅ **2026-09-12 已跟进**：5 条补齐 + `checkI18nKeysSynced` preBuild 门禁 |
| WiFi 死代码待清理 | ~~`KeyButtonService.kt:2152-2278` 仍在~~ | ✅ **2026-09-12 已跟进**：`connectToWifiApi29` + 4 个 `enableWifiVia*` 已删除（见 §[P1-13]） |

**结论**：作者的"修复进度"自述基本可信。**2026-09-12 集中整改后，上表 3 项「零跟进」中已有 2 项落地**（英文串 + WiFi 死代码，且均补了构建期门禁），仅剩 Token 轮换因需外部后台操作未完成。本报告关于「缺少强制执行机制」的判断已回应：`preBuild` 现有**四道**门禁（协议同源 / i18n key / 关键链路空 catch / 全仓空 catch 预算），release 另有两道发布闸门（#10）。**有意不建托管 CI**（单作者场景收益为负，见 §[P1-4]），门禁全部落在 Gradle 任务上、本地构建即生效。


---

## 8. 附：值得保留并推广的既有实践

建议在新人文档中显式列出，避免后续重构时被误删：

1. **`checkProtocolSynced`**（`build.gradle.kts:144-215`）—— 用构建失败强制双端协议同源，并禁止裸协议字面量。**思路正确，仅需扩大覆盖面**（P1-3）。
2. **`platform/` 显式降级层**（`Capability.kt` / `SdkBridge.kt` / `RomAdapter.kt` / `ShellOps.kt`）—— 用能力探测替代静默反射，是处理 ROM 碎片化的正确姿势。
3. **`ToolRegistry` 域装配 + `schemaCache` + `ToolRiskMap` 启动自检**（`LabApplication.kt:134-141`）—— 工具体系有明确的风险分级意识。
4. **16KB 页面对齐处理**（`useLegacyPackaging=false` + 覆盖 4KB 对齐的 opencv/onnxruntime）—— 前瞻性地兼容了 Android 16 强制要求。
5. **R8 keep 规则针对性配置**（`RokidLink/proguard-rules.pro` keep `com.rokid.cxr.**`）—— 混淆开启但关键反射路径有保护。
6. **Git 提交信息规范**（`vX.Y: 内容摘要` 前缀）—— 便于回溯版本边界。

---

## 9. 当前工作区未跟踪源码清单（2026-09-12，HEAD=`cd9c6f0`）

工作区状态（`git status --porcelain`）：**源码修改 84**、**删除 7**、**未跟踪 36**；`git diff --stat` = **91 files changed, +6196/−7379**。（首次记录时为"M 72 / D 4 / ?? 26"；此后追加了 #6 / #7 / #8 / #9 / #10 五阶段改动、新测试类与 `gradle/local-gates.gradle.kts`。）

> ⚠️ **这 36 个未跟踪条目包含几乎全部"六层重构"新源码**：`ai/ToolPolicy.kt`、`ai/ToolRisk.kt`、`ai/GlassToolConfirmChannel.kt`、`ai/tools/`（12 个）、`app/AppContainer.kt`、`connection/ChannelArbiter.kt`、`glasses/GlassesHandshake.kt`、双端 `LinkProtocol.kt`、`domain/`（9 个）、`feature/`（7 个）、`platform/`（9 个）、`music/`（1 个）等。其中多个文件已被**已跟踪**的旧文件引用（如 `ConnectionRouteManager.kt` 引用 `ChannelArbiter`、`CxrLHiRokidSession.kt` 引用 `ToolPolicy`/`LinkProtocol`）。因此一次 **clean checkout（`git clean -xdf` 或新克隆）会直接编译失败**——被引用的新源码不在版本控制内。

| 类别 | 未跟踪条目 | 说明 |
|---|---|---|
| 重构源码（phone-app） | `ai/ToolPolicy.kt`、`ai/ToolRisk.kt`、`ai/GlassToolConfirmChannel.kt`、`ai/LocationTools.kt`、`ai/tools/`（12 个 .kt）、`app/AppContainer.kt`、`connection/ChannelArbiter.kt`、`glasses/GlassesHandshake.kt`、`glasses/LinkProtocol.kt`、`domain/`（9 个 .kt）、`feature/`（7 个 .kt）、`platform/`（9 个 .kt）、`music/`（1 个 .kt）、`hid/BtHidCompat.kt`、`mirror/MirrorCompat.kt`、`store/ChatImageCache.kt`、`store/ChatHistoryStore.kt`、`store/LeqiToolsModule.kt`、`util/RomFingerprint.kt`、`util/SecretStore.kt` | 六层重构主体，全部未提交（`ChatHistoryStore.kt` 为 #9 新增） |
| 重构源码（RokidLink） | `RokidLink/.../LinkProtocol.kt`、`RokidLink/.../SecretStore.kt` | 协议 v2 眼镜端副本 + 密钥存储 |
| 新增测试（#8 / #9） | `phone-app/src/test/.../adb/`（`AdbSyncProtocolTest.kt` 8 例 / `AdbFileManagerSyncTest.kt` 6 例 / `AdbTestPeer.kt` 脚本化对端）、`phone-app/src/test/.../hid/HidReportTest.kt`（12 例）、`phone-app/src/test/.../ai/ToolRiskMapTest.kt`（8 例）、`phone-app/src/test/.../store/ChatHistoryStoreTest.kt`（14 例） | **新增的回归护栏本身也未入库** —— 一旦 clean checkout，护栏随之丢失 |
| 其它配置 | `phone-app/src/main/res/xml/network_security_config.xml`、`phone-app/src/debug/`（`DebugAiuiReceiver` 迁入 debug 源集）、`gradle/local-gates.gradle.kts`（#10 发布闸门，双端 `apply`） | #6 / #7 / #10 落地物 |
| 文档 | `docs/ARCHITECTURE_REVIEW.md`、`docs/CODE_AUDIT.md`、`docs/COMPATIBILITY.md`、`docs/ENGINEERING_ASSESSMENT_2026-09-12.md`、`docs/UI-IMPROVEMENT-LocalModelPage.md`、`docs/qa/` | 含本报告本身 |
| 其它 | `deliverables/` | 非源码（`.workbuddy/` 与 `adb_*.txt` 已加入 `.gitignore` 并删除） |

> 对应的 7 个已删除但未提交条目：`store/AdbToolsModule.kt`、`store/FileManagerModule.kt`、`store/PhoneMirrorModule.kt`、`store/ScreenMirrorModule.kt`（已合并进 `LEQI_TOOLS` 单页与 `store/LeqiToolsModule.kt`）；`settings/SystemLogPanel.kt`、`store/StoreTargetTags.kt`（零引用，见 §[P1-13]）；`ai/DebugAiuiReceiver.kt`（移入 `src/debug`，见 §[P0-1]）。
>
> **建议**：先分批 `git add` 提交上述重构源码，恢复"可 clean checkout 编译"的基线，再谈拆类（**CI 部分已由 #10 的本地闸门替代**：4 道 `preBuild` 门禁 + 2 道 `packageRelease` 发布闸门）；否则任何门禁都会在第一步失败（`checkGitClean` 会直接拦住 release 出包）。

---

*本报告为只读评估产物，未对仓库做任何修改。建议将 §4 阶段一 5 项立即转为 issue 跟踪，避免再次出现"评估报告写完即搁置"的情况。*
