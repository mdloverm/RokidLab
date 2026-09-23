# RokidLab v3.5 架构与安全评估

> ⚠️ **后已更正（2026-09-23）**：本文 `useLegacyPackaging = false` 的结论**已作废**，现为 **`true`（so 压缩存储）** —— 自持 proot 需在 `nativeLibraryDir` 内 `execve`，`false` 时 so 未压缩直载、系统不落盘 ⇒ 该目录为空、必然 ENOENT；官方 16KB 文档也把「压缩共享库」列为 AGP < 8.5.1 的替代方案（让 16KB 设备装不上的是「未压缩且未做 zip 对齐」，不是压缩）。
>
> 权威说明见 `phone-app/build.gradle.kts` 的 `packaging` 块与 `platform/ProotShell.kt` 类注释；本文其余内容保持当时快照，未作订正。

> 评估日期：2026-09-10（2026-09-12 全量复核刷新，HEAD=`cd9c6f0` / v3.5）
> 评估范围：`D:\rokidapp\cxrl\RokidLab` 全工程（phone-app main 160 + RokidLink main 19，共 **179 个 main Kotlin 文件**；另有测试 15 个）
> 评估方法：静态扫描 + 文档与实现交叉核对（非真机测试）

---

## 一、总体评价

**结论：这是一个完成度远超典型个人项目的工程，主要短板不在"功能"，而在"规模化之后的可维护性与安全边界"。**

给个直观定位：它的技术深度（自实现 ADB TCP 协议栈、自实现蓝牙 HID 描述符、scrcpy H.264 流水线、带 IDF 评分的本地 RAG）已经超过多数商业 App 团队；但它的工程保障体系（CI、测试、依赖注入、分层）基本停留在 v1.0 时代。这形成了强烈的反差——**代码里最硬的那部分，恰恰是保护最薄弱的那部分。**

### 做得好的地方（应当保持）

这部分说的人少，但价值最高，值得先明确。

| 项 | 具体表现 | 为什么难得 |
|---|---|---|
| **把踩过的坑固化成机器可验证的约束** | `RULES.md` 第 12 节 9 小节"架构硬约束"，每条都写了现象→成因→禁rules | 绝大多数项目靠口头传承，这里写成了可执行规则 |
| **双端协议同源校验** | `phone-app/build.gradle.kts:144` 的 `checkProtocolSynced` 任务，挂在 `preBuild` 上，逐字节比对 phone-app / RokidLink 的 `AiChannel.kt` 与 `LinkProtocol.kt`（v2），不一致直接**构建失败** | 这是我在个人项目里见过最专业的防线设计。双端各自持协议副本必然漂移，用 gradle 任务而非人工约定来兜底，判断非常正确（当前局限：仅 phone-app 侧构建校验，RokidLink 独立构建不触发，见 §三 对照表） |
| **i18n 纪律** | 全量扫描 `Text("...")` 中的中文字面量 = **0 处**；`strings.xml` zh 1100 条 / en 1100 条且 key 完全对齐，并由 `checkI18nKeysSynced` 挂 `preBuild` 强制（2026-09-12）；`Color(0xFF...)` 硬编码 35 处中 34 处集中在 `theme/` 两个主题文件 | 越高喊"严禁硬编码"的项目越常见偷偷硬编码。这里真做到了，说明 RULES 是被执行的，不是摆设 |
| **已知死锁经验的复用** | v3.5 一系列修复（AsrPushClient socket 置位时机、start() 1.5s 防抖、CUT 路径 drain socket、`catchUpRequested` 补读）都指向同一个认知：**眼镜端同一时刻只允许一条 RFCOMM** | 说明作者真在真机上调试过竞态，不是照抄文档 |
| **16KB 页面对齐** | `useLegacyPackaging = false` + `abiFilters arm64-v8a` + 把 opencv/onnxruntime override 到 4.12.0/1.22.0 | Android 16 的坑提前踩了，这里很多人还没开始 |
| **Keystore 弱凭据隔离** | allowBackup=false、release 开 R8（13.8MB → 8.7MB）、proguard keep `com.rokid.cxr.**` 注明"JNI 反射" | 有安全意识，只是执行得不完全 |

---

## 二、问题清单（按优先级）

### P0 — 立即处理

#### 1. Gitee Access Token 明文写在 `RULES.md:401`

```markdown
| Gitee API token | `${GITEE_TOKEN}`（占位符，**禁止明文入库**；从环境变量 / 不入库的 `local.properties` 注入。2026-09-12 移除了此前明文写入的旧 token，旧 token 需到 Gitee 后台撤销轮换） |
```

而且 `RULES.md:402` 还配了 `git remote set-url origin https://dlover1314:{TOKEN}@...` 的用法。

这个 token 同时被第 5~7 步用于 **创建 Release 和上传附件（写权限）**，涉及两个仓库 `RokidLab` 与 `RokidBrew-Registry`。

- RULES.md 是会被 AI 助手、CI、协作方频繁读取的文件，等于已经扩散
- 项目有公开主页（爱发电 / Gitee 仓库），暴露面不小
- **建议：立刻到 Gitee 撤销该 token → 生成新 token → 放进 `local.properties`（已 gitignore）或环境变量 → RULES.md 改为占位符 `${GITEE_TOKEN}`**
- 用 shell history 也检查一遍是否有 `https://dlover1314:<token>@gitee.com` 残留

#### 2. Release 签名配置：机器绝对路径 + 弱口令回退

`phone-app/build.gradle.kts:40-43`

```kotlin
storeFile = file("D:\\rokidapp\\release.keystore")
storePassword = providers.gradleProperty("RELEASE_KEYSTORE_PASSWORD").orElse("rokid123").get()
keyAlias      = providers.gradleProperty("RELEASE_KEY_ALIAS").orElse("rokidbrew").get()
keyPassword   = providers.gradleProperty("RELEASE_KEY_PASSWORD").orElse("rokid123").get()
```

两个问题叠加：
- `D:\rokidapp\...` 硬编码：换任何一台机器构建就崩（这正是外层 `D:\rokidapp` 壳工程存在时也没能覆盖的路径）
- `orElse("rokid123")`：**只要 gradle.properties 没配，就静默用 `rokid123` 出正式签名包**。这是"看起来能跑，实际用了错误配置"的典型陷阱

**建议：** 去掉三个 `orElse(...)`，改为找不到就 `error("缺少 RELEASE_* 配置")`，并向 `local.properties` 读取；keystore 路径改为 `file(rootProject.file("release.keystore"))` 相对路径。

#### 3. Release 包全局开放明文流量

> ✅ **已修（2026-09-12）**：新增 `phone-app/src/main/res/xml/network_security_config.xml`（`base-config cleartextTrafficPermitted="false"`，仅白名单 `localhost`/`127.0.0.1`/`::1`、眼镜 IP `192.168.1.168`/`192.168.49.1`、`ip-api.com`）+ `src/debug/res/xml/` 同名文件对 debug 整体放开；release 占位改 `"false"` 并在 manifest 挂 `networkSecurityConfig`（实测 release 合并清单 `usesCleartextTraffic="false"`）。因 NSC 不支持 CIDR 而眼镜 WiFi IP 是自报的任意局域网地址，`ai/AiuiProject.kt` 的 `uploadOnce`/`deleteOnce` 已补「明文被拒 → 回落蓝牙隧道」兜底。以下为修复前记录。

`phone-app/build.gradle.kts:58` — release 变体的 `cleartextTrafficPermitted = "true"`。

ADB over TCP（5555）、TextInputService（7656）、AIUI WebServer（8848）确实都是明文 HTTP/Socket，但正确做法是**用 `networkSecurityConfig` 精细化**：

```xml
<domain-config cleartextTrafficPermitted="true">
    <domain includeSubdomains="false">localhost</domain>
</domain-config>
```

或者至少限定到眼镜 IP 段。现在的写法等于整个 App 的所有网络请求（包括商店拉取 `apps.json`、AI API 调用）都允许降级到明文 HTTP，中间人可以无成本劫持。

> 顺便：`OpenAiService` 用户可自定义 `baseUrl`。如果用户填了 `http://` 的本地 Ollama，在当前配置下是能通的——这也是为什么不能简单地"关掉开关了事"，必须做域名白名单。→ 已按此执行：本地 Ollama 走 `127.0.0.1`（已白名单），外网 http 自定义地址会被系统拒绝（有意）。

#### 4. Lab 工具桥：LLM 生成的页面可直接拨号

这是我在这个项目里看到的**架构层面最值得讨论的风险**，也是 v3.5 最具野心但最没设防的设计。

链路是这样的：

```
用户对乐奇说话（或上传 .aix）
  → 外部 LLM 生成 .ink 页面代码
  → 打包 .aix 推送到眼镜
  → 页面执行 await globalThis.Lab.callTool('call_phone', {...})
  → RFCOMM 上行 → ToolGateway.call()
  → ALLOWED_DOMAINS = DOMAIN_ALL  →  执行  call_phone / search_contacts / read_calendar
```

`RULES.md:435` 明确记着 `ALLOWED_DOMAINS = DOMAIN_ALL（用户 2026-09-09 拍板全开）`。

问题在于：**执行这段代码的不是用户，是 LLM。**

而 prompt injection 在这个项目里是现成的：
- 用户社保؜知识库里的 txt 文档（`KnowledgeBase` 支持导入任意 txt）——可以在正文里埋指令
- 长期记忆 SQLite（`LongTermMemoryManager`）——AI 说过的话会被记住
- AIUI 页面又能 fetch 任意域名（`DOMAIN_ALL`）

一条级联：`来源文档污染 → RAG 检索注入 ASR/聊天上下文 → LLM 生成 AIUI 代码 → Lab.callTool('call_phone')`。

`RULES.md:436` 有句话我认为是误判：

> **`DENY_TOOLS` 只放技术故障工具（如 `open_aiui_app` 自指递归），不放"危险"工具，安全边界由 `isEnabled` 总开关负责**

`isEnabled` 是**全局布尔总开关**，它不是边界——它只能选择"全部放开"或"全部关闭"，无法表达"只读工具放行、副作用工具需确认"。而 `open_aiui_app` 明明是**递归**问题（技术故障），`call_phone` 是**物理副作用**问题，两者性质完全不同，不该用同一套机制处理。

**建议（不必推翻 `DOMAIN_ALL` 的决定，只加一层）：**

把 `ToolRegistry` 的工具声明加一个副作用等级：

```kotlin
enum class ToolRisk { READ_ONLY, LOCAL_SIDE_EFFECT, EXTERNAL_SIDE_EFFECT }
```

- `READ_ONLY`（查电量/查时间/知识库检索/计算）→ 静默放行
- `LOCAL_SIDE_EFFECT`（打开应用/设音量/日历写入）→ 放行，但落一条审计日志
- `EXTERNAL_SIDE_EFFECT`（`call_phone` / `search_contacts` / 发短信类）→ **首次调用时向眼镜推送一句确认："是否拨打 XXX？说确认拨打"**，`AiuiLinkActivity` 已有 `__lab/tool_call_sync` 的同步阻塞 Promise 通道，实现成本很低

同时给 `ToolGateway` 加 per-page 调用频率上限（比如 30 次/分钟），防止页面 bug 打爆 RFCOMM——那条通道现在还要留给 ASR 补读。

> **2026-09-12 复核状态：本项已「部分落地」，但仍属未完全关闭项。**
> - 分级基础设施已存在：`ai/ToolRisk.kt`（`ToolRisk` + `ToolRiskMap`，名字→档位唯一登记处）、`ai/ToolPolicy.kt`（域过滤之外的限流 + 风险闸门 + 审计）、`ai/GlassToolConfirmChannel.kt`（眼镜端确认通道）。
> - 闸门已接入**双路径**：AIUI 页面路径 `ai/ToolGateway.kt:147`（`ToolPolicy.check(SOURCE_AIUI_PAGE, …)`）、对话路径 `domain/AiConversationService.kt:403`（`ToolPolicy.check(SOURCE_CONVERSATION, …)`）；per-page 限流按来源隔离（AIUI 页面 30/min、对话 120/min）也已实现。
> - 确认通道已挂载：`glasses/CxrLHiRokidSession.kt:589-590`（`GlassToolConfirmChannel.bind(this)` + `ToolPolicy.confirmationChannel = toolConfirm`）。
> - **未关闭点（fail-open 与注释矛盾）**：`ToolPolicy.check` 在「无确认通道」或「确认超时」时实际 `return Decision.Allow`（`ToolPolicy.kt:116-121 / 128-131`，注释写作"降级放行"），而该类的 KDoc 顶部（`ToolPolicy.kt:11-13`）却写着"降级为拒绝 + 明确提示"。**代码与设计说明相矛盾，且实际为 fail-open**——真正的外部副作用工具在通道不可用时会被放行（工具侧以"只做无副作用动作"作为代偿，但闸门本身未拒绝）。
> - 另有语义收窄：`call_phone` 已于 2026-09-11 由 `EXTERNAL_SIDE_EFFECT` 降为 `LOCAL_SIDE_EFFECT`（语音指令即授权，直拨不确认）。当前 `ToolRiskMap` 中**没有任何工具登记为 `EXTERNAL_SIDE_EFFECT`**，该档位只剩"未知/幻觉工具名"的兜底命中——即确认闸门在现网实际拦不到任何真实工具。
> - 结论：P0-4 从"未落地"上调为**部分落地**；fail-open + 注释矛盾 + 无真实 EXTERNAL 工具三项叠加，仍列为未完全关闭项。

---

### P1 — 版本内处理

#### 5. God Class 只拆了一半，且 README 已经不准

实测行数 vs README 声明：

| 文件 | README 称 | 实测（2026-09-12） | 备注 |
|---|---|---|---|
| `CxrLHiRokidSession.kt` | 2780（从 3550 拆） | **887**（非空行；含空行 1003） | 已拆两轮（抽出 3 个 coordinator），仍是 facade 过渡态 |
| `MainActivity.kt` | — | 1104 | 第二热点，文档完全没提 |
| `KeyButtonService.kt` | — | 2282 | 眼镜端，现为全项目最大文件 |
| `FileManagerActivity.kt` | — | 1020 | |
| `AdbFileManagerClient.kt` | — | 1556 | 自实现 sync 协议，最高危文件 |

`RULES.md:484` 自己也承认还欠着两笔：

> 后续仍可拆分连接引擎（`connectAnd*` ~1200 行）和 `sendAiTextViaLink`（~600 行）

**关键判断：拆分本身不是目的，因为这三个待拆部分共享同一批可变连接状态**（`cxrLink`、`cxrlConnected`、`glassBtConnected`、`asrHandling`）。在没有状态容器之前把它们拆成三个文件，只会把一个上帝类变成三个互相引用的上帝类——现在的 `CxrLHiRokidSession` 保留"全部 public method 作委派 facade"就是这种过渡态。

**建议：先建状态容器，再拆。**

```
CxrLConnectionState      // SealedClass: Disconnected / Connecting / Ready(ip) / Failed(reason)
CxrLStateRepository      // 持有 StateFlow<CxrLConnectionState>，唯一可变状态源
  ├── CxrLConnectEngine      // 只做 connectAnd*，读写 Repository
  ├── AsrBridgeCoordinator   // 已有
  ├── AiuiFrontendController // 已有
  └── PhotoQuizFlow          // 已有
```

这样 `CxrLHiRokidSession` 自然退化成一个薄路由，不需要"委派 facade"这种妥协。

> 顺便：`MainActivity` 1104 行没人管。它承担了投屏控制 + 状态管理 + 权限请求 + 自更新 + 镜像源对话框，至少把 `performSelfUpdate()` 和镜子源对话框抽出去。

#### 6. 测试覆盖了最容易测的部分，避开了最容易坏的部分

> **状态更新（2026-09-12，阶段二 #8 / #9）**：下文「8 个测试类 / 102 例」为 2026-09-10 自评快照。**现已补两批回归测试**：第一批 #8 新增 `AdbSyncProtocolTest`(8) / `AdbFileManagerSyncTest`(6) / `HidReportTest`(12) / `ToolRiskMapTest`(8)，扩 `AiChannelTest` 17→21、`RokidLink/AiChannelProtocolTest` 4→5；第二批 #9 新增 `store/ChatHistoryStoreTest`(14，聊天历史落盘格式 / 旧格式迁移 / 崩溃容错) —— 全仓 **14 个测试类 / 155 个 `@Test`**（phone-app 150 + RokidLink 5），双端 EXIT=0。下列「建议顺序」1 / 2 / 4 已落地；`KeyButtonService` / `ChatStateHolder`（本体）/ `CxrLHiRokidSession` 仍零测试、`androidTest` 仍为 0；构建保障已补 **4 道 `preBuild` 门禁 + 2 道发布闸门**（阶段二 #10，见 §整改落地状态对照表），托管 CI 经评估**有意不建**（本地构建即触发门禁，托管 CI 属重复执行）。详见 `docs/ENGINEERING_ASSESSMENT_2026-09-12.md` §[P1-11] 与 `RULES.md` §12.15 / §12.16。

现状（2026-09-10 快照）：
- `phone-app/src/test` 下 **8 个测试类**（JVM 单测）+ `RokidLink/src/test` 1 个，**共 9 个测试类 / 102 个 `@Test`**
- 分布：`ai/` 7 个（AgentSessionHistory / Calculator / GoldenAgentEval 23 例 / SkillFetcher / SkillMarkdown / SseStreamAccumulator / TruncateToolOutput）+ `glasses/AiChannelTest`(17 例) + `RokidLink/AiChannelProtocolTest`(4 例)
- **`phone-app/src/androidTest` 不存在**，零插桩测试
- 无 CI，没有任何自动化 gate

对照上面的热点图：**红色和橙色那 9 个文件，一个测试都没有。**

而这些恰恰是全项目技术密度最高、最容易回归的部分：
- `AdbFileManagerClient`（1556 行）自实现 sync 协议，v3.5 刚修过 CUT 路径 drain socket 的丢数据 bug —— 这个 bug 的类型**完全可以用 fake socket 做确定性单测**
- `BluetoothHidManager`（1490 行）自定义 HID 报表描述符 + 报表字节拼接 —— 纯函数，非常好测
- `KeyButtonService`（2282 行）含持久化 crash-loop 计数器 —— 状态机，可测

反过来说，这是**性价比最高的一笔投入**——用最低的成本保护最容易坏、最难调试的部分。

**建议顺序：**
1. `AdbFileManagerClient` 的 sync 协议 → 抽一个 `AdbSyncStream` 接口，fake 实现喂 byte array，把 v3.5 那个 CUT 路径 bug 写成回归测试用例（这是最有说服力的第一批）
2. HID 报表字节拼接 → 纯位运算，纯函数单测，成本最低
3. `AppConfig` 里的超时/重试值 → 加一个"配置健全性"测试（防止误改成负数/0）
4. `AiChannel` 已有测试，很好，但应该加 pipeline schema version 兼容性测试

#### 7. 无 DI、无 ViewModel：状态挂在 Activity 上

实测：
- `ViewModel` / `hiltViewModel` / `@HiltViewModel` 引用数 = **0**
- `mutableStateOf` = **308 处**
- 顶层 `object` 单例 = **30 个**（`AppConfig`、`HttpClient`、`ChatStateHolder`、`brewThemeManager`…）

这么写在小项目里完全 OK，而且 `object` 单例 + `mutableStateOf` 的组合避免了 Context 泄漏（比 `ViewModel` 还得小心 Activity 引用要简单）。但代价已经显出来了：

- `MainActivity` 用 `android:configChanges="orientation|screenSize"` 规避了旋转重建——**这是治标**。低内存导致的进程回收 + Activity 重建这条路径没被覆盖，`onSaveInstanceState` 也得人工维护
- `ChatStateHolder` 是"跨页面切换不丢失"的单例 workaround，正是没有 ViewModel 才需要的
- `ToolGateway`、`AsrBridgeCoordinator` 之间的依赖全靠显式传参或单例，测试无法替换

**不建议现在引入 Hilt**（对一个还有大量新功能的单人项目，改造成本 >> 收益）。建议做一步轻量改动：

```kotlin
// 把连接状态从 XxxActivity / XxxManager 里提出来
class CxrLStateRepository {           // 或先在现有 object 上做
    private val _state = MutableStateFlow<CxrLConnectionState>(Disconnected)
    val state: StateFlow<CxrLConnectionState> = _state.asStateFlow()
}
```

然后 Compose 侧用 `collectAsStateWithLifecycle()`。这是接入 Hilt 的前置准备，但本身就能立刻缓解第 5 条。

---

### P2 — 日常维护类

#### 8. 5 个字符串缺英文翻译（违反自己的 RULES 9.2）

> ✅ **已修（2026-09-12）**：5 条英文串已补齐（zh 1100 = en 1100），并按下方建议新增 `checkI18nKeysSynced` Gradle 任务挂 `preBuild`（校验 phone-app / RokidLink 两模块 key 集合相等）。以下为修复前实测记录。

实测 `strings.xml` zh **1096** 条 / en **1091** 条（以下 5 条缺失，2026-09-12 复核**当时仍未补**）：

```
guide_ready_title
guide_reinstall_link_btn
guide_skip_btn
unknown_author
wifi_config_success
```

有意思的是 4/5 集中在 `GuideScreen.kt`（引导页）——说明是最近一次改动引入的，而且没有 CI 拦住。这正是第 6 条"加 CI"的价值证明。

**建议：** 补英文；再加一条 gradle 任务或简单 lint 检查两个 strings.xml 的 key 集合相等——成本 15 分钟，永久免疫。→ ✅ **两项均已于 2026-09-12 落地**（`checkI18nKeysSynced`）。

#### 9. `targetSdk 34` 已落后

现在是 2026 年 9 月，Google Play 早已要求 targetSdk ≥ 35（Android 15），Android 16 已在路上。当前 `compileSdk = 34 / targetSdk = 34`。

虽然这个 App 主要走官方商店外的渠道分发，不受 Play 强制约束，但 targetSdk 落后会带来隐性收益损失：
- 拿不到新的前台服务类型严格校验（反过来也意味着现在不会被查，但一旦要上架就要返工）
- Android 16 的预测性返回手势、`POST_NOTIFICATIONS` 等新行为无法提前适配

**建议：** 优先级不高但要排期。顺带说一句，`FOREGROUND_SERVICE_SPECIAL_USE` 这种权限在新系统上审查越来越严，需要在 Google Play Console 申报用途——如果未来有上架计划，现在就该把说明文档备好。

#### 10. 异常处理粒度：约 400 处宽泛捕获

> ✅ **关键链路部分已修（2026-09-12）**：4 条关键链路（ASR 补读 / RFCOMM 隧道 / ADB sync / AIUI 工具网关）共 16 处 catch 已落 App 内日志面板 `LogCollector`（带异常对象）；新增 `LogCollector.w(tag, message, throwable)` 重载；新增 `checkKeyPathEmptyCatch`（挂 `preBuild`）扫描 6 个关键链路文件，空 catch 必须带 `// catch-ok: <原因>` 否则构建失败（实测 20 处已全部标注）；`RULES.md` §12.14 立「异常吞噬约束」。**残留**：全仓其余空 catch 与 315 处 `runCatching` 未逐一处理；**全局机器门禁已落地（阶段二 #10）**：`checkNoBareCatch`（挂 `preBuild`）扫描双端 `src/main` 全量空 catch，实测 67 处 / 已标注 20 / 未标注 47，以 `bareCatchBudget = 47` 作**棘轮预算**（只降不升，新增一处即构建失败），存量随改动顺手收敛。以下为修复前记录。

实测 `catch (e: Exception)` / `catch (e: Throwable)` / `catch (_: ...)` 约 **400 处**（旧记 386，已随重构增长）。

README 里写着"所有可能失败的操作都使用 try-catch 包裹"并被列为**优势**——我不同意这个判断。**全量 try-catch 不等于健壮，很多时候等于静默失败。**

风险场景：眼镜端 RFCOMM 通道上的异常会在数百个地方被吞掉，出问题只能靠用户口述现象反推。项目其实已经有基础设施了：`LogCollector`（系统日志面板），但没有和这些 catch 自动打通。

**建议：** 不必全部改。先做两件事：
- 给最关键的 ~10 条路径（ASR 补读、ADB sync、RFCOMM 隧道、ToolGateway）catch 分支强制要求落 `LogCollector` 带 tag
- 加一个 lint rule（或干脆在 CR checklist 里）禁止空 catch 块

#### 11. `Thread.sleep` 63 处

多数在 IO/daemon 线程上是合理的，RULES 也提到 "15s 超时后不 interrupt，让线程自己跑完"——这个判断是对的。但 63 处（phone 50 + link 13）靠人工保证"没有一处在主线程"是不可持续的。建议用一个自定义 lint check，或者在接受 PR 时 grep一遍。

#### 12. 文档漂移（2026-09-12 已同步）

原实测不符项，本轮文档同步已逐条修正：
- ~~README 称 `CxrLHiRokidSession` "3550 → 2780 行"~~ → 已改为「现 887 行」（非空行；含空行 1003）
- ~~README 安装示例写 `RokidLab-v1.0.0-debug.apk`~~ → 已改为 `RokidLab-v3.5-debug.apk`（versionCode 20）
- ~~README 项目结构树未含 v3.5 新文件~~ → 结构树已整块重写，含 `AsrBridgeCoordinator` / `AiuiFrontendController` / `PhotoQuizFlow` 等

**建议：** 把"文件行数""安装文件名"这类会变的数据从 README 里删掉或写成占位符。行数这种信息本来就属于 CI 产物，不该手写到文档里。

---

## 三、建议的落地顺序

按"投入产出比 × 风险紧迫度"排序：

| # | 事项 | 投入 | 产出 |
|---|---|---|---|
| 1 | 吊销轮换 Gitee token，从 RULES.md 移除 | 10 分钟 | 消除最高危泄露 |
| 2 | ~~补 5 条英文字符串~~ ✅ 已完成（2026-09-12） | 15 分钟 | 修复 i18n 断链 |
| 3 | ~~加 gradle 任务：校验 zh/en strings key 集合相等~~ ✅ 已完成（2026-09-12，`checkI18nKeysSynced`） | 30 分钟 | 永久免疫第 8 条 |
| 4 | 签名配置去 `orElse` + 路径相对化 | 30 分钟 | 消除静默错误配置 |
| 5 | ~~networkSecurityConfig 域名白名单替代全局明文~~ ✅ 已完成（2026-09-12） | 2 小时 | 收窄攻击面 |
| 6 | ~~给 `AdbFileManagerClient` sync 协议写第一批回归单测（含 CUT 路径 bug）~~ ✅ 已完成（2026-09-12，`AdbFileManagerSyncTest` 6 例 + `AdbSyncProtocolTest` 8 例含 `pullFile` FAIL 分支） | 1 天 | 保护最高危文件 |
| 7 | `ToolRisk` 分级 + 副作用工具语音确认 | 1~2 天 | 加固 v3.5 新架构的核心风险 |
| 8 | 抽 `CxrLStateRepository`，作为拆 god class 的前置 | 2~3 天 | 解锁后续所有重构 |
| 9 | ~~建最简 CI（运行 unit test + strings 校验 + assembleDebug）~~ ✅ **已落地（2026-09-12，阶段二 #10，方案降级为本地闸门）**：4 道 `preBuild` 门禁（协议 / i18n / 关键链路空 catch / 全仓空 catch 预算）+ 2 道 `packageRelease` 发布闸门（`checkGitClean` + 依赖 `testDebugUnitTest`），双端各自 `apply`；**有意不建托管 CI**（门禁已在本地构建触发，托管 CI 需复刻 SDK/NDK 环境且属重复执行） | 半天 | 让 3、6 真正生效 |
| 10 | targetSdk 升 35 / 36 | 视回归情况 | 面向未来 |

### 整改落地状态对照表（2026-09-12 复核，HEAD=`cd9c6f0`）

对照上表 10 项与 §二 问题清单，逐项核对当前代码真实状态：

| 评审建议（出处） | 状态 | 证据 / 说明 |
|---|---|---|
| P0-1 吊销轮换 Gitee token，移除明文 | **部分落地** | 文档面 3 处（本文件 §37、`RULES.md:401`、`.traelink/rules.md:15`）已于 2026-09-12 改为 `${GITEE_TOKEN}` 占位；**但源码内 `settings/DeveloperScreen.kt:53` 仍为明文，且三个 token 均未轮换** —— 占位不解除风险，仍属阻塞 |
| P0-2 签名配置去 `orElse` + 路径相对化 | **未落地** | `phone-app/build.gradle.kts:40-43` 仍为绝对路径 `D:\rokidapp\release.keystore` + `orElse("rokid123")`；且签名口令已随 `gradle.properties` 提交入库并推送（口令入 git 历史，未清洗） |
| P0-3 release 全局明文流量收窄为域名白名单 | ✅ **已落地（2026-09-12）** | 新增 `src/main/res/xml/network_security_config.xml`（base 禁明文 + 回环/眼镜 IP/`ip-api.com` 白名单）与 `src/debug/res/xml/` 同名放开文件；release 占位改 `false`，manifest 挂 `networkSecurityConfig` —— release 合并清单实测 `usesCleartextTraffic="false"` |
| P0-4 工具副作用分级 + 副作用工具确认 | **部分落地** | `ToolRisk` / `ToolPolicy` / `GlassToolConfirmChannel` 已建并接入双路径（`ToolGateway.kt:147`、`AiConversationService.kt:403`）、通道已挂载（`CxrLHiRokidSession.kt:589-590`）；但通道缺失/超时 fail-open、KDoc 与实现矛盾、现网无真实 `EXTERNAL_SIDE_EFFECT` 工具（详见 §二 P0-4 复核） |
| P1-3 协议单一源治理 | **部分落地** | 门禁升级为 `checkProtocolSynced`（`phone-app/build.gradle.kts:144`），覆盖 `AiChannel.kt` + 双端 `LinkProtocol.kt`；但仅挂 phone-app `preBuild`，RokidLink 独立构建不触发校验 |
| P1-5 拆 God Class（先建状态容器） | **部分落地** | `connection/ChannelArbiter.kt` + `app/AppContainer.kt` 已落地；`CxrLHiRokidSession.kt` 已拆两轮至 887 行；`KeyButtonService.kt`(2282) 等仍是未治理上帝类 |
| P1-6 补关键链路回归单测 + CI | **部分落地** | **已补两批（2026-09-12）**：测试 9 类 / 102 例 → **14 类 / 155 例**（#8 新增 `AdbSyncProtocolTest` / `AdbFileManagerSyncTest` / `HidReportTest` / `ToolRiskMapTest`，扩 `AiChannelTest`、`RokidLink/AiChannelProtocolTest`；#9 新增 `ChatHistoryStoreTest` 14 例），双端 EXIT=0；**残留**：`KeyButtonService` / `ChatStateHolder`（本体）/ `CxrLHiRokidSession` 仍零测试、`androidTest` 为 0；**CI 部分**已由 #10 改为本地闸门（4 道 `preBuild` + 2 道 `packageRelease`），托管 CI 有意不建 |
| `ChatStateHolder` 主线程同步磁盘 IO + O(n²) 持久化（#9） | **已修（2026-09-12）** | 抽出纯 JVM 的 `store/ChatHistoryStore.kt`；落盘/加载都移到单线程 daemon `chat-history-writer`，`add`/`addImage`/`finalizeLastAi` 各只追加一行 JSONL（后写覆盖先写），`clear()` 删文件，冷启动后台迁移旧版 JSON 数组格式 + 压实，失败落 `LogCollector`。详见 `DEV_GUIDE.md` §8.6 与 `RULES.md` §12.16 |
| P1-7 引入状态容器 / 轻量 DI | **部分落地** | `app/AppContainer.kt` 手动 DI 容器已引入；但 `StateFlow`/`ViewModel` 仍为 0，状态仍以 Compose state + 单例为主 |
| P2-8 补 5 条英文字符串 | ~~**未落地**~~ → ✅ **已落地（2026-09-12）** | zh 1100 = en 1100；并新增 `checkI18nKeysSynced` preBuild 门禁（phone-app / RokidLink 双模块 key 集合校验），永久免疫 |
| 双端 LinkProtocol v2 + 握手三态（v3.5 新增能力） | **已落地** | 双端 `LinkProtocol.kt` 同源 + `glasses/GlassesHandshake.kt` 三态握手；`ToolProvider` 拆分（10 个域 Provider）、`AppContainer` 手动 DI、`ChannelArbiter` 均已在工作区落地（注：源码尚未提交，见 ENGINEERING_ASSESSMENT §9） |

**汇总：** 三项最高危安全建议中，**release 全局明文流量已于 2026-09-12 收口**（NSC 白名单 + release `usesCleartextTraffic=false`）；**token 明文（源码 `DeveloperScreen.kt:53` 仍为明文、三处 token 未轮换）与签名口令入库（含 git 历史未清洗）仍未修复**；工具风险闸门与分层重构为**部分落地**。

---

## 四、一句话总结

**它的技术实现深度配得上更好的工程保障。现在的代码是"靠作者的脑子在维护"，而下一步是"靠机制在维护"——`checkProtocolSynced` 那个 gradle 任务已经证明作者知道怎么做，只是还没系统性地做。**
