# 乐奇聊天 Agent 能力演进方案（对齐 DeepSeek Harness）

> 参照对象：[deepseek-ai/deepseek-harness](https://github.com/deepseek-ai/deepseek-harness)（MIT，`dsh` 0.1.0-rc）
> 对照对象：RokidLab `phone-app` 的乐奇聊天
> 日期：2026-09-19

---

## 0. 一句话结论

DSH 的价值**不是功能清单**，而是两组架构约束：**能力可插拔（Capability Seam）** 与 **全程可回放（Model-visible means logged）**。

RokidLab 不缺功能——技能系统（渐进披露）、三层记忆、BM25 本地 RAG、14 轮工具循环、`update_plan`、长任务 checkpoint，这些在 DSH 里也是以插件形式存在的，你甚至已经独立走到了同一个设计上。

RokidLab 缺的是**组织方式**：改一个工具要同步六张表，模型看到的每一字节不可回溯，压缩阈值硬编码在 `AgentSessionHistory` 里。

所以方案分三层：**结构层（接缝化）→ 数据层（事件流）→ 能力层（补齐缺口）**。

**⚠️ 最重要的判断：不要照搬 DSH。** DSH 有 32 个能力接缝，是因为它要同时支撑 web / headless / sdk / acp 四种宿主。RokidLab 只有一个宿主（Android App），**真正需要的接缝是 4 个**：`tools` / `llm` / `compaction` / `approval`。过度接缝化是负资产。

---

## 1. DSH 值得借鉴的五条架构不变量

### 1.1 Everything is a plugin（Cordis 微内核）

> "There is no privileged core to patch."

模型适配器、工具注册表、会话日志、**Agent 循环本身**，全都是可替换插件。扩展方式是往 profile 里加一行 bundle，不改源码。

关键机制：插件向共享 `context` 贡献 **services / typed events / reversible effects**，效果在插件卸载时自动 `unwind`。

分层组合顺序：`bundle（按序）→ profile patch → home patch → --patch 覆盖层`。

### 1.2 Capability Seams（能力接缝）三件套

一个 seam 必须同时具备三个角色，缺一不成缝：

1. **Service Definition** —— 声明接口（如 `ctx.compaction`）
2. **Service Provider** —— 实现（如 `dsh-compaction-basic`）
3. **Consumer** —— 使用方，通常是面向模型的工具

DSH 有 32 个 seam。对本项目有参照意义的几个：

| ctx key | 拥有包 | 对 RokidLab 的意义 |
|---|---|---|
| `ctx.tools` | `core/tools` | **最高优先级**，见 §4.2.1 |
| `ctx.llm` | `llm` | 模型适配 + 能力探测，见 §4.2.2 |
| `ctx.compaction` | `compaction` | 上下文压缩，见 §4.2.3 |
| `ctx.approval` | `user-approval` | 审批，见 §4.2.4 |
| `ctx.skills` | `skill` | 已有等价物（`SkillRegistry`） |
| `ctx.subagents` | `subagent` | 见 §4.3.2 |
| `ctx.jobs` | `jobs` | 已有雏形（`AgentTaskStore`） |
| `ctx.attachments` | `attachment` | 图像理解开关已起步 |
| `ctx.fs` / `ctx.shell` / `ctx.sandbox` | `fs`/`shell`/`sandbox` | **不适用**，见 §4.4 |

### 1.3 "Model-visible means logged"

这是 DSH 最硬的一条运行时不变量：

> 任何到达模型请求的内容必须可从日志重建；新模型可见输入必须有对应的 session event。

由此：

- **Append-only `SessionEvent` 日志是唯一真相源**，`deriveMessages()` 从日志投影出模型历史
- 事件分三类：**Session events**（持久事实，重载后存活）/ **Agent events**（`agent/*`，飞行中工作）/ **Capability events**（`fs/*`、`tools/*` 等）
- `assistant/message` 内嵌产生内容的精确紧凑定时流；`assistant/attempt` 保留失败/重试/取消的尝试，**不进模型历史**
- 由此 Resume / Fork / Search / Replay **全都在同一条事件流上实现**
- 格式版本化：JSONL v0 用 `session.jsonl`，v1+ 用 `session.vN.jsonl`，**代际路径永不改名/替换/删除**

而且这条不变量是**对模型开放的**：`session_event_read` / `session_event_search` / `session_event_trace` / `session_search` / `session_trace` 五个只读工具，让模型能查自己的历史。

### 1.4 压缩 / 审批都是"缝"，不是代码里的 if

**压缩**（`ctx.compaction`）三个入口：

| 入口 | 触发点 | 语义 |
|---|---|---|
| `compactIfNeeded(agent, 'pressure')` | `agent/pre-step`，**请求推导之前** | 日常压力 |
| 溢出恢复 | `agent/request-error` | 失败步骤关闭后重试 |
| `compactNow(agent)` | 轮次之间（维护任务） | 手动，低于阈值也压缩 |

实现细节值得抄：

- **锁语义**：`compaction/start`（`turn` 为 `null` 表示独立手动）→ 摘要化 → `compaction/summary` → 落地 → `compaction/end`。**锁包围整个操作**，中途崩溃留下孤儿锁（可检测），而不是假的"完成"
- **三个事件都是 log-only**，不进模型表面
- **唯一的表面变更是**一条携带 `surfaceOp: { op: 'replace', startSeq, endSeq }` 的 `user/message`
- **范围必须保持 tool-call/result 配对平衡**（`toolPairingBalancedBefore/After`），但**不要求保持完整轮次** —— 所以单个超大轮次的早期步骤也能被压缩
- 可选 `ctx.toolResultPruner`：确定性剪枝，不产生摘要

**审批**：从 `tools/pre-execute`（waterfall）返回 `{ kind: 'deny' | 'ask' }`，`ask` 通过 `ctx.approval` 拿回答。用 `ctx.tools.guard()` 做**单调最终拒绝**（不可被后续监听者翻案）。

### 1.5 Feature → 机制表（每个功能 = 一个扩展点上的监听器）

DSH 把"微内核"这个说法做成了**可检验的**：每个产品功能都能指到具体的扩展点上，**没有一行修改循环**。

| 产品功能 | 机制 |
|---|---|
| Hooks（用户级 + 项目级） | 监听 `agent/created` / `agent/pre-step` / `agent/request` / `tools/pre-execute` / `tools/post-execute` / `agent/turn-stopping` |
| Plan mode | `plan/mode` 持久状态 + `exit_plan_mode` 工具 + 用户评审 |
| 上下文压缩 | `ctx.compaction` + `dsh-compaction-basic` |
| 子 agent 委派 | `ctx.subagents` provider 注册表 + `subagent` 工具 |
| MCP | **一个 server 一个插件**：发现工具 → `ctx.tools.register()` |
| Skills | section + 工具注册；调用时 `inject()` 内容 |
| Memory | section provider + 工具 |
| 定时任务 | 插件注册调度工具；定时器触发 → idle 时 `followup()`，忙时 `inject()` 通知 |
| 工具超时/重试/指标 | 用 `tools/execute` 包装核心派发（只有 `exec.signal` 可替换） |
| 最终结果审计 | 用 `tools/result` 观察不可变的权威结果 |
| 会话回放 / trace | `session/event` → JSONL；重放 = `sessions.create(id, { seed })` |

---

## 2. 逐项对照：RokidLab 现在在哪

| 维度 | DSH | RokidLab 现状 | 判定 |
|---|---|---|---|
| 内核 | Cordis 微内核，无特权核心 | `ToolRegistry` object 单例；`ToolProvider` 已按域拆出 11 个 provider | **半成品**，接口只服务 execute |
| 工具注册 | `ctx.tools.register()`，schema 自动进 prompt | `toolList` 唯一入口 + 手工同步 5 处名单 + 4 处文案；`check_tool_wiring.py` 防漏 | **结构性缺口** |
| 模型适配 | `ctx.llm` seam + `LlmAdapter`/`registerAdapter` | `OpenAiService` 直连 OpenAI 兼容端点，无适配层 | **缺口** |
| 会话日志 | Append-only `SessionEvent`，model-visible=logged | `chat_sessions/<id>.jsonl`（聊天记录语义）+ `AgentSessionHistory`（内存态） | **缺口**（语义层级不同） |
| 回放 / fork | Resume / Fork / Search / Replay 同一事件流 | 无 | **缺口** |
| 压缩 | `ctx.compaction` seam，三个入口 | `AgentSessionHistory.trimLocked()` 滚动摘要，阈值 12/6000 硬编码 | **缺口** |
| 审批 | `tools/pre-execute` 返回 ask/deny + `ctx.approval` | `ToolRisk.kt` + `GlassToolConfirmChannel`（眼镜侧确认）+ `AuthorizationService` | **已有雏形**，需收敛单点 |
| 子 agent | `ctx.subagents` + `subagent` / `list_agents` / `send_message` | 无 | **缺口** |
| 后台作业 | `ctx.jobs` + `job_list/output/kill` | `schedule_agent_task` + `AgentTaskStore` | **雏形** |
| 技能 | `ctx.skills` + `skill` 工具 | `SkillRegistry` + `load_skill` / `load_skill_section` | **✅ 思路一致，已领先** |
| 记忆 | section provider + 工具 | `LongTermMemoryManager`（200 条）+ `search_past_conversations` | **✅ 但用户不可见** |
| 知识检索 | `ctx.web` + `ctx.spillStore` | `KnowledgeBase`（BM25 混合）+ `web_search` / `web_fetch` | **✅ 但无溯源** |
| 附件 / 多模态 | `ctx.attachments`；`read_image` 执行时校验"路由模型是否声明 image input" | 「图像理解」开关（同思路：不支持则回退 OCR） | **已起步** |
| 规划 | `todo_write` + `ctx.goals` + plan mode | `update_plan` + `AgentPlan` | **✅** |
| 调度 | `ctx.schedule`（`schedule_create/delete/list`） | `manage_timer` + `schedule_agent_task` | **✅** |
| 运行时内省 | `cordis_inspect_list/query`（Creator mode） | 隐藏的自检 / 日志工具 | **雏形** |
| 会话查询 | 5 个只读工具给模型 | 仅 `search_past_conversations` | **缺口** |

**一句话总结差距分布**：✅ 的项集中在"能力"，❌ 的项集中在"**结构**"与"**可观测**"。

---

## 3. 与之前识别缺口的收敛

此前识别出 12 项缺口。二次收敛后，它们其实归到 **4 个根因**：

| 根因 | 曾表现为 | DSH 给出的答案 |
|---|---|---|
| **R1 没有"事件流"这层数据模型** | 多会话割裂、消息级操作无依据、上下文静默截断、无法回放/溯源 | Append-only `SessionEvent` + session projections + `deriveMessages()` |
| **R2 没有"接缝"这层抽象** | 35 工具硬编码、无 MCP、无模型路由、改工具要同步六表 | `ctx.*` seam（本项目取 4 个） |
| **R3 信任基础设施缺失** | 答案无溯源、记忆不可见不可删、token 无计数 | session telemetry + spill store + 记忆管理 UI + 来源标注 |
| **R4 视觉输入是伪多模态** | 拍照走 `LocalOcr` → 文本 → 模型 | `ctx.attachments` + 路由模型能力校验 |

**R1/R2/R3 恰好是 DSH 已经系统解决过的三项**，这也是本次方案的主干。

补充说明（针对已完成的改动）：

- 多会话（`ChatSessionStore`）与消息级操作（`ChatMessageActions`）已经落地，但它们是**建在"聊天记录"语义上的**。阶段一要把底层语义升级为"事件流"，这两块功能**接口不变、实现换底**，不会推倒重来。
- 上下文用量条（`ChatContextBar`）**已随 1b 改为读事件流投影**（`AgentSessionManager.contextUsage` → `AgentSessionStore`）。
  仍**未**做到的是"额外显示哪几轮被摘要替代了"（对齐 DSH 的 `shadowedSeqs`）—— 数据已经有了
  （`SessionProjection.shadowedSeqs` / `hiddenSeqs` 已分开暴露），但 UI 还没有消费它。

---

## 4. 完整方案

### 阶段一：事件流底座（P0 · 纯重构，不加新功能）

**目标**：把"模型可见即可回溯"从口号变成数据模型。这是后面所有能力的地基。

#### 4.1.1 新增 `ai/session/SessionEvent.kt`

用 Kotlin `sealed interface` 表达 DSH 的 `SessionEventMap → derived-union` 模式（Kotlin 里 sealed class 就是原生等价物，且 `when` 强制穷尽 —— 比 TS 的声明合并更安全）：

```kotlin
sealed interface SessionEvent {
    val seq: Long        // 单调递增，会话内唯一
    val ts: Long
    val turn: Int?       // null = 非轮次事件（如手动压缩）
    val step: Int?
}

data class TurnStart(...) : SessionEvent
data class TurnEnd(val reason: TurnEndReason, ...) : SessionEvent
data class UserMessage(val text: String, val source: MessageSource, ...) : SessionEvent
data class AssistantAttempt(val outcome: AttemptOutcome, ...) : SessionEvent   // 不进模型历史
data class AssistantMessage(val text: String, val stream: CompactTimedStream, ...) : SessionEvent
data class ToolCall(val callId: String, val name: String, val args: JSONObject, ...) : SessionEvent
data class ToolResult(val callId: String, val content: String, val truncated: Boolean, ...) : SessionEvent
data class ContextInject(val kind: String, val content: String, ...) : SessionEvent  // 记忆/知识库/技能注入
data class CompactionStart(val turn: Int?) : SessionEvent
data class CompactionSummary(val shadowedSeqs: List<Long>, ...) : SessionEvent
data class CompactionEnd(val turn: Int?, val error: String?) : SessionEvent
```

**枚举不留退路**：`TurnEndReason`、`MessageSource`、`AttemptOutcome` 都用 sealed，`when` 穷尽 —— 这样调 `CompactionStart(turn)` 时编译器会提醒所有消费点，避免 DSH 文档里提到的"兼容性破坏变更"演化成本。

#### 4.1.2 新增 `ai/session/SessionLog.kt`

- 复用已有落盘位置 `chat_sessions/<id>.jsonl`，但**语义升级**：从"聊天记录"变成"事件流"
- **只追加**，不修改已写行
- 提供 `append(event): Long`（返回 seq）、`read(from: Long): Sequence<SessionEvent>`、`lastSeq(): Long`
- **格式版本化**：文件头或首个事件带 `formatVersion`。对齐 DSH 的"代际不可改名"原则 —— 未来升级时写 `chat_sessions/<id>.v2.jsonl`，旧文件保留
- 迁移：现有 `chat_sessions/*.jsonl`（只有 user/assistant 两种记录）在首次读取时补齐为 `UserMessage` / `AssistantMessage` 事件

#### 4.1.3 新增 `ai/session/SessionProjection.kt`，替换 `AgentSessionHistory` 的内部实现

**关键：保持对外接口不变**，`AgentSessionManager` 的公开方法签名一个都不改（`recordTurn` / `getHistory` / `contextUsage` / `dropLastTurn` / `clear`）。这样调用方零改动，可分步迁移。

> ⚠️ 2026-09-20 修订：`maybeExpire` 已随「空闲自动过期」一起**移除**（理由见 `RULES.md` §12.6），
> 其余签名确实一个都没改；另新增 `clearAll(context)`（清空全部对话的记忆）。

内部改为从事件流折叠：

```kotlin
class SessionProjection(private val log: SessionLog) {
    fun history(): List<ChatMessage>          // = deriveMessages()
    fun usage(): ContextUsage                 // 从事件长度 + 字符数算
    fun shadowedSeqs(): Set<Long>             // 新增：哪几轮被摘要替代
}
```

- `deriveMessages()`：只投影 `UserMessage` / `AssistantMessage`，**跳过** `AssistantAttempt`（失败尝试不进模型历史）、跳过 log-only 的 `Compaction*`
- 压缩策略参数（12 条 / 6000 字符 / 800 摘要字符 / 10 分钟过期）从 `AgentSessionHistory` 的硬编码常量提取为 `CompactionPolicy` 数据类，默认值不变

#### 4.1.4 验收标准

1. 聊 3 轮 → force-stop → 重开 → 对话、工具轨迹、用量**全部**从 `session.jsonl` 重建
2. `session.jsonl` 里能看到 `tool/call` 与 `tool/result` 成对出现
3. 人为写坏最后一行 JSON → 应用仍能启动，只丢最后一条事件（追加式日志的鲁棒性要求）
4. `ChatContextBar` 显示不变（回归）

**落地状态（2026-09-19）**

| 步骤 | 内容 | 状态 |
|---|---|---|
| 1a | 数据层：`ai/session/{SessionEvent,SessionLog,SessionProjection}.kt` + 单测 | ✅ 已完成 |
| 1b | 接线：`ai/session/AgentSessionStore.kt` + `AgentTurn.kt`、`AgentSessionManager` 改读投影 + 会话绑定 + `AiConversationService` 补事件 | ✅ 已完成 |
| 1c | 迁移与真机验收（上面 4 条） | 待真机 |

**1a 实际交付**：事件流是**追加式**的，压缩不再删行 —— 改为追加一条 `CompactionSummary`，
声明"这些 seq 已被摘要覆盖"。**这是整个阶段一最重要的一步**：改造前压缩把旧消息从内存里
删掉，删了就永远回不来；现在它们仍在文件里，只是不进模型上下文。
于是「AI 忘了前面的」（上下文该省就省）与「查不到前面聊了什么」（可回溯）第一次变成
两件能分开回答的事。

**与设计稿的三处收敛**（都是落地时才发现必须这么改的）：

1. **落盘位置独立** —— 写入 `agent_sessions/<id>.jsonl`，**不**与 UI 的 `chat_sessions/<id>.jsonl` 共用。
   设计稿要求复用同一个文件；实际做不到，因为 UI 侧 `ChatStateHolder.persistAll()` 在
   删除/编辑/截断消息时**整份重写**文件（`ChatHistoryStore.rewrite`），会把事件行一并抹掉 ——
   而"只追加"是事件流的核心不变式，和"可编辑的聊天记录"这两个语义放在同一文件里，
   不变式就无法维护。终局仍然是**一份事件流文件**（UI 也改为追加 `MessageEdited`/`MessageRemoved`
   事件、不再 rewrite），但那是阶段一后半的事，届时做一次合并迁移。
2. **`seq` / `ts` 放在 `SessionRecord` 上**，不放 `SessionEvent` 上（设计稿把两者混在事件里）。
   它们是**日志的属性**；拆开后 `append` 不必回填事件字段，"同一事件被记录两次得到不同 seq"
   在类型上就不可能搞错。
3. **事件不带 `CompactTimedStream`** —— 我们的流式只有"累积整串"一种形态（`appendAiDelta`），
   没有分段语义，时间信息由 `ts` 与 `ToolCall`/`ToolResult` 承载已经够用；先不引入一个没人消费的结构。

**1b 实际交付**（2026-09-19）

新增两个文件 + 改两个主文件：

| 文件 | 内容 |
|---|---|
| `ai/session/AgentSessionStore.kt` | 读写门面：三层职责分明（`SessionLog` 字节层 / `SessionProjection` 语义层 / 本类**动作层**）。把"改变历史"的四个动作统一翻译成**追加声明**，并做压缩 → `shadowedSeqs` 的反向映射 |
| `ai/session/AgentTurn.kt` | **一轮的记录句柄**（由 `beginTurn` 产出）。句柄在创建时抓住会话，`historyForRequest()` / `compactNow()` / `finish()` 都挂在它上面 |
| `ai/AgentSessionManager.kt` | 从"全局内存单例"改为"**会话级 + 事件流驱动**"：`init(ctx)` / `bindSession(id)` / `forgetSession(ctx,id)`；`AgentSessionHistory` 已删除 |
| `store/ChatStateHolder.kt` | bootstrap / `newSession` / `switchTo` 后 `bindSession`，`deleteSession` 时 `forgetSession` |
| `app/LabApplication.kt` | `AgentSessionManager.init(this)`（**必须先于** `ChatStateHolder.init`） |
| `domain/AiConversationService.kt` | 主循环补 `TurnStart` / `UserMessage` / `ContextInject`×5 / `ToolCall` / `ToolResult` / `AssistantAttempt` / `TurnEnd`；工具记录收在 `runTool` 包装里（原函数体改名为 `executeTool`，零结构改动） |

**1b 的六处设计决策**（都是落地时才暴露出来的，逐条都有真机症状兜底）：

1. **第 12 个事件类型 `VisibilityCut`**（设计稿只列了 11 个）。改造前有两个动作**直接改内存历史**，
   而事件流只能追加：丢弃最后一轮（`dropLastTurn`），以及整段清空（原先的「10 分钟无活动清空」`maybeExpire`，**已于 2026-09-20 移除**）。
   它们的追加式表达就是"声明这些 seq 从此不进模型上下文"。`clearDigest` 区分两种语义：
   过期必须连滚动摘要一起清（否则模型带着"更早对话摘要"继续聊，看起来像没清干净），
   丢弃最后一轮则保留摘要（更早的记忆仍然有效）。
2. **`shadowedSeqs` 与 `hiddenSeqs` 分开暴露** —— "被压掉了"与"作废了"对用户的解释完全不同，
   混在一起以后就分不出"上下文变小"是哪种原因。
3. **请求装配要排除进行中的那一轮**（`historyForRequest(excludeTurn)`）。用户消息在轮次**一开始**
   就落盘（崩溃也不丢"用户问了什么"），但它进请求时要单独构造 —— 多模态时 content 是
   `text + image_url` 分片数组，而 base64 不落盘。不排除就会在请求里出现**两条 user**。
   顺带把溢出恢复修对了：重建时读到的是"压缩**之后**"的历史，而不是冻结在轮次开始时的旧快照
   （否则压完再发一次还是原来那条超长请求）。
4. **记录句柄 `AgentTurn` 而不是"全局单例 + 轮号"**。句柄抓住会话，所以"生成到一半用户切会话"
   不会把这一轮写进新会话的时间线 —— 这正是"切会话 AI 不失忆"那个老 bug 的镜像面。
5. **四个改变历史的动作，只有两个是真删**：用户显式清空（`wipe()`，删文件）与会话被删除。
   自动发生的（10 分钟过期）一律走**声明式**裁剪，轨迹保留 —— 那只是**推断**（用户可能只是
   离开了一会儿），而轨迹有回溯价值。
6. **`AgentSessionManager` 由 `public` 收成 `internal`**（它返回 `AgentTurn`，内部类型不能出现在
   公开签名里）。调用方全在同一模块，零改动。

**已知限制（本次未做，留作后续）**：`ChatScreen.resendEdited` 编辑**中间**那条消息时，
UI 会截断到该条之前，但记忆只丢**最后一轮**（`dropLastTurn` 的老行为）。
要修需要把 UI 消息与轮号建立映射 —— 而拍照答题等 `recordHistory=false` 的轮不进事件流，
两侧轮数并不天然相等，硬映射会出错。等 UI 侧也改为"追加事件"（与第 1 条收敛的终局同一件事）再一并解决。


> ⚠️ 落地时踩到的坑（值得记）：`ContextInject` 的"注入来源分类"字段最初也叫 `kind`，
> 与 `SessionEvent.kind`（事件类型）撞名，编译器报 `hides member of supertype`
> 并连带把 `JSONObject.put` 变成重载歧义。已改名 `origin`。

---

### 阶段二：四道接缝（P0/P1 · 改造成本最高，收益最大）

#### 4.2.1 `tools` 接缝 —— 优先级最高

> **✅ 已落地（2026-09-19）**。与设计稿的差异：`ToolEntry` 把 12 个字段**内联在一个结构体**里
> （不是拆成多个注册面），并把 `statusText` / `summarize` 也一并收进来 ——
> 六张表全部由 `providers.flatMap { it.tools() }` 派生，`ToolRegistry` 退化为纯聚合器 + 分发器。
> 回归脚本 `check_tool_wiring.py` 已从"防漏检查"升级为"回归测试 + 防回退"
> （含"禁止再出现手写 `toolList` 字面量"一条）。

**现状**：`ToolRegistry.toolList` 是唯一入口，每个工具要在 `toolList` / `GLASSES_REQUIRED_TOOLS` / `SIDE_EFFECT_TOOLS` / `ToolRiskMap` / `statusText` / `GlassToolConfirmChannel.summarize` 六张表登记，另有 4 处模型可见文案要同步。`check_tool_wiring.py` 存在的理由就是这个流程有多脆。

**改造**：把 11 个 `ToolProvider` 从"执行分支的容器"升级为"能力的完整声明者"。

```kotlin
interface ToolProvider {
    val toolNames: Set<String>                 // 保留
    fun execute(context: Context, name: String, args: JSONObject): String  // 保留
    fun tools(): List<ToolEntry>               // 新增：自声明
}

data class ToolEntry(
    val name: String,
    val group: String,           // 域（装配用）
    val category: ToolCategory,  // 设置页分类
    val hidden: Boolean,         // 只隐藏 UI，仍下发模型
    val risk: ToolRisk,          // 副作用等级
    val sideEffect: Boolean,     // 是否幂等（决定失败重试）
    val requiresGlasses: Boolean,// 本机模式是否摘除
    val statusText: (args: JSONObject) -> String,  // 过程时间线文案
    val schema: JSONObject,      // 从 ToolSchemas.kt 迁到各 provider
)
```

**收敛点**：`ToolRegistry` 变成纯注册表 + 分发器，六张表全部由 `tools()` 聚合生成。

**收益**：
- `check_tool_wiring.py` 从"防漏检查"降级为"回归测试"（检查仍然保留，但不再有人需要靠它救火）
- **MCP 只需再加一个 `McpToolProvider`**，不影响任何现有文件
  （⚠️ 后半句成立的前提是先把 `ToolRegistry` 的派生表动态化 —— 见 [MCP-INTEGRATION-PLAN.md](./MCP-INTEGRATION-PLAN.md) §4.2）
- 工具 schema 从 `ToolSchemas.kt`（一个 900+ 行的巨型 `when`）分散到各 provider，就近维护

**迁移策略**（重要）：**逐域迁移，每迁一域编译 + 跑一次 `check_tool_wiring.py`**。不要一次性搬完 —— 你之前被 `list_glasses_apps` 静默删掉的事故说明这个文件的回归风险很高。

#### 4.2.2 `llm` 接缝

> **✅ 已落地（2026-09-19）**。实际实现与下面的设计稿有三处收敛，记在这里以免下次又按设计稿重做：
> 1. **不用 `Flow<LlmChunk>` 重写传输层**。RokidLab 的两个"实现"（远程 OpenAI 兼容 / 本地 Ollama）
>    其实是**同一个 `OpenAiService`** 换 baseUrl，差别只在**能力元数据**不在传输协议 ——
>    按"只有一个实现就不要做接缝"的判定标准，重写流式链路是无收益的高风险动作。
>    接缝落在**能力解析 + 客户端构造**上：`LlmRegistry.capabilities()/route()/newService()`。
> 2. **能力的证据分四级**，而不是"preset + probe"两级：
>    `PROBED`（本机实测）> `DECLARED`（服务端自报，本地 Ollama 的 `/api/tags`·`/api/show` 免费且准确）
>    > `PRESET`（内置家族表）> `ASSUMED`（保守默认）。本地端点优先信自报，**不必探测** ——
>    探测要真加载一次模型权重，代价比云端大得多。
> 3. **`supportsImage` 是三态**（`true`/`false`/`null`）。`null` = 未知 ⇒ 允许尝试、失败回退 OCR。
>    这一条是硬约束：把"未知"当"不支持"会把未收录模型的图像开关**无故置灰**，
>    用"我们不知道"惩罚用户。只有服务端明确拒绝才允许下否定结论。
>    同理，探测的"无结论"（超时/限流/鉴权）必须与"拒绝"分开，否则换个 Wi-Fi 就平白丢能力。

**现状**：`OpenAiService` 直连端点，模型名/endpoint 在配置里，没有能力元数据。图像理解开关靠**用户自己判断**"我那个模型支不支持视觉"。

**改造**：

```kotlin
interface LlmAdapter {
    val id: String
    fun chat(messages: JSONArray, tools: JSONArray?, stream: Boolean): Flow<LlmChunk>
}

data class ModelRoute(
    val id: String,
    val provider: String,
    val model: String,
    val contextWindow: Int,
    val supportsTools: Boolean,
    val supportsImage: Boolean,   // ← 图像理解开关直接读这个
    val supportsStreaming: Boolean,
)

object LlmRegistry {
    fun register(adapter: LlmAdapter)
    fun route(): ModelRoute?
    fun capabilities(): ModelCapabilities
}
```

**收益**：
- 图像理解开关从"用户猜"变成"探测后判断"（DSH 的 `read_image` 就是这么做的：*execution refuses unless the exact routed model declares image input*）
- 切模型 / 加本地模型（`LocalOllamaManager` 已有）不再需要改 `OpenAiService`
- 为 `contextWindow` 驱动的压缩阈值提供真实数字（现在是写死的 6000 字符）

**capability probe**：`ModelRoute` 首次使用时探测一次并缓存（发一个最小请求验证 `image_url` 是否被接受）。探测失败就保守回退 —— 与现有"图像理解开关 + OCR 回退"的行为一致。

#### 4.2.3 `compaction` 接缝 ✅ 已落地（2026-09-19）

**现状（改造前）**：`AgentSessionHistory.trimLocked()` 硬编码阈值（12 条 / 6000 字符 / 800 摘要 / 10 分钟），单一入口，且只有一个隐式的"超了就压"。

**改造**：

```kotlin
interface CompactionEngine {
    fun compactIfNeeded(session: SessionProjection, trigger: Trigger, signal: CancellationSignal): CompactionResult?
    fun compactNow(session: SessionProjection, signal: CancellationSignal): CompactionResult?
}

enum class Trigger { PRESSURE, CONTEXT_OVERFLOW }
```

三入口对齐 DSH：

| 入口 | RokidLab 落点 |
|---|---|
| `PRESSURE` | `AiConversationService` 主循环每轮开始前（对应 `agent/pre-step`） |
| `CONTEXT_OVERFLOW` | 模型返回上下文超限错误时（对应 `agent/request-error`），命中则重试 |
| `compactNow` | 手动触发（新按钮 / 工具） |

**必须抄的两点**：

1. **锁包围整个操作**：`CompactionStart(turn=null 表示手动)` → 摘要 → `CompactionSummary` → 落地 → `CompactionEnd`。中途崩溃留下孤儿锁可检测。
2. **压缩范围保持 tool-call/result 配对平衡**（实现 `isPairBalanced()` 校验），但**不要求保持完整轮次** —— 这样单个超大轮次的早期步骤也能压。

**默认实现**：现有"滚动摘要"就是 `BasicCompactionEngine`，行为不变。摘要写入方式改为：追加一条带 `surfaceOp: replace(shadowedSeqs)` 的 `UserMessage` 事件 —— 这是压缩**唯一**的表面变更。

**落地结果**（`ai/compaction/`，4 个文件）：

| 文件 | 职责 |
|---|---|
| `CompactionPolicy.kt` | 阈值数据面：DEFAULT 与改造前逐项一致 + `forWindow(contextWindow)` **只收紧不放宽** |
| `CompactionEngine.kt` | Service Definition：`CompactionTrigger` / `CompactionResult` / `pressure` / `compactIfNeeded` / `compactNow` / `isTurnBalanced` / `digestLines` |
| `BasicCompactionEngine.kt` | 默认实现（原 `trimLocked` 算法逐字搬迁，只是阈值改读 policy） |
| `ContextOverflow.kt` | `CONTEXT_OVERFLOW` 触发条件：**只认输入侧措辞** |

三入口落点：

| 入口 | 实际落点 |
|---|---|
| `PRESSURE` | `AgentSessionHistory.recordTurn` 内（等价于 `agent/pre-step`：下一轮发出去前必然已收敛）；另外 **策略被收紧时立刻压一次** |
| `CONTEXT_OVERFLOW` | `AiConversationService` 轮次循环里 `callModel()` 的 catch：强压历史 → **整体重建 messages** → 原地重试同一轮（只一次） |
| `MANUAL` | 上下文面板「立即压缩上下文」按钮 → `AgentSessionManager.compactNow()` |

**与设计稿的四处收敛**（都是实现时才发现的分歧，记录在此免得后来者以为漏做）：

1. **`isPairBalanced()` 改成 `isTurnBalanced()`。** 我们的**持久历史里没有 tool 消息**（中间态只在单次请求内有效），配对平衡在这个层面无从谈起。等价物是"轮完整性"：user/assistant 严格交替、无孤立消息。真正需要 tool 配对平衡的是**请求内**的 `messages` 数组，那件事用"整体重建"规避（不拆半对），见下条。
2. **孤儿锁**不落盘。DSH 的锁能留孤儿是因为会话日志是持久化的；做这道接缝时 `AgentSessionHistory` 还是纯内存，进程一死历史就没了，落盘孤儿锁是徒劳。进程内并发用现成的 `synchronized` + 纯本地摘要（不发请求）已经够。
   > **状态更新（1b 之后）**：事件流已持久化，这条的**前提条件已经成立**。落盘的孤儿锁现在可做，
   > 但当前已经有更直接的等价物：`SessionProjection.orphanToolCalls()`（有 `ToolCall` 无 `ToolResult`）
   > —— 它比锁更准（锁只能说明"上次在压"，它能说明"上次卡在哪个工具"）。真正的压缩锁留到
   > "摘要改为模型生成"（那时压缩会变成一次跨进程的网络调用）再补。
3. **`CONTEXT_OVERFLOW` 的恢复方式是"重建"而不是"就地删 in-flight 段"。** 就地丢掉最早的 `assistant(tool_calls) + tool` 对会拆散配对、服务端直接 400；把 `messages` 按「system + 压缩后历史 + 当前提问」整体重建是唯一协议合法的收敛方式（代价是本轮已跑的工具要重跑一遍，但比整轮失败好）。
4. **`forWindow` 只收紧不放宽。** 大窗口模型（deepseek 131072 / gemini 1M）仍拿 6000 字符的默认预算 —— 放宽是产品决策，不在本次范围；本次真正修的是"给本地 `num_ctx=4096` 的小模型塞 6000 字符历史必然溢出"这个 bug。UI 上给了 `windowLimited` 提示，避免用户看到"上限怎么变成 983 字符"时找不到原因。

#### 4.2.4 `approval` 接缝 ✅ 已落地（2026-09-19）

> **✅ 已落地**。`ai/ToolPolicy.kt` 已删除，判定点从 4 处收敛到 `ApprovalGate` 1 处。
> 与设计稿的三处收敛：
> 1. **`Ask` 不携带 `confirmChannel`**。确认通道全局只有一个（眼镜端悬浮层），让每个 Ask
>    自带一个只会把 Android 类型拖进数据面、堵死单测。改成 `Ask(prompt)` + 闸门注入的单一 `ConfirmResolver`。
> 2. **"问"与"答"分层**。`Ask` 的产生（guard，无 IO、可单测）与解析（`resolveAsk`，唯一阻塞点）
>    拆成两个函数，把 **fail-open 语义收成一个函数**。改造前它散在 `if` 链里，改一处就可能
>    把 fail-open 误改成 fail-closed —— 表现为"功能被安全策略挡住"。
> 3. **单调拒绝显式化**。`compose` 遇 `Deny` 立即短路（对齐 `ctx.tools.guard()`），
>    第一个 `Ask` 胜出，`null` = 不表态。

**现状**（改造前）：`ToolRisk.kt`（风险表）+ `GlassToolConfirmChannel`（眼镜侧确认）+ `AuthorizationService`（系统权限）三处分散判定，`ToolPolicy.kt` 还有 AIUI 页面 30/分钟的独立限流。

**改造**：收敛为单点决策函数：

```kotlin
sealed interface PreToolDecision {
    data object Allow : PreToolDecision
    data class Deny(val reason: String) : PreToolDecision
    data class Ask(val prompt: String, val confirmChannel: ConfirmChannel) : PreToolDecision
}

fun preExecute(tool: ToolEntry, args: JSONObject, ctx: ToolContext): PreToolDecision
```

三种决策的来源各自保持独立（风险表 / 用户确认 / 权限），但**汇到同一个判定点**。`Deny` 分两类：可被后续策略覆盖的，和**单调最终拒绝**（对应 DSH 的 `ctx.tools.guard()` —— 如"本机模式下眼镜工具一律不许"）。

**收益**：新增工具时风险判定只有一个地方要写，而不是三处。

**落地结果**（`ai/approval/`，6 个文件）：

| 文件 | 职责 |
|---|---|
| `ToolDecision.kt` | 数据面：`ToolSource`（`aiui-page` / `conversation`）+ `DecisionOrigin`（5 个归因）+ `sealed interface ToolDecision`（Allow / Deny / Ask） |
| `ToolGuard.kt` | Service Definition：`ToolGuard`（`id` / `sources` / `evaluate` 可空返回）+ `ToolCallContext`（纯数据，无 Context、无 IO） |
| `PseudoTools.kt` | 伪工具风险档**唯一产地**（`load_skill` / `load_skill_section` / `update_plan` = READ_ONLY、`manage_memory` = LOCAL_SIDE_EFFECT）；`knownToolNames()` = 真实工具 ∪ 伪工具 |
| `PageScope.kt` | AIUI 页面准入域判定 + **文案唯一产地**（`ToolGateway.precheckToolCall` 与审批链共用） |
| `ToolGuards.kt` | 5 个内置 guard，**顺序即求值顺序**：`PageScope`(仅页面) → `UnknownTool` → `SourceRateLimit`(有状态，30/120) → `GlassesDependency`(仅对话) → `RiskApproval` |
| `ApprovalGate.kt` | 唯一入口：`compose()` 纯合成 / `resolveAsk()` 唯一阻塞点 + fail-open 唯一产地 / `preExecute()` 门面 |

调用点收敛：

| 调用点 | 改造前 | 改造后 |
|---|---|---|
| `ToolGateway.call()`（AIUI 页面） | `ToolPolicy.check(SOURCE_AIUI_PAGE, …)` | `ApprovalGate.preExecute(AIUI_PAGE, …)` |
| `AiConversationService.runTool()` | `ToolPolicy.check(SOURCE_CONVERSATION, …)`，且**只在真实工具的 else 分支里**（重试循环内每次重试都重判） | `ApprovalGate.preExecute(CONVERSATION, …)`，提到**重试循环外**只判一次 |
| `AiConversationService.isReadOnlyTool()` | 复制一份判定 + 硬编码 3 个伪工具名 | 委托 `ApprovalGate.isReadOnly()`（与风险表同源） |
| `GlassToolConfirmChannel` | 自己从 `args` 拼摘要 | 实现 `ApprovalGate.ConfirmResolver`，摘要由闸门传入（产地 = `ToolEntry.summarize`） |
| `ToolPolicy.kt` | — | **删除** |

**顺带修掉的真实缺陷**（都是"看着接好了、实际拦不到"）：

- **伪工具原先完全绕过闸门**。`manage_memory` / `load_skill` / `load_skill_section` / `update_plan`
  的 schema 拼在 `buildTools()` 里、执行走 `runTool()` 的前置 `when` 分支，从未经过任何判定。
  接上闸门后同时解决两个隐患：未知名不再触发确认闸门**白等 35s**（`UnknownToolGuard` 直接拒）；
  `load_skill` 这类只读工具不会被风险表的"未知名 = 最保守档"兜底误判成需要眼镜端确认。
- **被拒的调用不再消耗限流配额**。改造前 AIUI 路径的准入校验在限流之前、对话路径根本没有准入，
  两个来源"被拒时扣不扣配额"行为不一致；现在统一为**不扣**（限流排在所有纯判定之后）。

**验收**：`ApprovalGateTest`（**35 例**：合成语义 / 伪工具已知 / fail-open 三态 / per-source 限流 /
页面文案 / 风险表三消费者，是本文件里用例最多的一张回归网）+ `ToolRiskMapTest`（3 例：风险表 +
无人值守只读名单）+ `check_tool_wiring.py`（11 provider / 35 工具 / 接线完整）全绿；
`compileDebugKotlin` 通过；模块全量单测 **290 例 / 23 类 / 0 失败**。

#### 4.2.5 阶段二验收

1. 用 `check_tool_wiring.py` 做双向核对（不只存在性，还要比 schema 内容），全绿
2. **加一个测试工具**：只写一个 `ToolProvider` 文件 + 注册一行 → 设置页出现、模型能调用、无需动任何其他文件（这是"接缝成立"的判定标准）
3. 切一次模型（在线 → 本地 Ollama）不改 `OpenAiService`
4. 图像理解开关在模型不支持时自动置灰并给出原因（不再是用户猜）

---

### 阶段三：能力补齐（P1 · 按 ROI 排序）

#### 4.3.1 会话查询工具族 ✅ 已落地（2026-09-19）

> **✅ 已落地**（3 个新工具，工具总数 35 → 38）。与设计稿的两处收敛：
>
> 1. **`search_sessions` 不新增** —— 已有的 `search_past_conversations` 读的是
>    `ChatStateHolder.readPersistedHistory()`，**本来就是跨全部会话**的（按文件最后修改时间合并）。
>    再造一个"多会话版"只会得到两个行为重叠的工具，模型的选择会变得随机。
>    因此改成**分工**：`search_past_conversations` 管**词法检索**（"哪一轮提到过这个"），
>    新三件套管**结构化读取**（"那次到底发生了什么"）。
> 2. **`session_trace` 的 `sessionId` 可省略**（默认当前会话），`turn` 也可省略（默认最近一个调过工具的轮）。
>    最常见的问法是"你上一轮到底做了什么"，强制传两个 id 只会让模型先去查一遍 `list_sessions`。
>
> 渲染层刻意抽成 `ai/session/SessionDump.kt`（纯函数、可单测），工具只是薄包装 ——
> 同一份"事件长什么样"还要被日志面板与将来的轨迹视图（§4.3.5）复用。
>
> ⚠️ 这三件套**不对 AIUI 页面开放**（`PageScope.DENY_TOOLS`）：页面是第三方制品，
> 而 `list_sessions` + `read_session` 合起来等于"枚举全部会话 + 整段导出事件流"。
>
> **事后修正（同日装机后由用户实测发现）**：`search_past_conversations` 原先读的是
> `readPersistedHistory()`（**把全部会话拼成一条扁平列表**），又渲染成"一条对话的最近 N 轮" ——
> 模型于是把**别的会话**的内容当成"刚才聊的"，表现成"新建了对话它还记得上一个"，
> 看上去像会话隔离坏了（实际事件流与会话记忆都是对的）。
> ⇒ 归属缺失才是根因：新增 `ChatStateHolder.readPersistedSessions()`（**按会话分组**）取代扁平接口，
> `renderGrouped` 打上 `【当前会话】` / `【其他会话】标题（N 分钟前）`，并加 `ATTRIBUTION_NOTICE`
> 明确禁止把其它会话说成"刚才"；两条分支收成唯一出口 `renderSearchResult` 以免漏声明。
> 回归测试＝`PastConversationAttributionTest`。
> **教训**：跨会话检索不给归属，等于给了模型一份"看起来像上下文"的假事实 ——
> 这类错误不会报错，只会让模型自信地答错。

**原设计**：已有 `search_past_conversations`。补齐为：

- `list_sessions` —— 列出历史会话（标题/时间/消息数）
- `read_session(sessionId, fromSeq)` —— 读某会话的事件范围
- `search_sessions(keyword)` —— 跨会话搜索（现有能力的多会话版）
- `session_trace(sessionId)` —— 返回某轮的工具调用链（血缘）

**前提**：阶段一必须完成（没有事件流，`fromSeq` / trace 无从谈起）。

**收益**：模型能回答"我上周问过你什么""上次那个报错怎么解决的" —— 这是 `LongTermMemoryManager` 的 200 条记忆**做不到**的（它只存结论，不存过程）。

#### 4.3.2 只读子 agent 委派 ✅ 已落地（2026-09-19）

> **✅ 已落地**：`ai/subagent/ReadOnlySubagent.kt` + 工具 `research_subtask`
> （新域 `research`，工具总数 38 → **39**）。与设计稿的三处收敛：
>
> 1. **不用 `SIDE_EFFECT_TOOLS` 反向摘除，而是正向只装 `schemasReadOnly()`**。
>    设计稿说"把副作用工具全摘"；实际做法是**只装只读档**（唯一的准入名单已经是
>    `ToolRegistry.schemasReadOnly()`），这样将来新增工具时是"默认不给子代理"，
>    而不是"默认给、漏摘就出事"—— 方向反了，风险性质完全不同。
> 2. **摘掉它自己**（深度恒为 1）。设计稿没提这一条，但不做的话子代理可以再派子代理 ——
>    这是唯一一种"开销没有上界"的失败方式。
> 3. **加了墙上时钟硬上限 90s**，设计稿只说了"上限 1 个并发"。子代理是**同步阻塞**调用
>    （主循环在等它），没有时间上限意味着一次调研能把用户晾好几分钟。
>    另外**本地模型直接拒绝**：本地轻量会话连主 Agent 的工具都不装配（小模型背不动 Schema），
>    给它一套只读 Schema 只会更糟。
>
> ⚠️ 它的工具调用**走同一条审批链**（`ApprovalGate`），不绕开 —— 限流配额是全局的，
> 绕开就等于开了一条"派子任务刷工具"的旁路。
> ⚠️ **不对 AIUI 页面开放**（`PageScope.DENY_TOOLS`）：页面一次 `callTool` 会触发若干次
> 模型调用，而页面桥是 fire-and-forget + onMessage，**没有取消通道**。
> ⚠️ 刻意没做：子代理内部的事件流记录（它没有轮次语义，硬塞会污染 `turn` 编号与投影）。
> 它的**结论**会作为 ToolResult 被正常记录，轨迹视图里看得到"派过、拿到了什么"。
>
> 新增 `LlmRegistry.Profile.SUBAGENT`：不能复用 `BACKGROUND`（8s 超时是给"失败可以接受"的
> 定时任务解析用的，子代理要连跑几轮 + 读网页），也不该复用 `CHAT`（子代理不接用户的思考开关、
> 不继承本地聊天调参）。

**原设计**：对"帮我查资料并总结成文档"这类长任务，主循环只收结果，把 14 轮预算让给主任务。

**⚠️ 与 DSH 的关键差异**：DSH 的 subagent provider 有 6 种（in-process / fork / acp / codex / claude-code / dsh-sdk），因为它在桌面上。手机上跑子 agent 意味着**并发请求 + token 成本翻倍 + 后台任务存活问题**。

**建议约束**：
- **只做只读子 agent** —— 不下发任何副作用工具（`SIDE_EFFECT_TOOLS` 全摘），避免"子 agent 偷偷拨号"
- 后台存活沿用已有的 `AgentTaskStore` + `schedule_agent_task` 链路
- 上限 1 个并发子 agent

#### 4.3.3 记忆管理 UI + 答案溯源（信任基建）✅ 已落地（2026-09-19）

> **✅ 已落地**（两半都做完了）：
>
> **a) 记忆管理 UI**：`store/MemoryManageDialog.kt` + `AgentSectionPage` 上的「查看与编辑」入口。
> 为此给 `LongTermMemoryManager` 加了 `update(index, content)` —— 在此之前用户**唯一**的纠错
> 手段是引导模型调用 `manage_memory`，而设置页里根本没有"重新说一遍"的入口。
> 编辑会一并刷新 `created_at`：用户刚亲手确认过这条内容，90 天过期计时理应重算。
> 列表读取走 IO 线程（SQLite 读是阻塞调用，`AgentSectionPage` 现有的两处同步读是历史写法，新代码不沿用）。
>
> **b) 答案溯源**：知识库命中的来源标注（`《文档名》第 N 块`）在 `tools` 接缝那轮就做完了；
> 这次补齐**联网侧**：`search_web` 附检索时间、`fetch_webpage` 附**来源 URL + 抓取时间**。
> ⚠️ 关键一条是**系统提示词里明确要求注明出处**（网页给链接、知识库给文档名、保留资料里的时间，
> 且**绝不**把推断说成"资料里写的"）—— 只给数据而不要求引用，等于没做溯源：
> 用户看到的仍然是一个没有出处、无法核对的结论。

**原设计**：
- `LongTermMemoryManager` 的 200 条记忆：做列表页，支持查看 / 编辑 / 删除 / 清空。现在用户看不见、改不了、删不掉 —— 隐私合规上是硬伤
- RAG / `web_search` / `web_fetch` 结果**标注来源**：知识库命中标注文档名 + 段落；联网标注 URL + 抓取时间
- 这两项做起来都不难，但直接决定用户敢不敢信

#### 4.3.4 token 计数与耗时 ✅ 已落地（2026-09-19）

> **✅ 已落地**。与设计稿的三处收敛（都是实现时才暴露的）：
>
> 1. **用量挂在 `TurnEnd` 上，不挂 `AssistantMessage`**。设计稿写的是后者，但
>    `AssistantMessage` **只在这轮真的给出了结论时才写** —— 把成本挂上去等于"没回复就不花钱"，
>    而被打断的长任务恰恰是最烧 token 的那一类。`TurnEnd` 两条收尾路径都必然写一次。
> 2. **累加而不是取最后一次**。一轮带工具循环 = **多次请求**，输入 token 每轮都要重发
>    （整段 system + 历史），那才是成本大头 —— 只报最后一次会把 6 次请求算成 1 次。
>    事件里因此同时存 `modelCalls`（"为什么这么贵"的第一解释）。
> 3. **耗时压根不落盘**：`TurnStart.ts` 与 `TurnEnd.ts` 已经在事件流里，投影一减就有。
>    再存一个字段就是同一件事的第二份记录，而且必然有一份会写漏。
>
> ⚠️ 拿真实用量的代价：OpenAI 协议要求流式请求**显式带** `stream_options.include_usage`，
> 而这是个可选字段 —— 严格的兼容实现会因它**整请求 400**（后果是整轮对话失败，不是"少个数字"）。
> 因此加了**自愈兜底**：命中 4xx 且还没吐过 content 时，撤掉该字段原地重试一次，
> 并把这个端点记进进程内黑名单，之后不再带。没有这条兜底，一个"严格校验未知字段"的
> 兼容实现就会让整个 AI 能力失能 —— 那正是「AI 突然不说话」最典型的成因。
>
> ⚠️ 拿不到时**必须显示"未知"而不是 0**：面板上"输入 0 / 输出 0"是错误信息，比没有更糟。
> 这种情况下面板改说"最近一轮输出约 N 字（服务端未返回用量）"——口径换掉，但不编数字。

**原设计**：阶段一之后，从事件流直接算：
- 每轮 input/output token（模型响应里通常带 `usage`，落成 `AssistantMessage` 的字段）
- 每轮耗时（`TurnStart.ts` → `TurnEnd.ts`）
- 在 `ChatContextBar` 展开面板里显示

#### 4.3.5 轨迹视图（Trajectory view）✅ 已落地（2026-09-19）

> **✅ 已落地**：`ai/session/SessionTrace.kt`（纯函数，可单测）+ `store/SessionTraceDialog.kt`，
> 入口在上下文面板（"用了多少" → "这些是怎么用掉的"，同一个疑问的两半）。
>
> 按来源分**六个分类**并分别着色：对话 / 工具 / **注入** / 压缩 / 作废 / 过程 ——
> 用户扫一眼就能分出"这段是它自己查的"还是"这段是喂给它的"。
> 每条默认只显示一行标题（带 `#seq` 与轮号），**点开才看原文**：注入内容与工具返回动辄上千字，
> 全部铺开等于让人自己去找重点。顶部一行摘要（几轮 / 几次工具 / 几次注入）先给规模。
>
> ⚠️ **与 `SessionDump` 刻意不合并**（同源不同消费者）：`SessionDump` 产出给**模型**读的连贯叙述
> （要求紧凑、单行、可续读），`SessionTrace` 产出给**界面**分组展开的条目（要求能扫、能筛、能看细节）。
> 硬合并的结果是两边都不好用，而且以后改任一侧的文案都会牵动另一侧的测试。
>
> ⚠️ 与既有的「过程」气泡（`AgentStep`，`chat_trace_*` 文案）的关系：那是**轮内实时**的过程时间线
> （思考中/执行中，随生成流式更新、存在消息上）；本视图是**事后**从事件流重建的完整轨迹
> （含注入、压缩、作废这些过程气泡里根本没有的东西）。两者互补，命名上也刻意分开
> （`chat_events_*` vs `chat_trace_*`）以免读代码的人以为是一回事。

**原设计**：
- 按来源分类：系统提示 / 记忆注入 / 知识库命中 / 工具调用 / 压缩事件
- 可展开看每次 `ContextInject` 的原始内容
- 这是**整个方案的收口**：阶段一的事件流到这里才真正"对用户可见"

#### 4.3.6 对话记录图 + 每会话提示词 ✅ 已落地（2026-09-19，用户提出的补充项）

**不在原设计稿里**，是用户装机实测后提的两项能力：

1. **对话记录图**（`ai/session/SessionGraph.kt` + `store/SessionRecordsDialog.kt`）：
   把会话事件流画成**竖向节点连接图** —— 主脊（时间顺序）+ 工具节点的肘形分支，
   两类边 `NEXT` / `TOOL_PAIR`（`call→result` 按 `callId` 配对，**落盘顺序 ≠ 调用顺序**）。
   另有搜索（标题+详情）、导出 Markdown（`MediaStore.Downloads`）、逐条复制/编辑/删除。
   入口＝会话列表每行「删除」后面的按钮。

   - **与 §4.3.5 轨迹视图刻意不合并**：那个回答"这一轮**做**了什么"（按来源分类、面向复盘），
     这个回答"这个会话**记录**了什么"（按 seq 逐条、面向核账与导出，还要摆出压缩/作废/孤儿调用）。
   - **布局刻意不用力导向**：事件流是线性路径，力导向只会把它随机摊开、丢掉先来后到；
     手机是窄屏。配对关系另以 `→#seq` 标注 —— 窄屏上跨节点长连线会糊成一团。
   - ⚠️ **编辑/删除只对当前会话开放**：它们作用在 UI 消息层，跨会话涉及异步加载与在飞的对话。

2. **每会话提示词**（`ChatSessionMeta.systemPrompt` → `buildSystemMessage` 末尾）：
   语义是**追加**而不是替换 —— 全局人设里装着"不能编造工具结果""多步先 update_plan"
   这类**功能正确性**规则，被用户随手一句顶掉会让模型开始乱来；用户要的多半只是风格约束。

> ⚠️ 落地时踩到一个语义坑值得记：节点上的"是否已排除出上下文"**不能**用
> `SessionEvent.modelVisible` 判 —— 那个字段的意思是"能不能变成一条聊天消息"，
> 对 `TurnStart`/`ToolCall`/`ContextInject` 都是 false，而它们确实在请求里
> （工具调用是 tool 消息、注入在 system 里）。照它判会把图上**除消息以外的每个节点**
> 都标成"不进上下文"（一片灰 + 红色标注），既错误又掩盖了真正被排除的那几条。
> ⇒ 判据改为"是否落在 `shadowedSeqs` / `VisibilityCut.seqs` 里"。

---

### 阶段四：明确不做 / 慎做

| 项 | 结论 | 理由 |
|---|---|---|
| **代码执行沙箱** | **不做** | DSH 在桌面上有 landlock / sandbox-exec；Android 无等价强隔离。而 RokidLab 已有的 ADB shell 通道**语义上比沙箱更危险**。正确做法是**风险分级 + 审批**（§4.2.4），而不是再套一层假沙箱 |
| **MCP 客户端** | **缓做** | 价值在接第三方生态，但要在手机上跑 stdio/http MCP 涉及网络 + 权限 + 存活，收益不确定。**等阶段二完成后再评估** —— 届时只需加一个 `McpToolProvider`，成本极低<br>⚠️ **该"成本极低"判断已被 [MCP-INTEGRATION-PLAN.md](./MCP-INTEGRATION-PLAN.md)（2026-09-20）修正**：provider 只是外壳，主体工作量在 `ToolRegistry` 六张派生表要静态→动态化 |
| **Computer / Browser use** | **不做** | 眼镜场景无意义（DSH 的 `stagehand_*` 是桌面浏览器自动化） |
| **插件热重载（HMR）** | **不做** | Android 上不现实。用"设置页开关 + 域装配"（`SESSION_AGENT_DOMAINS` / `SESSION_AIUI_DOMAINS` 机制）已达到同样目的 |
| **32 个接缝全抄** | **不做** | 见 §0。只需 4 个 |
| **`ctx.fs` / `ctx.subprocess` 接缝** | **不做** | 单宿主下是纯开销 |

---

## 5. 依赖顺序

```
阶段一（事件流底座）
   │  必须先行：阶段三的会话查询/trace/token 全部依赖它
   ▼
阶段二（四道接缝）
   │  tools 接缝优先（ROI 最高，且 MCP 依赖它）
   │  llm 接缝次之（压缩阈值依赖真实 contextWindow）
   ▼
阶段三（能力补齐）
```

阶段一与阶段二**技术上可并行**（事件流改 `ai/session/`，接缝改 `ai/tools/` 与 `ai/OpenAiService`，交集在 `AiConversationService` 的主循环）。但**建议串行** —— `AiConversationService` 是两者共同的改动热点，并行会冲突。

---

## 6. 风险与对策

| 风险 | 对策 |
|---|---|
| **事件流改造动到聊天主链路** | `AgentSessionManager` 对外接口一个都不改，只换内部实现（适配器模式）。分步迁移，每步可编译可运行 |
| **接缝化把小问题复杂化** | 严格限制在 4 个接缝。判定标准：**如果将来只有一个实现，就不要做接缝**。`approval` 是唯一"现在就有多实现"的 |
| **工具 schema 迁移风险** | 逐域迁移，每迁一域跑一次 `check_tool_wiring.py`。参考 `list_glasses_apps` 静默失效的教训 —— 迁移时脚本要做**双向内容比对**，不只是名称存在性 |
| **模型路由兼容性** | OpenAI 兼容端点对 `image_url` / `tool_calls` 的支持差异极大。必须有 capability probe + 保守回退（现有 OCR 回退逻辑可复用） |
| **事件流文件增长** | `chat_sessions/<id>.jsonl` 会随工具调用轨迹显著变大。需要：单会话大小上限 + 归档策略（对齐 DSH 的 `spillStore` 思路）。**注意 `.workbuddy/` 被 gitignore**，清理策略要先行定好 |
| **压缩的配对平衡** | 持久历史层面＝轮完整性（`isTurnBalanced()`，已单测）；**请求内** messages 的 tool-call/result 配对靠"溢出恢复时整体重建、绝不就地拆段"来保证（已落地的做法） |

---

## 7. 建议的第一步

**`ToolProvider` 接口升级**（§4.2.1）。

理由：ROI 最高。它同时解决三件事 —— ① 把 `check_tool_wiring.py` 从防御工具变成回归测试；② 为 MCP 留出唯一入口；③ 让工具 schema 就近维护。而且**不依赖阶段一**，可以立刻动手。

唯一需要注意：**逐域迁移**，不要一次性搬。

> ✅ `tools`（§4.2.1）已完成（2026-09-19）。
> ✅ `llm`（§4.2.2）已完成（2026-09-19）。
> ✅ `compaction`（§4.2.3）已完成（2026-09-19）—— 按建议优先于事件流底座，因为
> `llm` 给出的 `ModelCapabilities.contextWindow` 让它有了真实依据，且用户可感知
> （溢出自动恢复 + 手动「立即压缩」）。
> ✅ `approval`（§4.2.4）已完成（2026-09-19）—— 判定点从 4 处收敛到 `ApprovalGate` 一处；
> 伪工具首次被纳入闸门（此前完全绕过硬拒，会白等 35s 眼镜确认）。
>
> 🚧 **阶段一「事件流底座」进行中**：步骤 1a（数据层 `ai/session/` 三文件 + 单测）已完成，
> 详见 §4.1.4 的落地状态表。**四道接缝全部完成之后，这是唯一剩下的主线。**
> 剩 1b（`AgentSessionManager` 改读投影 + 会话绑定 + 落盘）与 1c（迁移与真机验收）——
> 两者都会动 `AgentSessionManager` 与 `ChatStateHolder` 的线程模型，值得单独一轮。
>
> 注意 1a 的两处**已知偏离**（§4.1.4 有完整理由）：事件流暂**独立落盘**
> `agent_sessions/<id>.jsonl`，而**不是**直接复用 `chat_sessions/<id>.jsonl` ——
> 后者被 UI 的结构性编辑整份重写，"只追加"这条不变式在里面立不住。
> 终局仍是合并成一份，但要等 UI 侧也改成"追加事件"（届时做一次合并迁移）。
>
> **对下游的解锁**：1a 落地后，§4.3.1 的会话查询工具族（`fromSeq` / `session_trace`）
> 与 `CompactionEngine` 里"等事件流再补"的孤儿锁校验，都已经有了数据基础
> （`SessionRecord.seq` 可直接做 `fromSeq` 的游标，`CompactionSummary.shadowedSeqs`
> 就是 `shadowedSeqs` 投影）—— 但它们依赖 1b 把事件流真正接到生产路径上，现在还调不到。

---

## 附录 A：DSH 相关事实速查

- 许可证：MIT（对二次开发和商业使用友好）
- 版本：`0.1.0-rc`，**developer preview，会有破坏性变更**
- 内核：Cordis（论文《A Programming Paradigm for Spatiotemporal Composability》）
- 包结构：`packages/core`（spine：session / system-prompt / tools / agent / agent-loop / scope）、`packages/bundle`（base / web-app / headless / sdk-app / acp-app / sdk-minimal）
- 运行时模式：Standard / Code（`run_code` 编排多轮工具）/ Minimal（只有 shell + 编辑器，用于 benchmark）/ Creator（可检视运行时、内存中试插件）
- 工具规模：28 个工具包、68 个工具实例
- 五张规范映射（可扩展联合类型）：`ContentBlockMap` / `MessageSourceMap` / `FinishReasonMap` / `TurnEndReasonMap` / `SessionEventMap`
- 工具执行管道：`tool/call` → `tools/pre-execute`（waterfall）→ `tools/execute` → `tools/post-execute` → `tool/result`
- 扩展插件**依赖 `agent` 而绝不依赖 `agent-loop`**（保持循环可替换）
- 品牌 ID：ID 在类型级别不可互换（`SessionId` 不能当 `ToolCallId` 用）

## 附录 B：RokidLab 现状速查

- 工具：35 个（可见 31 / 隐藏 4），10 个域，11 个 `ToolProvider`，六张表手工同步
- 主循环：`AiConversationService`，`MAX_TOTAL_ROUNDS = 14`，`SUMMARY_MAX_ROUNDS = 3`
- 会话记忆：`AgentSessionHistory`，12 条 / 6000 字符 / 摘要 800 字符 / 10 分钟过期
- 多会话：`ChatSessionStore`（`chat_sessions.json` + `chat_sessions/<id>.jsonl`）
- 已落地（2026-09-19）：多会话、消息级操作、上下文用量条、图像理解开关
- 已有等价物（无需重建）：`SkillRegistry`、`LongTermMemoryManager`、`KnowledgeBase`、`AgentPlan`、`AgentTaskStore`、`ToolRisk` + `GlassToolConfirmChannel`
