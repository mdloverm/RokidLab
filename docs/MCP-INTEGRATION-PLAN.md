# 乐奇聊天 MCP 客户端接入方案

> 对照对象：RokidLab `phone-app` 乐奇聊天（v3.9）
> 前置文档：[AGENT-ROADMAP-DSH-ALIGNMENT.md](./AGENT-ROADMAP-DSH-ALIGNMENT.md)（工具接缝化已完成）
> 日期：2026-09-20
> 状态：**方案评审中，未动代码**

---

## 0. 一句话结论

**MCP 能接，接入点唯一（`ai/tools/ToolProvider`），但"只需再加一个 `McpToolProvider`"这个既有判断不成立。**

provider 只是外壳。真正的工作量在于：`ToolRegistry` 的**六张派生表全部是 `object` 初始化时算一次的静态快照**，而 MCP 的工具集是**连接之后才知道**的 —— 现在物理上塞不进去。

因此本方案把工作切成两段：

| 段 | 内容 | 独立价值 |
|---|---|---|
| **地基** | `ToolRegistry` 从「静态快照」改成「可刷新的派生视图」 | 有 —— 顺带解掉 `SESSION_AGENT_DOMAINS` 的僵化，之后加工具真的只需改一个文件 |
| **MCP 本体** | `McpToolProvider` + 传输层 + 配置 UI | 无（依附于地基） |

**建议把地基当成一次独立的接缝升级来排期**，而不是把它藏在"MCP 功能"里。

---

## 0.1 实施状态（2026-09-20）

**S0–S4 全部落地，S6 文档同步完成；已打 debug 包并安装到手机（2026-09-20 11:26），功能验收仍待用户执行。**

验证证据汇总：`compileDebugKotlin` **BUILD SUCCESSFUL**；`testDebugUnitTest` **BUILD SUCCESSFUL**；
`check_tool_wiring.py` → `provider 数 = 13，声明工具数 = 39（另有动态 provider 1 个）` / **接线完整 ✅**；
`assembleDebug` **BUILD SUCCESSFUL** → `adb install -r` **Success** → 外部拉起后 `pidof` 有值、
`topResumedActivity = com.rokidlab.phone/.app.MainActivity`、`SYSTEM_ALERT_WINDOW: allow`（覆盖安装不丢）。

| 步 | 状态 | 落地位置 | 验证证据 |
|---|---|---|---|
| S0 | ✅ | `ai/tools/ToolEntry.kt` 加 `dynamicName`/`dynamicDescription`；`ToolRegistry.ToolCategory` 加 `MCP`；`ai_tool_cat_mcp` 双语串 | `compileDebugKotlin` EXIT=0 |
| S1 | ✅ | `ToolRegistry.kt` 派生表惰性化（`DerivedTables` + `@Volatile tableVersion`）、`schemaCache` key 改 `Pair<Set<String>, Int>` | 编译 EXIT=0 + `testDebugUnitTest` 全绿 + 接线脚本全绿 |
| S2 | ✅ | `ai/mcp/McpClient.kt`（JSON-RPC / SSE+JSON 双形态 / 超时）、`McpServerStore.kt`、`McpRegistry.kt`、`ai/tools/McpToolProvider.kt`、`util/HttpClient.kt` 加 `postStreamWithHeaders` | `compileDebugKotlin` EXIT=0 |
| S3 | ✅ | 同上（`McpClient` 三个方法 + 分页 `tools/list` + `flattenContent`） | 同 S2 |
| S4 | ✅ | `store/McpServersPage.kt`（列表 / 添加 / 编辑 / 删除 / 逐工具开关 / 状态点 / 被拒工具如实展示），入口＝`ChatSettingsDialog` 「AI 工具」**正下方**新增一行 | `compileDebugKotlin` EXIT=0（**含 `checkI18nKeysSynced`**） |
| S5 | 🟡 装机完成 | `assembleDebug` + `adb install -r` 已执行并拉起（2026-09-20 11:26）；**填入真实 MCP server 的功能验证待用户做** | `Success` + `pidof` + `topResumedActivity` |
| S6 | ✅ | 本文档 + RULES §12.10（新增第 4 条 + 修正"闸门空转"表述）+ 脚本豁免说明 | 人工审阅 |

### 落地时与方案有出入的四处（值得记）

1. **`McpToolProvider` 没有写进 `ToolRegistry.providers` 静态列表**。方案 S2 原写"注册进 `providers`"，
   但那是 `object` 初始化时求值的编译期常量，与"运行期才知道工具集"直接矛盾。
   实际做法：由 `McpRegistry.rebuildIndex()` 调 `ToolRegistry.setDynamicProviders(listOf(McpToolProvider))` 注入。
2. **`setDynamicProviders` 的位置必须在 `providers` 声明之后**（属性初始化顺序），
   且它的 `entries` 等派生表必须**晚于** `providers` 声明 —— 与既有约定一致。
3. **设置页的"保存"不是一次性提交**：工具开关在页面里即时生效（`ToolRegistry.setEnabled`），
   「完成」只是关页。这与会话级开关的"保存才生效"是两种惯例，不要混。
4. **地址准入从"一刀切 https"放宽为"https + 回环 http"**（装机时发现的问题，方案 §4.4 原本没写这一条）。
   原因：debug 变体其实允许明文流量，但 UI 写死 `startsWith("https://")` ⇒ 想先用**自建/局域网 MCP**
   验证链路时被自己的输入框拦住、根本存不进去。改法见 §4.4 的「地址准入」行，关键是**按两份变体配置的交集
   开口子**，而不是按 `BuildConfig.DEBUG` 分支。

---

## 1. 现状勘核（实证）

### 1.1 可以直接复用的（不用加任何依赖）

| 能力 | 位置 | 说明 |
|---|---|---|
| 工具接缝 | `ai/tools/ToolProvider.kt:20-29` | `toolNames` / `tools()` / `execute()` 三个口，12 个 provider 各管一域 |
| 聚合点 | `ai/ToolRegistry.kt:115-116` | `entries = providers.flatMap { it.tools() }` |
| HTTP 客户端 | `phone-app/build.gradle.kts:479` | OkHttp 已在依赖里（连接池 + HTTP/2） |
| SSE 逐行读取 | `util/HttpClient.kt:155` `postSse` | AI 聊天流式链路在用，可直接喂 MCP 的 SSE 响应 |
| SSE 累积/解析 | `ai/OpenAiService.kt:725` `SseStreamAccumulator` | 有单测基础（`build.gradle.kts:517` 已引入真实 `org.json`） |
| schema 直通 | `ai/tools/ToolEntry.kt:49` | MCP 的 `inputSchema` 本身就是 JSON Schema，只需搬字段名 |

### 1.2 真正的缺口（按难度排）

| # | 缺口 | 证据 | 影响 |
|---|---|---|---|
| 1 | **六张派生表是静态快照** | `ToolRegistry.kt:93`（`providers`）、`:115`（`entries`）、`:119`（`entryByName`）、`:147`（`SIDE_EFFECT_TOOLS`）、`:219`（`GLASSES_REQUIRED_TOOLS`）、`:227`（`toolList`）全是初始化时求值的 `val`；`:321` `schemaCache` 以 domains 为 key | MCP 工具运行时才发现 → 塞不进去；且缓存 key 不含工具集版本 |
| 2 | **文案字段是 `Int` 资源 ID** | `ai/tools/ToolEntry.kt:39-40` `displayNameRes` / `descriptionRes` | 动态工具没有编译期资源 ID |
| 3 | **文案有第二条消费链** | `ai/ToolGateway.kt:151` `listAllowedJson()` | `descriptionRes` 会**下发到 AIUI 页面**，动态文案要一起走通 |
| 4 | **执行是同步签名** | `ToolRegistry.kt:403` `fun execute(...): String`；`ai/ToolGateway.kt:271` 用独立线程跑（容忍阻塞），而 `ai/subagent/ReadOnlySubagent.kt:154-160` 是**同步**调用 | MCP 网络耗时直接进主循环，provider 内必须自带超时 |
| 5 | **域装配是静态 `val`** | `ToolRegistry.kt:53-57` `DOMAIN_ALL`、`:60` `SESSION_AGENT_DOMAINS` | 要加 `DOMAIN_MCP` 且"连不上就不下发"，静态集合表达不了 |
| 6 | **分类枚举绑死资源** | `ToolRegistry.kt:154` `enum class ToolCategory(val labelRes: Int)` | MCP 需要新分类 → 必须同步 `values` + `values-en`，否则 `checkI18nKeysSynced`（`build.gradle.kts:301`）拦构建 |
| 7 | **回归脚本会误报** | `skills/rokidlab-chat-standalone-mode/scripts/check_tool_wiring.py`（RULES §12.10 第 3 条） | 脚本双向核对 provider 的 `toolNames` 与 `tools()`，动态 provider 天然不一致 |

### 1.3 对既有判断的两处修正

**修正一：`AGENT-ROADMAP-DSH-ALIGNMENT.md` 的"MCP 缓做"依据不成立。**

文档 §4.4 判定"等阶段二完成后再评估 —— 届时只需加一个 `McpToolProvider`，成本极低"。该结论建立在"接入成本几乎为零"的假设上。勘核结果是：**成本主要在地基（§1.2 第 1、5 条），不在 provider**。

**修正二：安全策略不能建在 `ApprovalGate` 上。**

RULES §12.10 现状说明写明：

- `ApprovalGate` 是 **fail-open** —— 无确认通道 / 眼镜端旧版 / 用户超时未响应 → **降级放行**，只有眼镜端显式回 `no` 才拒绝（`ai/approval/ApprovalGate.resolveAsk`）
- **当前没有任何真实工具被登记为 `EXTERNAL_SIDE_EFFECT`** ⇒ 确认闸门对真实工具**处于空转**

⇒ 把第三方 MCP 工具声明为 `EXTERNAL_SIDE_EFFECT` 在**语义上正确**，但在**效果上拦不住任何东西**。MCP 的安全必须靠**连接前的准入控制**，见 §4.4。

---

## 2. 目标与非目标

### 2.1 目标（第一版）

- 连**远程 MCP server**（Streamable HTTP），跑通 `initialize` → `tools/list` → `tools/call`
- 工具进现有 function-calling 链路：模型能看见、能调用、结果能回填
- 每个工具独立开关；每个 server 独立开关与信任标记
- 断线时**不下发 schema**（不让模型调不存在的工具）

### 2.2 明确不做（第一版）

| 项 | 理由 |
|---|---|
| **stdio 传输** | 需借 Termux `RUN_COMMAND`（`platform/ShellOps.kt:30`）起进程，还要管存活（ROM 冻结）、依赖安装、权限引导。技术可行（Ollama 就是这么跑的），但属**独立一档工作量**，见 §7.1 |
| **Lab 作为 MCP server**（反向） | 方向相反：是让桌面 AI 控制手机。已有先例可复用（`ai/AiuiProject.kt:252` `AixHttpServer`），但属另一件事 |
| MCP `resources` / `prompts` / `sampling` | 第一版只做 tools |
| `notifications/tools/list_changed` 推送 | 第一版用「设置页手动刷新」代替 |
| OAuth 授权流 | 第一版只支持静态 header（token 手动填） |

---

## 3. 形态选型

| 形态 | 传输 | 进程管理 | 依赖 | 成本 | 结论 |
|---|---|---|---|---|---|
| **A. 远程 HTTP 客户端** | Streamable HTTP + SSE | 无 | 无（OkHttp 已有） | **低** | ✅ **第一版做这个** |
| B. Termux stdio 宿主 | stdio | **有**（存活/冻结/重启） | Termux + node/python + 用户授 `RUN_COMMAND` | 中高 | 后续可选 |
| C. 反向 MCP server | HTTP server | 有 | 无 | 中 | 不做（另一个需求） |

选 A 的关键理由：**唯一新增的复杂度是"工具集是动态的"，而这部分复杂度无论选哪个形态都躲不掉**（B 只是在此之上再加进程管理）。先把 A 做通，B 的子进程管理是可加的。

---

## 4. 设计

### 4.1 地基 A：`ToolEntry` 支持动态文案

`ai/tools/ToolEntry.kt` 加两个**带默认值**的可选字段（不影响现有 12 个 provider 的任何构造调用）：

```kotlin
/** 非空则优先于 [displayNameRes]：动态工具（MCP）没有编译期资源 ID */
val dynamicName: String? = null,
/** 非空则优先于 [descriptionRes]；⚠️ 会经 ToolGateway.listAllowedJson() 下发 AIUI 页面 */
val dynamicDescription: String? = null,
```

配套：

- `ai/ToolRegistry.kt:236` `toMeta()` 里给出"动态优先、资源兜底"的取值逻辑；`ToolMeta` 需要能承载字符串名/描述（现在只有 `Int` 资源 ID，见 `:180-197`）
- `ToolCategory` 加 `MCP(R.string.ai_tool_cat_mcp)`
- ⚠️ **新增 string 资源必须同步 `res/values-en/strings.xml`**，否则 `checkI18nKeysSynced`（`build.gradle.kts:301`）在 `preBuild` 直接拦构建
- ⚠️ 注意**前缀撞车**：`ai_tool_cat_*` 家族已存在，新 key 起名前先 grep

### 4.2 地基 B：`ToolRegistry` 派生视图化（**本方案的主体**）

#### 4.2.1 设计原则

**对外 API 签名零改动，只改内部求值时机。** 这些表被设置页、闸门、子代理、AIUI 网关广泛读取，改签名会引发大面积编译错；改求值时机则调用方完全无感。

#### 4.2.2 具体改法

引入"工具集版本号 + 惰性派生 + 版本失效"：

```kotlin
/** 工具集版本：静态 providers 装载后为 0；MCP 工具集每次变化 +1 */
@Volatile private var tableVersion: Int = 0

/** 动态 provider 注册表（MCP），与静态 providers 分开持有 */
@Volatile private var dynamicProviders: List<ToolProvider> = emptyList()

private val allProviders: List<ToolProvider>
    get() = providers + dynamicProviders          // providers 仍保持原静态 listOf

private class Derived(val version: Int, val entries: List<ToolEntry>, val byName: Map<String, ToolEntry>)
@Volatile private var derivedCache: Derived? = null

private fun derived(): Derived = derivedCache?.takeIf { it.version == tableVersion }
    ?: Derived(tableVersion, allProviders.flatMap { it.tools() }, ...).also { derivedCache = it }
```

逐项对照：

| 现有 | 改为 |
|---|---|
| `:115` `entries`（`val`） | `derived().entries`（惰性 + 版本失效） |
| `:119` `entryByName`（`val`） | `derived().byName` |
| `:147` `SIDE_EFFECT_TOOLS`（`val`） | `get() = derived().entries.filter { it.sideEffect }...` |
| `:219` `GLASSES_REQUIRED_TOOLS`（`val`） | 同上，改 `get()` |
| `:227` `toolList`（`val`） | 改 `get() = derived().entries.map { it.toMeta() }` |
| `:123` `allToolNames()` / `:126` `riskOfOrNull()` | 走 `derived()` |
| `:417` `execute()` 分发 | 走 `allProviders.firstOrNull { name in it.toolNames }` |
| `:321` `schemaCache` | key 从 `Set<String>` 改成 `Pair<Set<String>, Int>`（**domains × tableVersion**） |

> ⚠️ **`schemaCache` 的 key 必须带版本号**。否则 MCP 工具连上了、`toolList` 也更新了，但 `schemasFor` 命中的还是旧缓存 —— 表现是"设置页看得见新工具，模型却永远不调"，且**不报错**。这与 `list_glasses_apps` 静默失效是同一类事故。

> ⚠️ **`providers` 的声明位置必须保持早于派生表**（`ToolRegistry.kt:90-91` 已有警告：`object` 属性按文本顺序初始化）。改惰性后这个约束**依然适用于 `providers` 本身**，不要顺手把它挪到文件下方。

#### 4.2.3 对外接口：显式刷新口

```kotlin
/** MCP 工具集变化后调用；tableVersion +1 使所有派生表与缓存失效 */
internal fun refreshTools() { tableVersion++ }
```

#### 4.2.4 域装配

- 加 `const val DOMAIN_MCP = "mcp"`，并加进 `DOMAIN_ALL`（`:53-57`）
- `schemasFor`（`:328`）在"域 ∩ 开关"过滤之后，对 `DOMAIN_MCP` 追加一道**活跃性过滤**：只保留"所属 server 当前已连接且已启用"的工具
- 挂靠方式建议用**工具名前缀解析**（§4.3.2），不在 `ToolEntry` 上加 server 字段 —— 避免改动 12 个 provider
- ⚠️ 连不上 server 时该域**必须产出空集**，否则模型会去调不存在的工具，白耗一轮

### 4.3 MCP 本体

#### 4.3.1 `McpToolProvider`

`McpToolProvider` 与其它 provider 同形，但两个成员是**动态 getter**：

```kotlin
internal object McpToolProvider : ToolProvider {
    override val toolNames: Set<String> get() = McpRegistry.activeToolNames()
    override fun tools(): List<ToolEntry> = McpRegistry.activeEntries()
    override fun execute(context: Context, name: String, args: JSONObject): String =
        McpRegistry.call(name, args)
}
```

接口声明是 `val toolNames: Set<String>`，实现用 `get()` 是合法且必须的（否则快照又回来了）。

注册进 `ToolRegistry.providers`（`:93` 的 listOf 末尾）—— 这是**唯一需要改的既有文件行**。

#### 4.3.2 工具命名与反向映射

OpenAI 的 function name 只允许 `[A-Za-z0-9_-]` 且长度有限，而 MCP 工具名相对自由。需要 sanitize + 反向表：

- 格式：`mcp__<serverId>__<sanitizedTool>`
- 规则：非法字符 → `_`；超长则截断 + 短哈希
- 反向表常驻内存：`wireName → (serverId, originalName)`
- ⚠️ **必须做撞名检测**：两个 server 的同名工具、或 sanitize 后碰撞 → **拒绝注册并告警**，不能静默覆盖（否则模型调 A 打到 B）

#### 4.3.3 schema 搬运

MCP `tools/list` 返回 `{name, description, inputSchema}` → OpenAI 需要 `{type:"function", function:{name, description, parameters}}`：

- `inputSchema` 直通为 `parameters`；建议剥掉 `$schema` 字段
- `inputSchema` 缺失 / 非 object / 为 `null` → 兜底 `{"type":"object","properties":{}}`（与 `ToolRegistry.kt:395` 的既有兜底一致）
- `description` 可能缺省 → 给兜底文案，否则模型看不到用途
- ⚠️ **schema 体积要设上限**：每个工具的 schema 约 200–400 token，10 个工具就是 +2–4k input token，直接拖慢首字延迟。项目已经为此做过优化（`SESSION_AIUI_DOMAINS` 每轮少发 14 个工具），MCP 必须同样克制 —— 建议设单 server 工具数上限 + 按需装配（先只下发名字与简述）

#### 4.3.4 传输层

Streamable HTTP 最小流程：

| 步骤 | 请求 | 要点 |
|---|---|---|
| 1 | `initialize`（`protocolVersion` / `capabilities` / `clientInfo`） | 响应头带 `Mcp-Session-Id`，后续请求要回填 |
| 2 | `notifications/initialized` | 通知类，无 id |
| 3 | `tools/list` | 可能分页（`nextCursor`），要循环取完 |
| 4 | `tools/call` `{name, arguments}` | 响应 `{content:[...], isError}`，需把 content 拼成文本回填模型 |

- 响应可能是 `application/json`，也可能是 `text/event-stream` → 后者复用 `HttpClient.postSse`（`util/HttpClient.kt:155`）
- ⚠️ **JSON-RPC 的 `id` 必须与响应配对**，不能假设"下一个响应就是我要的"
- ⚠️ **超时**：`execute()` 是同步签名且被 `ReadOnlySubagent` 同步调用（§1.2 第 4 条）⇒ 必须在 provider 内设硬上限（参考 `LocalOllamaManager` 的激进探测：连接 1s / 读 2s，MCP 可放宽到连接 3s / 读 15s），**超时返回错误文本给模型，不要抛异常**（抛异常会让整个工具轮次失败）
- ⚠️ 协议版本不匹配时按规范断开并明确报错，不要硬撑
- ⚠️ **执行中 server 掉线**要优雅失败（返回错误文本），不能挂住主循环

### 4.4 风险与准入控制（**不是审批闸门**）

如 §1.3 修正二：`ApprovalGate` fail-open + 闸门空转 ⇒ **不能把 MCP 的安全建在它上面**。正确做法是**前置准入**：

| 层 | 措施 |
|---|---|
| 默认态 | MCP 功能**默认关闭**；server 列表初始为空 |
| **地址准入** | **https-only；唯一例外＝回环 http**（`localhost` / `127.0.0.1` / `::1`）。判据＝**两份 `network_security_config` 白名单的交集** ⇒ debug/release 行为一致；⚠️ **不要**改成按 `BuildConfig.DEBUG` 放开 http（debug 变体被 `src/debug/` 覆盖成 `cleartextTrafficPermitted="true"`，那样写＝「调试能跑、发布才挂」）。判定**只有一处** = `McpRegistry.isUrlAllowed()`，UI 必须复用它，别自己写 `startsWith`（否则会出现「输入框放行、连接被拒」） |
| 加 server | 用户必须手动填 URL（**显式信任动作**，不存在"自动发现"） |
| 首次连接 | 展示该 server 的工具清单与风险摘要，要求用户确认后才启用 |
| 工具级 | 每个工具独立开关，**默认关**；用户逐个开启 |
| 信任标记 | server 可标为 `trusted` → 其工具 risk 从 `EXTERNAL_SIDE_EFFECT` 降为 `LOCAL_SIDE_EFFECT`（避免走确认通道拖慢）；未标记则保持 `EXTERNAL_SIDE_EFFECT` |
| 单 server 闸门 | 加频率上限（参考 `ai/approval/ApprovalGate` 里 AIUI 页面 30/分钟的做法），防单 server 刷爆 |
| 危险工具 | MCP 侧维护**工具级允许清单**：命中高危命名（如 `delete_*` / `exec_*`）默认拒绝注册，需用户显式解锁 |

> ⚠️ 第一版**不要**试图用 `ToolRisk` 区分"MCP 危险工具" —— 现有三档（`ai/ToolRisk.kt:21-30`）是按"副作用范围"分的，表达不了"这个第三方工具会不会删我远端仓库"。这是 MCP 侧自己该有的维度。
>
> ⚠️ 另注意：未登记风险档的名字兜底是 `EXTERNAL_SIDE_EFFECT`，且会在到达闸门前被 `UnknownToolGuard` **单调拒绝** —— 动态工具务必确保 `riskOfOrNull()` 能查到（即 `derived()` 里真实存在），否则会被当成幻觉工具拒掉。

### 4.5 配置与设置页

**持久化**（照 `files/chat_sessions.json` 的既有惯例）：

`files/mcp_servers.json` —— 每条 `{id, name, url, headers?, enabled, trusted, addedAt}`

- ⚠️ **token 明文问题**：header 里可能含密钥。至少要在文档/UI 上明示，不要写进日志（项目有 `LogCollector`）。若要加密，项目已有 bouncycastle（`build.gradle.kts:477`）可用
- ⚠️ **不要**用 `getExternalStoragePublicDirectory`（memory 有明确踩坑记录），写内部 `files/` 即可

**设置页**：

- 入口按项目约定放「**乐奇聊天 → 工具**」页
- 形态：server 列表 → 每行显示连接状态 + 工具数 → 展开后逐工具开关（复用既有 `tool_enabled_<name>` pref，`:34-35`）
- 新增分类 `MCP`（§4.1）
- ⚠️ 需确认 `isEnabled`（`:293`）对**未知工具名**的默认返回值。MCP 工具第一次出现时 prefs 里没有它的 key —— 若默认 `true`，等于"连上就全开"，与 §4.4「默认关」冲突，需要显式覆盖

**UI 文案**：所有新串必须**中英双语**（`checkI18nKeysSynced`）

### 4.6 回归脚本豁免 —— **已实现**

`check_tool_wiring.py` 双向核对"六张派生表 vs 各 provider 声明"。`McpToolProvider` 的 `toolNames` 是运行时值 ⇒ 必然不一致 ⇒ 误报。

⚠️ 但**不能靠"认不出就跳过"来豁免**：脚本原实现对解析不出 `toolNames` 的 provider 是 `continue` 静默跳过，
那正是它自己最怕的失效模式（漏报）。因此落地方式是**显式识别 + 换断言对象**：

1. 新增 `DYNAMIC_RE`：匹配 `override val toolNames … get() = <接收者>.<成员>`（getter 形式）；
2. 命中即标为动态 provider，断言三件事 —— ① `tools()` 也是 `get() = <接收者>.<成员>` 的简单委托；
   ② 两个接收者**相同**（否则"设置页有、模型调不到"）；③ 两个成员**不同**（同成员在类型上不可能
   同时是 `Set<String>` 与 `List<ToolEntry>`）；
3. 兜底分支从 `continue` 改成**报错**：认不出的 `toolNames` 写法必须显式说明，不能再静默漏查；
4. 顺手显式跳过接口文件 `ToolProvider.kt`（否则会被上面的兜底分支误报）；
5. 输出里单列"另有动态 provider N 个"，并注明派生表**不含**它们的工具。

实测输出（2026-09-20）：`provider 数 = 13，声明工具数 = 39（另有动态 provider 1 个）` / `结论：接线完整 ✅`。

---

## 5. 分步实施

**每一步都必须可单独合入、可单独验证、可单独回滚。**

| 步 | 内容 | 验证 | 回滚 |
|---|---|---|---|
| **S0** | `ToolEntry` 加动态文案字段 + `ToolCategory` 加 `MCP` + 双语 string | 编译 + `preBuild` 的 `checkI18nKeysSynced` 通过 | 纯新增字段，删掉即可 |
| **S1** | `ToolRegistry` 派生表惰性化 + 版本号 + `schemaCache` key 加版本 | 编译 + 单测 + `check_tool_wiring.py` 全绿。**行为应与改动前完全一致**（此时没有动态 provider） | 还原 `ToolRegistry.kt`（单文件） |
| **S2** | `McpToolProvider` 空实现（`toolNames` 恒空集）+ 注册进 `providers` + 脚本豁免 | `check_tool_wiring.py` 通过；模型侧看不到任何新工具 | 从 `providers` 移除一行 |
| **S3** | `McpClient`：`initialize` / `tools/list` / `tools/call` + JSON-RPC 配对 + 超时 | 单测（JSON-RPC 配对 / 命名 sanitize 反解 / schema 搬运 / SSE 分帧） | 新增文件，隔离 |
| **S4** | `McpServerStore` + 设置页 UI（列表 / 添加 / 开关 / 状态） | 编译 + 双语 key 校验 | 设置页入口摘掉 |
| **S5** | 端到端：接一个真实 server，跑通一次工具调用 | 见 §6 真机步骤 | 关掉 server 开关 |
| **S6** | 文档同步：RULES §12.10、DEV_GUIDE §7、`AGENT-ROADMAP-DSH-ALIGNMENT.md` 的 MCP 结论 | 人工审阅 | — |

> **S1 是风险最集中、也最有独立价值的一步**。它不引入任何新功能，只换求值时机 —— 建议单独一次提交、单独回归，不要和 S2 混在一起。

---

## 6. 验证方案

**编译级**（每次改动）：

```bash
cd D:\rokidapp\cxrl\RokidLab
.\gradlew.bat :cxrl:RokidLab:phone-app:compileDebugKotlin
```

**回归脚本**（S1–S3 每步都跑）：

```bash
python skills/rokidlab-chat-standalone-mode/scripts/check_tool_wiring.py
```

**单测**（纯 Kotlin，无需真机，`unitTests.isReturnDefaultValues = true` 已开）：

- JSON-RPC `id` 与响应配对（乱序响应 / 通知无 id / 错误响应）
- 工具名 sanitize 与反解（含撞名拒绝、超长截断）
- `inputSchema` → `parameters` 搬运（缺省 / null / 非 object 兜底）
- `schemaCache` 版本失效（改了 `tableVersion` 后必须重算）

**真机验证（由用户执行）**：

1. 装包 → 进「乐奇聊天 → 工具」→ 添加一个 MCP server，确认状态灯变绿、工具列表出现
2. 逐个开启 1–2 个只读工具，回到聊天页问一个必须用到该工具的问题
3. 预期：过程时间线里出现工具调用步骤；回答内容基于工具返回结果
4. 断网 / 关掉 server → 再问同一问题 → 预期：**不下发该工具**，模型正常回答或用别的工具，**不出现"调用失败"**
5. 关掉单个工具开关 → 预期：该工具从可见列表消失，模型不再尝试调用

---

## 7. 风险与未决问题

### 7.1 已识别风险

| 风险 | 说明 | 缓解 |
|---|---|---|
| **`schemaCache` 版本失效漏改** | 表现是"设置了但模型永远不调"且**不报错**，最难查 | S1 加专门单测；`check_tool_wiring.py` 兜底 |
| **`ToolRegistry` 初始化时序** | 原本 `val` 在 `object` 初始化即就绪，改惰性后变首次访问才构造 → 若有人依赖"类加载后立刻可用"会踩到 | S1 单独回归；保留 `providers` 的声明位置 |
| **token 成本失控** | MCP 工具 schema 直接进每轮请求，10 个工具 +2–4k input token，拖慢首字 | 工具数上限 + 按需装配 + 默认关 |
| **撞名静默覆盖** | sanitize 后碰撞 → 调 A 打到 B，属"看起来能用但结果错" | 注册期硬拒绝 + 告警 |
| **安全被高估** | 开发者容易以为"声明了 `EXTERNAL_SIDE_EFFECT` 就安全了"，实际 fail-open | §4.4 前置准入；RULES 里写明 |
| **`execute()` 同步阻塞** | 网络耗时进主循环，慢 server 会卡住整轮对话 | provider 内硬超时 + 返回错误文本而非抛异常 |

### 7.2 待确认（动手前需核实）

1. `subagent/ReadOnlySubagent.kt:154` 的执行线程是否为 Main（决定 MCP 调用能否直接阻塞）
2. `ToolRegistry.isEnabled`（`:293`）对未知工具名的默认值（决定 §4.5 的"默认关"要额外做多少）
3. `ToolGateway.listAllowedJson()` 的消费端（AIUI 页面）是否依赖 `descriptionRes` 做本地化 —— 若是，动态文案要确认能正常显示
4. 眼镜端"本机模式"下 MCP 工具的去留（`GLASSES_REQUIRED_TOOLS` 的语义是"没眼镜做不成"；MCP 属于"不需要眼镜"⇒ 应**保留**，需在 `ToolEntry.requiresGlasses = false` 下确认）

### 7.3 后续可选（不在本方案内）

- **stdio 传输**：借 `platform/ShellOps.runTermuxCommand`（`:30`）起本地 MCP server。技术上可行（Ollama 已证明），但需处理进程存活（ROM 冻结 → 参考 `LocalOllamaManager.wakeTermux`）、依赖安装（node/python）、`RUN_COMMAND` 权限引导（`LocalModelPage.kt:226` 有现成 UI 先例）
- **Lab 作为 MCP server**：复用 `ai/AiuiProject.kt:252` `AixHttpServer` 的 HTTP server 经验

---

## 8. 附：一句话给评审

**如果只能做一件事**，做 S1（`ToolRegistry` 派生视图化）—— 它零功能变更、风险可完全回归，且做完之后 MCP 真的就只是"加一个文件"。**如果 S1 不做而直接上 MCP**，会得到一套"接线在两个地方各自维护"的新技术债，比现在更糟。
