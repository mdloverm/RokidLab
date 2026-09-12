# RokidLab 上线前全检报告（代码审查 + 安全审计 + QA）

**日期**：2026-09-12
**场景**：上线前检查（product-reviewer + security-officer + qa-lead 三线并行）
**参与成员**：产品评审员（代码审查） + 安全官（OWASP + STRIDE） + QA 负责人（构建验证与发布就绪度）
**受检对象**：`D:\rokidapp\cxrl\RokidLab` 当前工作区（分支 `master`，HEAD = `cd9c6f0` v3.5）
**主理人**：gstack-lead
**复核日期**：2026-09-12（复核人：agent）
**复核结论**：**持续 No-Go（发布工程层）** —— B1–B7 中 **B2（调试 receiver）与 B4（release 全局明文）已于 2026-09-12 关闭**（阶段一行动 #2 / #4），**B6 的「零 CI」部分已由阶段二 #10 的本地闸门替代（4 道 `preBuild` 门禁 + 2 道发布闸门），但 R8 mapping 归档仍未做 → 本项部分关闭**；其余 **4 条（B1 全量提交 / B3 assets 换 release 包 / B5 签名密钥轮换 / B7 三方令牌轮换）仍未关闭**；本报告快照数据（diff/tests/工具数）已按 2026-09-12 最新实测刷新（见各条证据与 §2 表头），QA 单测计数已随阶段二 #8 / #9 更新为 **14 类 / 155 例**。

---

## 📌 TL;DR（执行摘要）

- **整体结论：🔴 No-Go（发布工程层）／🟡 条件 Go（代码层）**。代码本身质量明显高于同类项目，但**当前无法以可追溯的方式发布**。
- **最致命的不是 bug，是"发布的不是仓库里的东西"**：HEAD `cd9c6f0` 里**根本不存在** 52 个新源码文件（`ai/tools/`、`domain/`、`platform/`、`feature/` 等），被跟踪的文件却引用它们 → **clean checkout 直接编译失败**。（2026-09-12 最新复核：`git status --porcelain` 共 **36 个未跟踪条目**，其中新增的 5 个回归测试文件**也未入库**。）
- **阻塞项 7 条**：**已关闭 2 条（B2 / B4）、部分关闭 1 条（B6：CI 部分已由本地闸门替代，mapping 归档仍缺）**；剩余 4 条中 1 条可在 0–3 天内修完且**不需要动业务逻辑**；另 3 条（签名密钥、第三方令牌、assets 换包）**需先做凭据轮换 / 出一次 release**，属不可逆或依赖发布的动作。
- **⚠️ 复审补充（本文档定稿后由主理人只读复核追加）**：新增 1 条阻塞 **B7（Gitee / 爱发电 凭据硬编码）**，并将 **B5 严重度上调**——签名口令不是"硬编码兜底"，而是**明文提交在 `gradle.properties` 里、已在 git 历史中存续**，需按"密钥已泄露"处理。
- **代码层实测健康**：编译通过、R8 双端混淆通过；单测 **14 类 / 155 个 `@Test`**（阶段二 #8 / #9 后静态计数：phone-app 150 + RokidLink 5；2026-09-12 双端 `compileDebug/ReleaseKotlin + testDebugUnitTest + RokidLink:testDebugUnitTest --offline` **EXIT=0**）；AI 工具 **实测 34 条**（`ai/ToolRegistry.kt` 的 `toolList`）；`!!` 仅 59 处、无 Cursor 泄漏、无 Gson/Class.forName 反射面、`checkProtocolSynced` / `checkI18nKeysSynced` / `checkKeyPathEmptyCatch` / `checkNoBareCatch` **四道 preBuild 门禁** + **两道 `packageRelease` 发布闸门**（`checkGitClean` / `dependsOn testDebugUnitTest`）有效。
- **下一步**：先做阻塞项 1（全量提交，含 5 个回归测试文件 + `gradle/local-gates.gradle.kts`）—— 注意 #10 之后**工作区不干净已无法出 release 包**（`checkGitClean` 会直接构建失败）；再修 3 / 5 / 7 后做一次 release 冒烟（重点 AIUI JS 桥）即可复评。

---

## 🎯 核心结论卡片

| 项目 | 内容 |
|------|------|
| **Go / No-Go（发布）** | 🔴 **No-Go** —— 7 条发布阻塞项中 **2 条已关闭（B2 / B4）+ 1 条部分关闭（B6）**，剩 4 条未清（2026-09-12 复核） |
| **Go / No-Go（代码层）** | 🟡 **条件 Go** —— 编译 / R8 / 单测（14 类 155 例）全绿；H2（ASR 竞态）已修（阶段二行动 #7） |
| **严重度分布** | 🔴 **4** / 🟠 8 / 🟡 15 / 🟢 5（原 7🔴，B2 / B4 已关闭、B6 部分关闭；另**主动排除 9 项**误报，见 §2 末） |
| **已验证的亮点** | `checkProtocolSynced` 协议同源守护、`checkI18nKeysSynced` 双语 key 门禁、`checkKeyPathEmptyCatch` 空 catch 门禁、**`checkNoBareCatch` 全仓空 catch 棘轮预算**、**`checkGitClean` + release 依赖单测两道发布闸门（#10）**、`SdkFieldMap` 反射单点收敛、零 Cursor 泄漏 |
| **关键行动项** | **4 条阻塞项**（B1 / B3 / B5 / B7）+ B6 的 mapping 归档 + 高优先修复 |
| **建议负责人** | 作者本人（阻塞项 1–6）+ 产品决策（AI 工具风险分级、BOOT_COMPLETED） |
| **回归验证** | 28 条手工用例已产出（`docs/qa/v3.5-manual-test-cases.md`），**交用户执行** |

---

## 1. 各成员核心结论

### 🔍 产品评审员（代码审查）
- **核心判断**：单看代码质量明显高于同类项目，但**不能按现状上线**——v3.5 提交里装的不是实际跑起来的代码。
- **关键建议**：全部提交并打 tag；CI 加 `git status --porcelain` 非空即 fail。
- **额外贡献**：**读了真实的 `mapping.txt`**，用数据**排除了** R8 反射风险（163 个 `com.rokid.cxr.**`/`com.rokid.sprite.**` 类重命名数 = 0，`-keep` 规则确实生效），避免了一条误报。

### 🛡️ 安全官（OWASP + STRIDE 审计）
- **核心判断**：证书校验未绕过、密钥未硬编码，但"导出组件 + Release 明文 HTTP + AI 高危工具无确认闸门"三处构成上线阻塞。
- **关键建议**：删调试 receiver 或加 signature 权限；release 保持 `usesCleartextTraffic=false` + 域白名单；高危工具独立分级。
- **最强链路**：`DEBUG_CMD` 广播 → 任意 ADB shell（已独立复核确认）。

### ✅ QA 负责人（构建验证与发布就绪度）
- **核心判断**：代码层条件 Go（编译/R8/单测全绿），**发布工程层 No-Go**——release 候选未提交、mapping 未归档、零 CI。
- **关键建议**：提交全部改动后再构建 release；assets 换 release APK；mapping 纳入归档。
- **实测数据**：`:phone-app:compileDebugKotlin` **BUILD SUCCESSFUL 1m28s**；R8 + 单测 **BUILD SUCCESSFUL 18m30s**；**98 tests / 0 fail / 0 err**（8 文件）。→ **2026-09-12 阶段二 #8 / #9 后复核**：全仓 **14 个测试类 / 155 个 `@Test`**（phone-app 13 类 150 例 + RokidLink `AiChannelProtocolTest` 1 类 5 例）；双端 `compileDebug/ReleaseKotlin + processDebug/ReleaseManifest + testDebugUnitTest --offline` **EXIT=0**。

> **主理人注**：三位成员中，代码审查员与 QA 负责人**各自独立**指出了"HEAD ≠ 工作区"，互相印证。安全官与代码审查员在"确认闸门"上的结论存在分歧，已由主理人读原文裁定（见 §2-H1）。

---

## 2. 综合审查发现（去重合并，按严重度排序）

### 🔴 阻塞项（7 条）

| # | 位置 | 问题 | 证据（已复核） | 建议 | 来源 |
|---|------|------|---------------|------|------|
| **B1** | 全仓库（工作区） | **release 候选全量未提交，发布不可复现** | **2026-09-12 复核**：`git diff --stat` = **91 files changed, +6196 / −7379**（修改 84 + 删除 7）；`git status --short` = **36 个未跟踪条目**，展开含 **`.kt` 源码 52 个**：`ai/`16（含 `ai/tools/` 12）、`platform/`9，及 `domain/`、`feature/`、`music/`、`connection/`、`app/`、`glasses/`、`hid/`、`mirror/`、`store/`、`util/`、`RokidLink/LinkProtocol.kt`、`gradle/local-gates.gradle.kts`。被跟踪的 `ai/ToolRegistry.kt` import `com.rokidlab.phone.ai.tools`、`ai/ToolGateway.kt` 引用 `ToolPolicy`/`ToolRisk`、`mirror/PhoneMirrorService.kt` 引用 `MirrorCompat`/`RomFingerprint`、`hid/BluetoothHidManager.kt` 引用 `BtHidCompat` → **HEAD 检出必编译失败**（成立） | 全量 `git add -A && commit` 并打 tag；~~CI 加 `git status --porcelain` 非空即 fail~~ → **#10 已把该闸门落在本地 `checkGitClean`**（挂 `packageRelease`，脏工作区直接构建失败） | 代码审查🔴 · QA🔴 · 主理人复核 · **agent 复核（2026-09-12）** |
| **B2** | `phone-app/src/main/AndroidManifest.xml:79-85`<br>`phone-app/src/main/java/com/rokidlab/phone/ai/DebugAiuiReceiver.kt:104-114` | **导出调试广播 → 眼镜端任意命令执行**（本次最强提权链） | receiver 声明在 **`main` 源集（非 debug）**、`exported="true"` 且**无 `android:permission`**，action `com.rokidlab.phone.DEBUG_CMD` → **release 包同样导出**。任意本机 App 一句 `sendBroadcast` 即可命中，无需任何权限：`act=shell` 把广播携带的 `cmd` 原样交给 `adb.executeShellCommand(cmd)` **在眼镜上执行任意 shell**；另有 `act=install`（打包上传任意项目）、`act=upgrade_ollama`（Termux `RUN_COMMAND` 执行脚本）。**R8 不改变导出组件可达性**（由 manifest 决定）。**2026-09-12 复核：`phone-app/src/main/AndroidManifest.xml:79-85` 仍为 `<receiver android:name=".ai.DebugAiuiReceiver" android:exported="true">` + `com.rokidlab.phone.DEBUG_CMD` intent-filter，**无 `android:permission` 行**，且确认声明在 `src/main/AndroidManifest.xml`（main 源集）—— 成立** | ✅ **已关闭（2026-09-12，阶段一行动 #2）**：类 + manifest 声明整体移入 `src/debug/`，release 合并清单 `DEBUG_CMD` 零匹配，release 包不含该 receiver | 安全官🔴 · QA🔴（联审升级） · 主理人读码确认 · **agent 复核（2026-09-12）** |
| **B3** | `phone-app/src/main/assets/RokidLink.apk` | **内置的眼镜端 APK 是 debug 未混淆包** | **2026-09-12 复核（`Get-FileHash -Algorithm MD5`）**：`phone-app/src/main/assets/RokidLink.apk` = `41D20777631CCFADCFEA03DB2FD5B6E3`（12,301,311 B），与 `RokidLink/build/outputs/apk/debug/RokidLink-debug.apk` **同哈希、同体积 → 逐字节相同，成立**。而 `RokidLink/build/outputs/apk/release/RokidLink-release.apk` = `B5EE32BC48E6D093001160FC50100D71`、仅 8,737,658 B（2026-09-10 09:46，**仍为过期旧包**）。注：`assets/RokidLink.apk` 本身是**被跟踪文件**且当前为 modified 状态 | 跑 `:RokidLink:assembleRelease` 并把 release 产物替换进 assets；注意源码已改，需重新构建而非复用 09-10 旧包 | QA🟠（主理人升为🔴） · **agent 复核（2026-09-12）** |
| **B4** | `phone-app/build.gradle.kts:58` | **release 变体全局放开明文流量** | release 分支覆盖 `cleartextTrafficPermitted = "true"`，而 `defaultConfig:30` 本已设为 `"false"`；全仓**无** `network_security_config.xml`。实证：`build/intermediates/packaged_manifests/release/.../AndroidManifest.xml:79` = `android:usesCleartextTraffic="true"`（debug 亦然，且 `merged_manifests/debug/...:82` 亦为 `true`）。**外泄面具体化**：`domain/AiConfigService.kt:127` 支持用户自定义 `baseUrl`，`ai/OpenAiService.kt:302/365/420` 以 `Authorization` 头携带 API Key 发往该地址 → 中间人降级即可完整窃取 Key 与对话。**2026-09-12 复核**：`phone-app/build.gradle.kts:58` 仍为 `manifestPlaceholders["cleartextTrafficPermitted"] = "true"`（`defaultConfig` 第 30 行仍为 `"false"`）；`phone-app/src/main/res/xml/` 下**仅有 `file_paths.xml`，全程无 `network_security_config.xml` —— 成立** | ✅ **已关闭（2026-09-12，阶段一行动 #4）**：新增 `src/main/res/xml/network_security_config.xml`（base 禁明文 + 回环/眼镜 IP/`ip-api.com` 白名单）+ `src/debug/res/xml/` 同名文件对 debug 放开；release 占位改 `false` 并挂 `networkSecurityConfig`；`ai/AiuiProject.kt` 补「明文被拒 → 回落蓝牙隧道」兜底 | 安全官🔴 · 代码审查🟠 · QA🟡→**🟠**（主理人归一为🔴） · **agent 复核（2026-09-12）** |
| **B5** | `gradle.properties`（**被 git 跟踪**）第 5–7 行<br>`phone-app/build.gradle.kts:41-43`<br>`RokidLink/build.gradle.kts:20-23` | **🔺严重度上调：签名口令已明文提交进 git，属"密钥已泄露"而非"硬编码兜底"** | 实测 `git ls-files gradle.properties` → **该文件被跟踪**；`git show HEAD:gradle.properties` 内容为：<br>`RELEASE_KEYSTORE_PASSWORD=rokid123` / `RELEASE_KEY_ALIAS=rokidbrew` / `RELEASE_KEY_PASSWORD=rokid123`<br>`git diff -- gradle.properties` = **无差异**（HEAD 里就是这份），确认 **HEAD `cd9c6f0` 即含明文口令**；`git log -S rokid123 -- gradle.properties` → **命中 1 个提交**（引入提交 `00ec0c8`，口令自该提交起进入历史）。<br>⚠️ `.gitignore:22` 虽已列 `gradle.properties`，但**文件在被忽略之前就已被提交，gitignore 不会使其脱离跟踪**（典型的"以为忽略掉了"陷阱）。<br>**两模块共用同一 keystore + 同一口令`rokid123` + 同一 alias** → 任何拿到仓库（含历史）的人可**离线伪造手机端与眼镜端两个包的正式签名**。<br>**2026-09-12 复核：上述四条命令全部复现一致（跟踪/明文/无差异/历史命中）；`git ls-files '*.keystore'` 为空（keystore 本体未跟踪）—— 成立** | **仅删默认值不够**：必须**视为密钥已泄露 → 轮换 keystore 并吊销旧密钥**；旧的 `00ec0c8` 起的历史无法通过删除文件清除，需评估是否重写历史或接受旧密钥作废；随后 `git rm --cached gradle.properties`，口令改由不入库的 `local.properties`/环境变量注入，并在 CI 用 secret | 主理人只读复核（**推翻了初报"仅硬编码兜底"的定级**） · **agent 复核（2026-09-12）** |
| **B6** | `phone-app/build/outputs/mapping/release/mapping.txt`<br>仓库 `.github/` 等 | **R8 mapping 未归档 + 零 CI** | **2026-09-12 复核**：`phone-app/build/outputs/mapping/release/mapping.txt` = **216,964,724 B（≈206.9 MB）**、`RokidLink/build/outputs/mapping/release/mapping.txt` = 691,453 B，均位于 `build/`（gitignored），`clean` 即永久丢失 → **线上崩溃无法还原堆栈**。`Test-Path .github` / `.gitlab-ci.yml` / `Jenkinsfile` **全为 False**（仓库无 `.github/`、无 `gitlab-ci.yml`、无 Jenkinsfile）—— 成立。**2026-09-12（#10）：「零 CI」部分已由本地闸门替代**（4 道 `preBuild` 门禁 + 2 道 `packageRelease` 闸门：`checkGitClean` + `dependsOn testDebugUnitTest`），**托管 CI 经评估有意不建**；**残留：R8 mapping 归档仍未做** | mapping 上传符号表服务或纳入发布归档；~~至少加 `compileDebugKotlin + testDebugUnitTest + buildRokidLink` 门禁~~ → **已由 #10 本地闸门覆盖**（`packageRelease` 依赖 `testDebugUnitTest`） | QA🟠 · 主理人复核 · **agent 复核（2026-09-12）** · **2026-09-12 #10 复核：CI 部分关闭，mapping 归档仍缺** |
| **B7** | `RULES.md` 凭据表（约 400–401 行）<br>`.traelink/rules.md:15`<br>`docs/ARCHITECTURE_REVIEW.md:37`<br>`settings/DeveloperScreen.kt:53`<br>`settings/SettingsScreen.kt:531-532` | **第三方凭据明文外泄（文档面 + 源码面）** | **（a）文档面 —— 2026-09-12 已移除，token 需轮换**：`RULES.md` 凭据表、`.traelink/rules.md:15`、`docs/ARCHITECTURE_REVIEW.md:37` 三处原先均以明文写出真实 Gitee 私人令牌 `f795…da8c`；本轮已全部替换为占位符 `${GITEE_TOKEN}`。**但该 token 已随 git 历史长期存续，仍必须到 Gitee 后台撤销并重发**。<br>**（b）源码面 —— 本轮仍未修**：`DeveloperScreen.kt:53` `GITEE_TOKEN = "f795…da8c"`（并被拼进请求 URL `:1176 ...?access_token=$GITEE_TOKEN`）、`SettingsScreen.kt:531-532` `IFDIAN_USER_ID` / `IFDIAN_TOKEN = "Vd7nJX…YEUM"`（爱发电 API 令牌）。两文件均在本次已修改文件内 → **仍会进 release 包**。且 `DeveloperScreen.kt:1169` 的校验仍拿占位符 `"YOUR_GITEE_TOKEN_HERE"` 比较，**该守卫已失效**（真实令牌≠占位符，永远放行） | 立即到 Gitee / 爱发电**撤销并重发**令牌；源码改读 `local.properties`/环境变量或服务端中转；确认 token 未被公开仓库历史泄露（仓库有公开主页，见 `docs/ARCHITECTURE_REVIEW.md:44-47`） | 主理人只读复核（**既有评估 P0-2 已提但未修**） · **agent 复核 + 文档面处置（2026-09-12）** · **2026-09-12 最新复核：源码面三处明文全部仍在** —— `DeveloperScreen.kt:53` Gitee、`SettingsScreen.kt:541` 爱发电、**`util/AppConfig.kt:9 KUWO_API_TOKEN`（酷我）**；文档面已改占位符但**token 未轮换，本项未关闭** |

### 🟠 高风险（8 条）

| # | 位置 | 问题 | 建议 | 来源 |
|---|------|------|------|------|
| **H1** | `ai/ToolPolicy.kt:12-13,52,75,116-121`<br>`ai/ToolGateway.kt:146-151`<br>`domain/AiConversationService.kt:398-413`<br>`glasses/CxrLHiRokidSession.kt:588-590`<br>`ai/GlassToolConfirmChannel.kt:36,114` | **已接入双路径 + 确认通道已挂载，但闸门仍 fail-open，且类注释与实现自相矛盾**。**2026-09-12 读码复核**：①**双路径已接入** —— `ToolGateway.kt:147`（AIUI 页面路径 `SOURCE_AIUI_PAGE`）与 `domain/AiConversationService.kt:403`（对话路径 `SOURCE_CONVERSATION`）均调用 `ToolPolicy.check()`；②**确认通道已挂载** —— `CxrLHiRokidSession.kt:589-590` 执行 `GlassToolConfirmChannel.bind(this)` + `ToolPolicy.confirmationChannel = toolConfirm`（另 `domain/ConnectionService.kt:296`、`app/LabApplication.kt:267` 兜底绑定），即 `confirmationChannel` **已非 null**；③**降级语义仍为 fail-open** —— 无可用通道时 `ToolPolicy.kt:116-121` 审计 `ALLOW (downgraded (no confirmation channel))` 后 `return Decision.Allow`，`:128-130` 确认超时同样 `ALLOW`；④**注释与实现矛盾** —— 类注释 `:12-13` 写"确认通道未接入时**降级为拒绝 + 明确提示**"、`:52` 写"EXTERNAL_SIDE_EFFECT 工具一律降级拒绝"、`:75` 写"当前为 null → 降级拒绝"，均与"已挂载 + fail-open"的现状不符；`GlassToolConfirmChannel.kt:36` 的历史日志文案 `DENY (no confirmation channel)`、`:114` 注释"让 ToolPolicy 立即降级拒绝"同样过时。`ToolRiskMap` 登记 **33 个工具、无一 EXTERNAL**（`call_phone` 按用户 2026-09-11 要求已降为 LOCAL），未登记的真实工具兜底为 LOCAL_SIDE_EFFECT 放行（`ToolRisk.kt:90-99`）；`isExternalConfirmGranted()`（`:73`）**全仓仍零调用点**（已 grep 验证） | 统一注释与代码语义（二选一：fail-closed 或明确写 fail-open）；把 `isExternalConfirmGranted()` 接进真正的高危工具执行点，或删除以免误导；为"通道不可用→放行"补一致的文案 | 安全官🟠 · 代码审查🟡 · **主理人读原文裁定** · **agent 复核（2026-09-12）** |
| **H2** | `glasses/AsrBridgeCoordinator.kt:123-124,147,226,294,389,400,222` | **ASR 去重竞态 + 兜底补读永久丢消息**。`lastAsrText`/`lastAsrTextAt` 为普通 `var`，`onAsrText()` 无锁却有 3 路并发入口 → check-then-act 竞态可致同一句话触发两次 AI 下行；每条消息 `Thread{}` 新建裸线程、无排序 → 后发先至。补读 `tail -n 20` + `lastOrNull()` 只取最后一条，而 `:222` 又把游标推到最新 → **退避期（最长 30s）积压文字永久丢失** | `@Volatile` + CAS 或 `synchronized`；投递改单消费者 Channel/串行协程；补读返回全部新行并按序逐条推进游标 | 代码审查🟠 |
| **H3** | `ai/ToolRisk.kt:52,60,65,66,52` | **高影响工具被归为 LOCAL_SIDE_EFFECT（不确认）**：`open_phone_app`、`install_aiui_project`、`set_phone_volume`、`launch_glasses_app`。注：`call_phone` 不确认是**用户 2026-09-11 的明确要求**（`ToolRisk.kt:70-74` 有载明），**不属缺陷**；但其余四项的分类需产品确认 | 逐项评审分类；把"可拉起任意应用/安装项目"提为需确认档 | 安全官🟠 · 主理人澄清 |
| **H4** | `RokidLink/.../AndroidManifest.xml:73-79`<br>`AiuiLinkActivity.kt:156-166,216`<br>`AixBundleReader.kt:31-73` | 眼镜端**导出 Activity 接受任意 `aix_path`** 加载 `.aix` 并注入 `Android` JS 桥（含 `__lab/tool_call` fetch 通道，可达手机工具）；`.aix` 只是**无签名 zip** 且接受任意可读路径。⚠️ **触发有未证实前置**：需攻击者先把恶意 `.aix` 投放到 RokidLink 可读路径，而 manifest **未申请任何外部存储权限**，Android 11+ scoped storage 下该投放是否可行依赖眼镜 ROM，**未能证实** | `exported="false"` 或校验调用方签名；`aix_path` 限定私有目录白名单 | 安全官🟠（`docs/ENGINEERING_ASSESSMENT_2026-09-12.md` P0-4 已提，**未修**） |
| **H5** | `RokidLink/.../AndroidManifest.xml:81-84`<br>`KeyButtonService.kt:1764-1789,2045` | 眼镜端**导出服务**；`handleOpenApp` 以任意 pkg/activity `startActivity`；`handleConfig` 可写任意按键映射。⚠️ **触发有未证实前置**：需"第三方 App 能发 CXR 自定义指令"；已核 RokidLink 的 merged manifest，**无任何 CXR 相关权限/组件**（路由在眼镜系统进程 `cxr-service` 内），该前提**在本仓库无法证实**。严重度成立（导出 + 无权限 + 高危能力），但**不应表述为"已确认可被第三方驱动"** | `exported="false"`/加权限 + pkg 白名单 | 安全官🟠（**主理人注**：若真机证实 CXR 可被第三方调用，本项与 H4 应上调为🔴） |
| **H6** | `domain/AiConfigService.kt:62-64`<br>`KeyButtonService.kt:1576` | **API Key 明文存 SharedPreferences（双端）并经 CXR 平文下发**；仅 UI 掩码（`ChatSettingsDialog.kt:349`） | `EncryptedSharedPreferences` / Keystore 加密，或至少 CXR 侧加封装 | 安全官🟠 |
| **H7** | `ai/ToolGateway.kt:166-172` | 超时 15s 后**不中断 worker**，页面收到 `error` 会重试 → `call_phone`/`install_aiui_project` **重复副作用** | 按 `cbId` 做幂等 | 代码审查🟡（主理人升🟠，因涉及重复拨号/重复安装） |
| **H8** | 仓库根 | **零托管 CI 门禁**（与 B6 同源，此处单列其质量后果）：**84 个改 / 7 删（+6196 / −7379，2026-09-12 实测）** + 开启 R8。**#10 已补本地闸门**（4 道 `preBuild` 门禁 + 2 道 `packageRelease` 发布闸门：洁净工作区 + 必须跑绿单测），托管 CI 经评估有意不建 | ~~加最小流水线~~ → 本地闸门已落地 | QA🟠 → 🟡 |

### 🟡 中风险（13 条）

| # | 位置 | 问题 | 来源 |
|---|------|------|------|
| M1 | `ai/AiuiProject.kt:264` | `AixHttpServer` 绑 `0.0.0.0` 无鉴权，局域网可下载 `.aix`/`agents.json` | 安全官 |
| M2 | `AiuiLinkActivity.kt` | WebView 无 `shouldOverrideUrlLoading` 域白名单 + `allowContentAccess=true`，JS 桥随导航常在 —— ✅ **已修（2026-09-12）**：补非 `https://ink.local/` 顶层导航阻断 + `allowContentAccess=false`。**残留**：外部 `<iframe>` 属子框架加载仍可触达 `Android` 桥，彻底关闭需改「仅向页面 realm 注入桥」 | 安全官 |
| M3 | `AiuiLinkActivity.kt:441` | `dispatchHostMessage` 把 json 直拼进 `evaluateJavascript`（同文件 `:360 bootJs` 却正确用了 `quote`） | 安全官 |
| M4 | `phone-app/.../AndroidManifest.xml:24,25,32,37` | `QUERY_ALL_PACKAGES` / `REQUEST_INSTALL_PACKAGES` / `CALL_PHONE` / `SYSTEM_ALERT_WINDOW` 需复核必要性与商店合规 | 安全官 |
| M5 | `ai/DebugAiuiReceiver.kt:25` | `Log.i` 打印 `intent.extras` 全量 —— ✅ **影响面已消解（2026-09-12）**：该类已随 P0-1 移入 `src/debug`，release 包不含，仅在 debug 包内打日志 | 安全官 |
| M6 | `RokidLink/.../MainActivity.kt:450-457` | `setprop adb.tcp.port 5555`，adbd 默认监听 `0.0.0.0`（需 RSA 授权，风险中） | 安全官 |
| M7 | `res/values{,-en}/strings.xml` | **18 处**占位符未用定位格式（`%s %s` → 应为 `%1$s`）→ 多语言占位符错位 | QA |
| M8 | `adb/AdbFileManagerClient.kt`(12)、`AdbScreenMirrorClient.kt`、`AdbShellClient.kt` 等 | **空 `catch {}` 共 73 处**，掩盖 ADB 通道错误 | QA |
| M9 | `hid/BluetoothHidManager.kt`(6)、`filemanager/FileManagerActivity.kt`(5)、`util/HttpClient.kt`(5)、`mirror/PhoneMirrorService.kt`(4) | **`!!` 共 59 处 / 17 文件** → NPE 风险 | QA |
| M10 | `store/LocalModelPage.kt:93`(782 行)、`domain/AiConversationService.sendAiTextViaLink`(631 行)、`settings/DeveloperScreen.kt:102`(609 行) | **≥120 行函数 49 个**，多落在 v3.5 改动区 | QA |
| M11 | `phone-app/src/test`（13 文件）+ `RokidLink/src/test`（1 文件）/ 无 `androidTest` | **14 类 / 155 个 `@Test`**（阶段二 #8 / #9 后静态计数，双端 EXIT=0）；✅ **已补两批高危链路回归测试**：ADB sync 帧（`AdbSyncProtocolTest` 8 / `AdbFileManagerSyncTest` 6）、HID 报表字节（`HidReportTest` 12）、`ToolRiskMap` 完整性（8）、`AiChannel` v0/v1 矩阵（`AiChannelTest` 21）、聊天历史落盘格式与迁移（`ChatHistoryStoreTest` 14）；**残留**：`KeyButtonService` / `ChatStateHolder`（本体）/ `CxrLHiRokidSession` 仍零测试，投屏/AIUI 关键路径仍无自动化 | QA |
| M12 | `ai/LongTermMemoryManager.kt:166`、`ai/ToolRegistry.kt:625`、`ai/ToolGateway.kt:181` | 截断按 `char` 切会**撕裂代理对**（emoji/中文边界产生乱码） | 代码审查 |
| M13 | `glasses/AiuiFrontendController.kt:219-220` | 把模型抽取的 params 拼进 shell；已 `replace` 单引号（注入风险低）但**无长度上限** | 代码审查 |
| M14 | `music/LabMediaButtonReceiver.kt` | 导出 receiver（`exported=true`），仅筛 `ACTION_MEDIA_BUTTON` → 可控性低，但建议一并收敛 | QA |
| M15 | `RokidLink/.../KeyButtonService.kt:922-1049` | `subscribe`/`sendCustomCmd` 侧**无调用方校验**（配合 H5 的导出服务） | QA |

### 🟢 低风险 / 已知约束（5 条）

| # | 位置 | 问题 | 定性 |
|---|------|------|------|
| L1 | `RokidLink/src/main/AndroidManifest.xml` | **无 `BOOT_COMPLETED`**（仅 `SelfRestartReceiver`）：眼镜重启后 CXR 指令必丢，靠安装后主动拉起绕过 | 已知约束，**需产品决策**（恢复广播会触发 ROM 自启拦截，权衡后可能是有意为之） |
| L2 | `glasses/AsrBridgeCoordinator.kt:134` | `pushWasDown` 只写不读（死状态） | 代码审查 |
| L3 | `ai/LongTermMemoryManager.kt:330` | 空 `catch {}` | 代码审查 |
| L4 | `phone-app/proguard-rules.pro:31` | `-keep class androidx.compose.** { *; }` 过宽，白送体积（非正确性问题） | 代码审查 |
| L5 | `RokidLink/.../AiuiLinkActivity.kt` | `@JavascriptInterface` 保名依赖 AGP 默认 `proguard-android-optimize.txt`；该规则文件在本机 gradle cache 与 SDK 均未落地，**无法离线读证** | ⚠️ **本次唯一需真机确认的 R8 点** |

**✅ 已明确排除（审查中主动查证后判定"不是问题"，避免误报）**

| 项 | 结论 | 依据 |
|---|------|------|
| 证书校验绕过 | **不存在** | 无 `TrustAllCerts` / 无空实现 `X509TrustManager` / 无 `HostnameVerifier` 恒真 |
| API Key 传输方式 | **规范** | `OpenAiService.kt:302/365/420` 走 `Authorization` 头，未走 query/URL；UI 已掩码（`ChatSettingsDialog.kt:349`） |
| SQLite 注入 | **不存在** | `LongTermMemoryManager.kt:83`、`KnowledgeBase.kt:327` 全部参数化查询 |
| Cursor 泄漏 | **不存在** | 所有 `rawQuery` 均包 `.use{}` |
| R8 反射面（SDK） | **安全** | 读真实 `mapping.txt` 实证：163 个 `com.rokid.cxr.**`/`com.rokid.sprite.**` 类**重命名数 = 0**，`client-l-1.1.2.aar` 自带 `-keep` 规则生效 → `SdkFieldMap` 的 `getDeclaredField` 在 release 下成立 |
| R8 反射面（app） | **安全** | app 侧无 `Class.forName` / 未用 Gson 反射（统一 `org.json`）；RokidLink 侧 `method.invoke()` 均为 lambda 与框架 `WifiManager` 反射 |
| 硬编码密钥 | **⚠️ 误判已更正 —— 实际存在 2 处真实凭据，见 B7** | 主理人复审（只读扫描）推翻初判 |
| 协议漂移 | **有工程化护栏** | `checkProtocolSynced` 在 `preBuild` 强制双端 `AiChannel.kt`/`LinkProtocol.kt` 逐字节同源 + 禁止裸协议字面量（本次构建已通过该校验） |

---

## ✅ 行动清单

### 阶段一：发布阻塞项（必须先做，0–3 天）

| # | 行动 | 负责方 | 紧急度 | 验收标准 |
|---|------|--------|--------|---------|
| 1 | **全量提交 v3.5 改动**（2026-09-12 最新实测：**M 84 + D 7 = 91 个改/删文件 + 36 个未跟踪条目**，其中含 #8 / #9 新增的 5 个回归测试文件）并打 tag | 作者 | **P0** | `git status --porcelain` 为空；在**干净克隆**下 `./gradlew :phone-app:compileDebugKotlin` 通过 |
| 2 | ~~**删除或收紧 `DebugAiuiReceiver`**：移入 `src/debug/` 或加 signature 权限；删除 `act=shell` 分支~~ ✅ **已完成（2026-09-12，采用 `src/debug` 方案）**：类 + manifest 声明整体移出 `src/main`，新建 `phone-app/src/debug/{AndroidManifest.xml, java/.../ai/DebugAiuiReceiver.kt}` | 作者 | **P0** | ✅ `phone-app/build/intermediates/**/AndroidManifest.xml` 检索 `DEBUG_CMD` → release 合并清单**零匹配**，仅 3 个 debug 产物命中；release 包不再出现该导出 receiver |
| 3 | **assets 换 release 眼镜端 APK**：重新 `:RokidLink:assembleRelease` 后替换 | 作者 | **P0** | `assets/RokidLink.apk` 的 MD5 ≠ `RokidLink-debug.apk`，且与最新 `RokidLink-release.apk` 一致 |
| 4 | ~~**release 关闭全局明文**：`build.gradle.kts:58` 改回 `false` + 新增 `network_security_config.xml` 白名单~~ ✅ **已完成（2026-09-12）**：`src/main/res/xml/network_security_config.xml`（base 禁明文 + 回环/眼镜 IP/`ip-api.com` 白名单）+ `src/debug/res/xml/` 同名文件对 debug 放开；release 占位改 `false` 并挂 `networkSecurityConfig`。实测 release 合并清单 `usesCleartextTraffic="false"`。因 NSC 不支持 CIDR，`ai/AiuiProject.kt` 已补「明文被拒 → 回落蓝牙隧道」兜底 | 作者 | **P0** | ✅ 已验证（合并清单 + packaged_res 分流） |
| 5 | **🔺轮换签名密钥**（已泄露）：生成新 keystore → 停用旧密钥 → `git rm --cached gradle.properties` → 口令走不入库注入 | 作者 | **P0** | 新包用新密钥签名；`git ls-files gradle.properties` 为空；`git log -S rokid123` 不再增长 |
| 6 | **归档 R8 mapping** 并~~加**最小 CI 门禁**~~ | 作者 | **P0**（CI 部分已由 #10 替代） | mapping 有归档路径；~~CI 跑 `compileDebugKotlin + testDebugUnitTest + buildRokidLink`~~ → **#10 已改本地闸门**：4 道 `preBuild` 门禁 + 2 道 `packageRelease` 闸门（`checkGitClean` + `packageRelease dependsOn testDebugUnitTest`），门禁在本机构建即触发，托管 CI 有意不建 |
| 7 | **🆕 撤销并轮换 2 个第三方凭据**：Gitee PAT（`DeveloperScreen.kt:53`）、爱发电令牌（`SettingsScreen.kt:532`）。**文档面 3 处明文（`RULES.md`/`.traelink/rules.md`/`docs/ARCHITECTURE_REVIEW.md`）已于 2026-09-12 移除为占位符，但 token 仍需轮换** | 作者 | **P0** | 旧令牌在两端后台显示"已失效"；源码中不再出现明文值；`DeveloperScreen.kt:1169` 的占位符守卫恢复有效 |

### 阶段二：高优先级修复（可与阶段一并行，1 周内）— ✅ 已完成（2026-09-12）

| # | 行动 | 负责方 | 紧急度 | 结果 |
|---|------|--------|--------|------|
| 7 | 修 `AsrBridgeCoordinator` 竞态：`@Volatile`+CAS、投递改单消费者 Channel、补读改为全量按序推进 | 作者 | **P1** | ✅ 落地为「单线程 `asrExecutor` 消费者 + `dedupeLock` + 去重条件改为『同文且在处理中』+ `AsrBridgeRead` 逐条推进游标」 |
| 8 | 统一 `ToolPolicy` fail-open/fail-closed 语义并让注释与代码一致；接线或删除 `isExternalConfirmGranted()` | 作者 | **P1** | ✅ 语义显式写死为 fail-open（仅眼镜端显式取消才拒绝）；死代码 `isExternalConfirmGranted()` 已删除 |
| 9 | `ToolGateway` 按 `cbId` 做幂等，避免超时重试导致重复拨号/重复安装 | 作者 | **P1** | ✅ `dedupeMap`（TTL 60s / 上限 64）复用首个 worker 结果 |
| 10 | 眼镜端 `AiuiLinkActivity` / `KeyButtonService` 改 `exported="false"` 或加签名校验 + pkg 白名单 | 作者 | **P1** | ⚠️ **只收敛 `AiuiLinkActivity`（→`exported="false"`）；`KeyButtonService` 经实测保留导出** —— 见下方说明 |
| 11 | API Key 改 `EncryptedSharedPreferences` / Keystore | 作者 | **P1** | ✅ 新增双端 `SecretStore`（Keystore AES-256-GCM），旧明文读取时自动加密回写迁移 |
| 12 | 补第一批高危链路回归单测（ADB sync / `pullFile` FAIL / HID 报表 / `AiChannel` 矩阵 / `ToolRiskMap`） | 作者 | **P1** | ✅ **已完成（2026-09-12，#8）**：新增 `AdbSyncProtocolTest`(8) / `AdbFileManagerSyncTest`(6) / `HidReportTest`(12) / `ToolRiskMapTest`(8)，扩 `AiChannelTest` 17→21、`RokidLink/AiChannelProtocolTest` 4→5；测试类 9→13、`@Test` 102→141，双端 EXIT=0。**残留**：`KeyButtonService` / `ChatStateHolder`（本体）/ `CxrLHiRokidSession` 仍无测试 |
| 13 | 补第二批回归单测（聊天历史落盘格式与迁移：`ChatHistoryStore`） | 作者 | **P1** | ✅ **已完成（2026-09-12，#9）**：新增 `store/ChatHistoryStoreTest` **14 例**（JSONL 往返 / 同 id 后写覆盖先写 / 无 id 条目不去重 / 非法行跳过 / 崩溃截断半行不毁历史 / 上限裁剪 / 旧版 JSON 数组迁移不丢历史 / 迁移后可继续增量追加 / `rewrite(空)` 删文件 / 缺文件返空 / 自动建父目录）；测试类 13→14、`@Test` 141→155，双端 EXIT=0 |

> **行 10 的方案修订（有实测依据，非妥协）**：原计划把两个组件一起收 `exported="false"`。
> 实测（眼镜 `RG_glasses`，非 root shell）对非导出组件执行
> `am start-foreground-service -n com.rokidlab.rokidlink/.BtTunnelService` 返回
> `Error: Requires permission not exported from uid 10094` —— **非导出组件连 adb shell 都进不去**。
> 而手机端 `ToolRegistry.ensureGlassesLinkRunning` 正是靠这条命令拉起常驻服务，
> 一旦收紧，会打断「服务不在跑 → 所有 CXR 自定义指令石沉大海」这条关键路径。
> 签名级自定义权限同样会把 shell 挡在门外，无法两全。故只收敛 `AiuiLinkActivity`
> （唯一启动方是同进程 `KeyButtonService`），`KeyButtonService` 保留导出并把该结论写进 manifest 注释。
> 同时补上原计划遗漏的更实际风险：`AiuiLinkActivity` 的 `.aix` 路径原先只判 `exists()`，
> 已加 `isAllowedAixPath()` 限定在 `filesDir/aiui_host/` 之下。

### 阶段三：真机冒烟（**用户执行**，遵"不代替用户实测"约定）

| # | 行动 | 执行方 |
|---|------|--------|
| 12 | 按 `docs/qa/v3.5-manual-test-cases.md` 跑 28 条用例（9 条路径） | **用户** |
| 13 | **R8 专项冒烟**：装 release 包，逐入口验证 AIUI 页面 JS 桥（观察 `ClassNotFoundException` / `NoSuchMethodError`）—— 覆盖 §2-L5 未验证项 | **用户** |
| 14 | 多活跃 MediaSession 场景下验证歌词（先关网易云/番茄畅听） | **用户** |

---

## ⚠️ 待完善 / 已知局限

**本次未能验证（宁缺毋滥，未读到即不编）**

> **最重要的一条**：**B2 是唯一无需任何额外假设、路径完整、可静态证实的强提权链。H4/H5 的"导出 + 无权限 + 高危能力"已证实，但"攻击者能否实际投递触发"依赖眼镜 ROM 行为，**未能证实**——报告已逐条标注前置假设，请勿按"已确认可利用"处置。

1. **CXR 自定义指令通道能否被第三方 App 使用** —— 直接决定 H5 的真实可利用性。已核 RokidLink merged manifest 无 CXR 权限/组件（路由在眼镜系统进程内），需真机验证。
2. **眼镜端 adbd 5555 是否实绑 `0.0.0.0`**（依赖 ROM，未实测）。
3. **`AsrPushServer` 的 RFCOMM 是否需配对认证**（已见其要求 `isConnected`，未读全鉴权分支）。
4. **`FileManagerActivity` 路径拼接是否存在 shell 注入**（逻辑在 `feature/FileManagerStateHolder`，未逐一核对）。
5. ~~签名 keystore 是否会随仓库分发~~ → **已复核**：`release.keystore` 文件本身**未跟踪**（安全），但**口令与 alias 已被跟踪并提交**（见 B5）——即"锁没丢，钥匙丢在仓库里了"。
6. **旧令牌的对外泄露面未评估**：Gitee / 爱发电令牌已随公开仓库历史存续，是否被第三方抓取/滥用（如他人用该 PAT 推送代码）**无法从本地判断**，需到两端后台查调用记录。（2026-09-12：文档面 3 处明文已移除为 `${GITEE_TOKEN}` 占位符，但**历史提交里的旧 token 仍有效，轮换前不解除本项**。）
7. **`AiuiLinkActivity` 的 `@JavascriptInterface` 在 R8 下的保名**（规则文件未落地）→ **必须真机冒烟**。
8. 全部发现均为**静态与构建层结论**，未做设备实测（遵用户约定）。

**方法论局限**
- 安全审计的 Android 客户端视角完整，但**未覆盖 AI 模型侧的提示注入**（"AI 可否被对话内容诱导调用高危工具"）—— 这需要构造对抗样本实测，建议后续专项。
- 代码审查聚焦**未提交重构**，未逐行复核全部 188 个文件。

**与既有文档的关系**
- 仓库内已存在 `docs/ENGINEERING_ASSESSMENT_2026-09-12.md`（未跟踪，同日生成），其 **P0-1 ~ P0-5 与本次安全审计高度重合**。经比对，其中 **P0-1（导出调试广播）、P0-4（AiuiLinkActivity 导出）、P0-5（明文流量）在本次受检工作区中仍然存在 → 属"已被点名但未修复"**。**后续更新（2026-09-12）**：P0-1 已完成（`src/debug` 隔离）、P0-5 已完成（NSC 白名单收口）、P0-4 已完成 `exported=false` + `aix_path` 白名单 + 顶层导航锁定（残留 iframe 面）；`ENGINEERING_ASSESSMENT` 阶段二 #7（关键链路 catch 落 `LogCollector` + 空 catch 门禁）亦已完成 —— 新增 `checkKeyPathEmptyCatch` 挂 `preBuild`；**#10 后仓库共四道 `preBuild` 门禁**（协议同源 / 多语言 key / 关键链路空 catch / 全仓空 catch 预算）+ **两道 `packageRelease` 发布闸门**（`checkGitClean` / `dependsOn testDebugUnitTest`）。
- 本报告已尽力去重：对既有文档已覆盖的架构结论不重复计分，聚焦**代码正确性缺陷**与**发布工程缺口**。

---

## 📚 成员产出索引

| 成员 | 产出 |
|------|------|
| gstack-product-reviewer（产品评审员） | 代码审查报告（含 R8 `mapping.txt` 实证排除误报、ASR 竞态逐行定位） |
| gstack-security-officer（安全官） | OWASP + STRIDE 审计报告（含 STRIDE 六维简表、10 类攻击面逐项核查） |
| gstack-qa-lead（QA 负责人） | 发布就绪度评估 + 构建实测日志：`_qa_metrics.txt`、`_qa_build_u8.log`、`_qa_r8test_u8.log`、`_qa_tests.txt`（位于 `D:\rokidapp\cxrl\`） |
| gstack-qa-lead（QA 负责人） | **手工测试用例**：`docs/qa/v3.5-manual-test-cases.md`（9 路径 / 28 用例，含新增 REL-04/REL-05） |
| gstack-lead（主理人） | 本报告；独立复核：B1/B2/B3/B4/B6 全部实证确认，H1 读原文裁定成员分歧 |

---

> 本报告由软件工坊 AI 协作生成，关键决策请由工程负责人复核。
> 全部发现基于**静态读码与构建实测**，未经设备实机验证。

---

## 🧭 下一阶段修复顺序（2026-09-12 复核补充）

> 仅列顺序与标题，详细方案见对话上下文。原则：**先修"会静默出错 / 静默丢能力"的，再修"能看见但没做好"的**。

**P0（先修 —— 会造成线上静默异常或能力失效）— ✅ 已全部清零（2026-09-12）**

1. **AI 慢路径代际自废** —— `domain/AiConversationService.kt` 的代际号（`:40-41`、`:244`、`:443`、`:860`）需覆盖慢路径所有检查点，避免用户打断后旧请求仍继续下行。
2. **BtTunnelService 孤儿替换 BtTunnelServer** —— `RokidLink/BtTunnelService.kt` 与 `BtTunnelServer.kt` 当前是"服务持有 server"的并存态，需收敛为单一隧道实现，消除孤儿路径。

**P1（随后修 —— 体验与一致性）— ✅ 3~9 已完成；10 进行中（2026-09-12）**

3. ✅ ASR 去重竞态与补读丢消息（本报告 H2）
4. ✅ 工具确认闸门语义统一 + `isExternalConfirmGranted()` 接线或删除（本报告 H1）
5. ✅ `ToolGateway` 超时按 `cbId` 幂等，避免重复拨号/重复安装（本报告 H7）
6. ✅ 眼镜端导出组件收敛（本报告 H4 / H5）
7. ✅ API Key 改 `EncryptedSharedPreferences` / Keystore（本报告 H6）
8. ✅ 权限跳转 Intent 降级链（`docs/COMPATIBILITY.md` 待办 1）
9. ✅ 兼容性诊断导出入口（`docs/COMPATIBILITY.md` 待办 2）
10. 🟡 真机矩阵验证并回填结果（`docs/COMPATIBILITY.md` 待办 3 表格）—— 设备身份列已实测回填；运行列需在真机点一次设置页「导出兼容性诊断」后填写

> 详细变更见 `docs/纠错方案.md` §五「2026-09-12 — P1 修复」表。
> 尚未进入本清单的遗留：`settings/DeveloperScreen.kt:53` 明文 `GITEE_TOKEN`（需先人工轮换凭据再改代码）、M1~M15 中风险项、未跟踪源码 36 项（托管 CI 已由 #10 改为本地闸门）。
