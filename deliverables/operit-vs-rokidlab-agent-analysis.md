# Operit 研究 × RokidLab Agent 诊断报告

> 研究对象：https://github.com/AAswordman/Operit （Android 端最成熟的开源 AI Agent，18 个月持续迭代）
> 对照对象：RokidLab 乐奇聊天 agent
> 日期：2026-10-01

---

## 一、Operit 为什么"聪明"——五个核心机制

### 1. 模型永远看到完整的能力地图（不搞关键词路由）

Operit 的 system prompt 结构：

```
自我介绍 + 工作区指南 + 工具使用准则 + 包系统 + 可用包清单 + 全量工具定义
```

- **每轮全量下发**所有工具的结构化定义：名称、描述、每个参数的类型/示例值/默认值/取值范围（如 `environment` 参数直接枚举 `"android" | "linux" | "repo:<名>"` 三种取值并解释各自路径风格）。
- 模型**做计划时知道自己有什么牌**。这是任务分解质量的根基——模型不知道 `grep_context` 存在，就永远不会想出"先语义搜代码再改"的执行路径。
- 能力扩展用 **package（包）系统**做减法：包先 `use_package` 激活才展开细节，避免提示词爆炸。**减法做在"细节层级"，而不是把能力藏起来。**

### 2. 循环终止按 token 预算，不按轮数

`EnhancedAIService.processToolResults`：每轮工具执行后估算请求窗口，**超过 maxTokens 的使用率阈值才触发总结收尾**。轮数本身无硬顶。对比 RokidLab 的 `MAX_ACTION_ROUNDS = 6`：生成一个 8 文件的页面项目，6 个动作轮在第 7 个文件就被掐断，模型被迫进"兜底总结"，用户看到的就是"做一半、逻辑烂"。

### 3. 每一条错误都是"教学"——错误信息告诉模型下一步该干什么

`buildToolNotAvailableErrorMessage`（ToolExecutionManager.kt:739）按失败原因分四种，每种都给出**精确的修正动作**：

- `pack.tool` 写成 `pack.tool` 点号格式 → "请用 `packName:toolName` 冒号格式，你可能想调的是 `X:Y`"
- 包没激活 → "请先调用 `use_package(package_name=X)` 列出该包全部工具"
- 把包名当工具名调 → "X 是工具包不是工具，先用 use_package 激活"

模型拿到错误后**下一轮就知道怎么改**，而不是再猜一次再错一次。Operit 的工具描述里也全是这种"踩坑沉淀"：`visit_web` 的描述花了 300+ 字讲清 `Results: [1] [2]` 编号怎么用、什么时候不能用 link_number 下载图片、什么时候该改用 http_request——每一个"IMPORTANT/NOTE"都是真实失败案例换来的。

### 4. 流式截断自愈

`detectAndRepairTruncatedToolRound`：模型流式输出被截断、只吐出半个 `<tool name="xxx">` 时，系统**自动补全标签闭合、把残缺调用标记为 invalidated**，不让一次网络抖动毁掉整轮。另有 `normalizeToolXml`、`enhanceToolDetection` 两道后处理。**Agent 的可靠性 = 处理模型最差输出的能力。**

### 5. 工作区（Workspace）让代码生成有"闭环"

- 聊天绑定工作区目录，prompt 注入工作区指南（绝对路径纪律、挂载说明）
- 注入**项目规则文件**（等同 CLAUDE.md）：项目自己的约定直接进 system prompt
- 系统提示写死最佳实践："**修改任何文件前，先用 grep_code + grep_context 定位并理解上下文，禁止盲改**"
- 文件改完可以**在 Ubuntu proot 终端里跑起来、在浏览器里预览**——模型能亲眼看到报错、自己修

**这是"生成的代码不差"的最大秘密：代码是被运行过、验证过的，不是盲写的。**

其他细节：工具结果单条上限 64k 字符（文本 5k）；可并行的只读工具（visit_web 等）自动并行执行；子任务 agent 有独立严格 prompt（无记忆无人格、必须消化资料给结论、禁止等待用户输入）。

---

## 二、RokidLab 对照诊断：你的短板不在你以为的地方

先说结论：**RokidLab 的 system prompt 质量、工具描述质量、轮次分账（只读/动作）、schema 自检、前缀缓存设计，放在开源里都算优秀，这些不是问题。** 真正的差距按影响力排序：

### 差距 0（2026-10-01 修订：最大单一成因）：deepseek-flash 被全程关着思考跑

> 修订说明：初版把"模型能力"排第一。经用户指正，Lab 本来就是随便接模型，且当前接入的
> `deepseek-flash`（V4.1-Flash）就是 DeepSeek 官方 API 主力档——Terminal-Bench 90.6、
> DeepSWE 74.2、NL2Repo 65.4，官方口径超过 V4-Pro。**模型不是瓶颈。**
> 但查请求装配链路后发现：这个强 agent 模型在 Lab 里**每轮都被显式关掉思考**。

证据链（全部可复核）：

1. `ChatScreen.kt:112` — 思考三档 `auto | deep | off`，`auto` 的语义是"推理模型照常思考、**普通模型显式关**"。这个分类写于 deepseek-chat=非思考模型的 V3 时代。
2. `ModelPresets.kt:263` — `supportsThinkingDisabled` 按 `contains("deepseek")` 命中，`deepseek-flash` 被归入"普通模型"。
3. `AiConfigService.kt:190` — `KEY_AI_THINKING` **默认 false**（用户没动过开关就是关）。
4. `OpenAiService.kt:33` KDoc 自述了原因："reasoning 会吞掉输出预算导致 finish=length 空轮"——**用全局关思考来绕一个当年没修好的预算 bug**。
5. `LlmRegistry.newService` — `Profile.SUBAGENT` / `Profile.BACKGROUND` 根本不传 thinkingEnabled，**子代理调研和定时自主任务也一律关思考**，max_tokens 8192。

结果：DeepSeek 官方跑 agent 基准（Terminal-Bench 等全部成绩）用的是 thinking 开启 + reasoning_effort + top_p 0.95；而 Lab 里同一个模型每轮都是 `thinking:{"type":"disabled"}` + 8k 输出上限。**"生成的代码差、执行逻辑差"——模型连想都没想就被要求出手，这是第一成因，且修起来最便宜。**

修法（按性价比）：

- 任务轮/工具轮开思考：与 `ChatFastPath` 的闲聊快路天然互补——纯闲聊轮保持 disabled（省首字延迟），**检测到工具装配的轮次改发 `thinking:{"type":"enabled"}` + `max_tokens=MAX_TOKENS_THINKING(32000)`**（预算路径已存在，deep 档在用）。
- 根治当年的 finish=length 空轮问题：`finish_reason=length` 且只有 reasoning 溢出时，用现有 `accumulator.hasEmittedContent()` 判定后自动续一轮，而不是靠关思考躲。
- 给 SUBAGENT / BACKGROUND 两个 profile 传 thinkingEnabled（同步调研/自主任务是全 Lab 最吃推理的场景，且对首字延迟不敏感）。
- 可选：给 deepseek 系透传 `reasoning_effort`（闲聊轮 low、任务轮 medium、子代理 high）。
- `supportsThinkingDisabled` 的家族分类补一条：`flash` / 新混合模型**不匹配**"可显式关"——宁可少关不能错关（与现有取舍一致）。

### 差距 ②：代码生成没有验证闭环——"生成的代码差"的直接原因

- `save_code_file` 盲写进 `下载/项目名/`，写完即宣称成功。模型从没见过自己代码运行的样子。
- 你已经有 proot 自持（`run_shell`）和 `start_web_preview`（WebPreviewManager）——**零件齐了，缺的是把它们串成"生成→运行→读报错→修"的标准作业流程**：
  - HTML/JS 项目：生成完自动 `start_web_preview`，让模型确认页面不白屏（至少渲染无 JS 错）
  - Python/脚本：生成完在 run_shell 里跑一遍，把报错回喂给模型修一轮
  - 在 system prompt 的代码准则里写死："落盘后必须运行验证，带着报错修完再交付"
- 这一条改完，"代码质量差"会有肉眼可见的好转，且不依赖换模型。

### 差距 ③：动作轮预算 6 轮太紧，复杂任务被结构性掐断

- 多文件项目（HTML+CSS+JS+README 轻松 5-8 文件）+ 中途试错，6 个动作轮根本不够；`MAX_AUTO_CONTINUES = 1` 只续一段。
- Operit 的思路值得抄：**终止条件从"轮数"换成"token 预算"**（估算请求窗口超阈值才收尾）。你有 `estimatePreparedRequestWindow` 的等价物吗？没有就退而求其次：动作轮放宽到 12~16，靠 CompactionPolicy 控上下文；或按"连续 N 轮无新动作"判收敛。
- 另注意：预算紧张会诱发模型的"偷懒行为"（一次写超大文件、跳过验证步骤），这本身就是执行逻辑差的观感来源之一。

### 差距 ④：三层路由的信息饥饿——省 token 的代价是规划质量

- 关键词路由把"天气轮"砍到 2 个 Schema、菜单探测轮只有 ~2k 菜单，**模型在计划阶段不知道自己还有什么牌**。跨域任务（"查天气然后定个提醒顺带看看那个文件"）全靠 `[NEED_TOOLS]` 折返跑，每折返一次多一轮延迟+一次缓存 miss。
- 你们的实测（42.4k 账单）证明了"子集中途扩全量"是坏策略——对。但另一头也值得重测：**任务轮从第 0 轮就挂稳定全量 83 + 追加式历史**。DeepSeek 前缀缓存命中价 ≈ miss 的 2%，全量 Schema 每轮的真实成本可能远比想象低；而模型看到全量能力后，单轮内自己就能串多工具，反而**省掉 [NEED_TOOLS] 折返和砍半的任务成功率损失**。
- 建议做个 A/B：同一批任务，`路由子集` vs `稳定全量`，比 token 账单和任务完成率。别用单轮账单下结论，用**整个任务的总账单+完成率**。

### 差距 ⑤：错误反馈不够"教学"

- 你们自己的 KDoc 写着："白耗一轮，拿回一句「未知工具』"。对照 Operit 的四分支教学式错误，差距明显。
- 低成本高收益改造：所有工具失败路径（未知工具、参数校验失败、权限拒绝、环境未装）统一走"**错误 + 原因 + 下一步该做什么**"三段式模板。比如环境未装："proot 环境未安装。请告知用户到 设置→本机执行环境 安装，安装前不要重试本工具"。
- 顺手把 `auditToolSchemas` 的思路延伸：**工具执行失败率高的条目**进日志面板聚合展示，用来迭代工具描述（Operit 的描述就是 18 个月失败案例喂出来的）。

### 差距 ⑥（锦上添花）：项目级约定注入

- 代码生成类任务支持用户放一个"项目规则文件"（框架约定、命名风格、禁用项），生成时注入 system。一次性成本，长期提升生成一致性。

---

## 三、行动清单（按 优先级×成本 排序）

| # | 动作 | 成本 | 预期收益 |
|---|------|------|----------|
| 0 | **任务轮/子代理/自主任务开思考**（deepseek-flash 混合模型，闲聊快路保持关） | 低 | ⭐⭐⭐⭐⭐ |
| 2 | 生成后强制"运行验证→修一轮"闭环（run_shell + start_web_preview 接进代码准则） | 中 | ⭐⭐⭐⭐⭐ |
| 3 | 错误信息三段式改造（错误+原因+下一步） | 低 | ⭐⭐⭐⭐ |
| 4 | 动作轮预算放宽 / 改 token 预算终止 | 低 | ⭐⭐⭐⭐ |
| 5 | A/B 测「稳定全量 Schema」vs「路由子集」的总账单与完成率 | 中 | ⭐⭐⭐ |
| 6 | 项目规则文件注入 | 中 | ⭐⭐⭐ |
| 7 | 截断自愈（原生 tool_calls 下优先级降低，doubao 场景仍值得） | 中 | ⭐⭐ |

---

## 四、附：Operit 关键源码索引

- 系统提示词组织：`core/config/SystemPromptConfig.kt`（模板 + 占位符替换 + 工作区指南）
- 工具定义（含示例/默认值/踩坑注释的范本）：`core/config/SystemToolPrompts.kt`
- 工具执行/并行/错误教学：`core/tools/` + `api/chat/enhance/ToolExecutionManager.kt`
- Agent 主循环/token 阈值收尾：`api/chat/EnhancedAIService.kt`（`processToolResults` 2400 行附近）
- 截断自愈：`EnhancedAIService.detectAndRepairTruncatedToolRound`
- 工具结果上限：`core/tools/ToolExecutionLimits.kt`（文本 5k / 单条 64k）

---

## 五、实施方案（2026-10-01，锚定到 RokidLab 具体落点）

> **实施进度（同日）**：P0 全部完成 ✅（prefersThinking 准入 / 120s 思考超时 / SUBAGENT 开思考 / length 空轮 64k 重放）；
> P1 完成 ✅（save_code_file 准则追加"落盘后必须验证"）；
> P2 完成 ✅（MAX_ACTION_ROUNDS 6→12、MAX_AUTO_CONTINUES 1→2、MAX_TOTAL_ROUNDS 14→20）。
> P2-b 核实结论：升级到全量后**锁定本就存在**；连带实锤并修复了一个隐藏 bug——`SESSION_AIUI_DOMAINS`
> 缺 `DOMAIN_SHELL`，代码生成一开场 `start_web_preview`/`run_shell` 就从 Schema 消失，验证闭环在
> 最需要的场景下根本调不到工具，已补。
>
> **第二批（同日续）**：
> - **P0-5 reasoning_effort 分档** ✅：`Options.reasoningEffort` + `OpenAiService` 构造参数，
>   流式/非流式双路径附带 `reasoning_effort`，与 thinking 字段同款"被拒撤回"兜底
>   （`reasoningEffortRejected` 按 模型@端点 记忆）。分档：任务轮 medium（LlmRegistry 默认）、
>   子代理 high、闲聊/路由轮不附带、BACKGROUND 不附带。
> - **#3 错误信息三段式** ✅：①未知工具改为执行侧前置判定——给出编辑距离 ≤2 的最近真实工具名
>   （"你可能想调的是 X"），不再白等闸门+重试；②Deny 分支按 DecisionOrigin 分流出路
>   （RISK_CONFIRMATION→"不要重试本工具"、RATE_LIMIT→"稍后重试"、TOOL_DISABLED→"告知用户去设置开启"…）。
>   （执行失败/参数非法的 teaching 式报错本来就有，未动。）
> - **#7 截断自愈** ✅：`repairTruncatedJson`——arguments JSON 解析失败先做保守闭合修复
>   （悬空字符串/括号/冒号），截在值中间则回退最近结构位重试（≤64 次），修复成功照常执行只打日志。
> - **#6 项目规则注入** ✅：用户把约定写在 `下载/RokidLab规则.md`（≤6000 字符），代码/项目类
>   任务装配工具时注入 system 的【项目规则】节。懒加载+每任务只读一次（闲聊零开销），任务内
>   逐字节稳定（缓存前缀纪律）。
> - **仍需用户执行**：A/B 测「稳定全量 Schema」vs「路由子集」（真机跑分，看任务总账单与完成率）。

### 附：真机 bug 修复（2026-10-01 第三批）——「运行 HTML」报 `https://file///...` 无法打开

两层根因，都已修：

1. **URL 拼坏**（`BrowserToolProvider.normalizeUrl`）：旧实现先做 `https://` 补全再校验 scheme，
   `file:///storage/...` 被当"无 scheme"输入拼成 `https://file///storage/...`（host=file），
   URI 校验拦不住 → WebView `ERR_NAME_NOT_RESOLVED`。已改为**先判原始 scheme 再补全**，
   非 http(s) scheme 与本地路径形状一律拒绝，且拒绝时给出指向 `start_web_preview` 的教学式出路。
2. **结构性缺口**：`save_code_file` 写「下载/<项目名>/」，但容器只挂 `/mnt/lab`（↔ 下载/Lab）——
   **模型起 http.server 根本读不到刚生成的项目**，被迫拿 file:// 硬开浏览器。已新增
   `ProotShell.ensureDownloadBind`：授予「所有文件访问」时把公共下载目录挂为容器内
   **`/mnt/download`**（run_shell 与常驻预览两条通道都生效），并在 run_shell /
   start_web_preview 工具描述与系统提示里写明正路：`python3 -m http.server $PREVIEW_PORT
   --directory /mnt/download/<项目名>`。

### P0 · 开思考（性价比最高，先做）

**P0-1 flash 不再匹配"可显式关思考"**
- `ModelPresets.kt:264`：`return !m.contains("reasoner") && !m.contains("-r1") && !m.contains("r1-")` → 追加 `&& !m.contains("flash")`。
- 效果：deepseek-flash 不再被附 `thinking:{"type":"disabled"}`。一行改动，全 Lab 生效（能力快照/设置页口径同步走接缝，零额外改动）。

**P0-2 任务轮开思考 + 大预算**
- `AiConversationService.kt:735`：`Options(thinkingEnabled = session.isThinkingEnabled(), ...)`。
  - 判据提前算（`isPureChat` / `ToolIntentRouter.route` 都是纯函数，735 处先调一次，951/循环内复用结果）：
    `thinkingEnabled = 开关 || (非纯闲聊 && 非路由命中轮)`
  - 即：**闲聊 0 工具轮关、高置信路由轮（天气/时间/算式）关保持秒回、菜单探测/全量/[NEED_TOOLS] 轮开**。
  - 思考轮自动获得 `max_tokens=MAX_TOKENS_THINKING(32000)`（OpenAiService 已有路径）。
- ⚠️ 配套超时：`chatTurnStream` / `chatTurn` 在 `thinkingEnabled=true` 时 readTimeout 取 `max(CHAT_REMOTE_TIMEOUT_MS, 120_000)`（探测轮非流式 + 30s 读超时会被思考掐死）。

**P0-3 子代理/自主任务开思考**
- `LlmRegistry.newService`：`Profile.SUBAGENT` / `Profile.BACKGROUND` 传 `thinkingEnabled = !local`，并同步放宽 `BACKGROUND_TIMEOUT_MS`（8s 对思考轮必超时）。

**P0-4 根治 finish=length 空轮（当年关思考的因）**
- `OpenAiService` 流结束处：`finish_reason=length && !accumulator.hasEmittedContent() && thinkingEnabled` → 自动续一轮（沿用现有重试骨架）。修完这个，"关思考防空轮"的历史包袱才真正卸掉。

**P0-5（可选）reasoning_effort 分档**
- `Options` 加 `reasoningEffort: String?`，deepseek 系透传：闲聊不传 / 任务 medium / 子代理 high。

### P1 · 代码验证闭环

- `OpenAiService.buildSystemMessage` 的 `save_code_file` 条款（`OpenAiService.kt:374`）追加一句："**多文件/项目类生成落盘后必须验证再交付：网页项目用 start_web_preview 确认能渲染（白屏/报错要修）；脚本与命令先用 run_shell 跑一遍；带报错修完才算完成**"。
- 零结构改动，纯提示词 + 现有工具组合。

### P2 · 轮次与 schema 稳定性

- `AiConversationService`：`MAX_ACTION_ROUNDS` 6→12（或改 token 阈值收尾）；`MAX_AUTO_CONTINUES` 1→2。
- 任务轮 schema 锁定：进入工具循环后一旦装配过全量，**锁到任务结束**（[NEED_TOOLS] 折返后不回子集）。落点在循环内装配处（~991）。
- 用 AI route 打点复盘 42.4k 账单：确认"扩全量后每轮全价"是否为装配不稳定（稳定全量第 2 轮起应命中缓存）。

### 验证方式（按约定：只编译，不打包装机）

`./gradlew.bat :phone-app:compileDebugKotlin`；真机 A/B 由用户执行——同一批任务（一个闲聊、一个天气、一个多文件页面生成），对比改动前后：任务完成率、总 token 账单、"过程"里的空轮/折返次数。

## 六、能力验证（2026-10-01 第四批）："让做个项目"能做到哪一步

### 真机现状（adb 实测）

| 项 | 状态 |
|---|---|
| 设备 | 9fc033b0（小米 24129PN74C）在线 |
| 装的包 | v4.3，2026-09-30 16:34 —— **今天全部改动未上真机** |
| MANAGE_EXTERNAL_STORAGE | allow ✓（/mnt/download 挂载前提成立） |
| 无障碍 LabAccessibilityService | 已启用 ✓ |
| proot rootfs | `files/proot/rootfs/bin/bash` 存在 ✓（start_web_preview 可用） |

### 旧构建的实际战绩（磁盘取证）

- `下载/color-runner/` 只有一个 `app.json`（小游戏配置），无任何页面/代码文件——**半途而废**。
- 报错里引用的 `ccmusic-player/color-music-player.html` **磁盘上不存在**——模型虚构了交付路径，且没落盘，再拿假路径拼 URL 硬开浏览器（三连失败）。
- 对照组：同机 Operit 留有完整 `android_projects/aiui_simulator/` 产物（含反编译工程）——这就是"聪明"与"笨"在同一台设备上的实物差距。

### 新构建的链路推演（逐环节代码核实，全部通过）

1. 闲聊快路不触发、意图路由未命中 → 进菜单探测轮（~2k 菜单，全量点名可用）
2. autoThink=true（prefersThinking(flash)✓ 且 llmOptions 每条消息算一次，**任务全程保持开思考**）
3. 模型点名 [TOOLS] → 升级全量 Schema（锁定不回退）→ reasoning_effort=medium
4. save_code_file 落盘 → isCodeGenCall 切 AIUI 子集（**含 SHELL 域**，验证工具不消失）
5. start_web_preview：proot 已装 ✓，`python3 -m http.server $PREVIEW_PORT --directory /mnt/download/<项目名>`（新 bind，权限已确认）
6. 轮预算 8 只读 + 12 动作 + 2 自动续段：多文件 + 修错不再被 6 轮掐断
7. 兜底：未知工具给最近名、Deny 按来源给出路、截断 JSON 自愈

### 残余留白

- 真机要见效**必须打包装机**（等用户指令）；
- 菜单探测轮也开思考（autoThink 未排除探测轮）→ 任务首响会慢一点，可接受；
- deepseek-flash 是否认 `reasoning_effort` 字段未实测（有被拒撤回兜底，最多浪费一次 4xx）；
- 路由误判时任务轮沿用轮 0 的关思考决定（低概率，已记录）。

## 七、AIUI 生成专项诊断（2026-10-01 第五批）："AIUI 彩色跑酷"为什么惨

### 真机实物证据（run-as 取证 `files/aiui_projects/`）

| 项目 | 内容 | 判定 |
|---|---|---|
| color-runner | 只有 app.json（221B，9-30 22:26） | 唯一真 AIUI 尝试，**连第一个 .ink 都没写出来** |
| color-game | 单个 index.html | 退化成网页 |
| color-music-player | 单个 color-music-player.html | 退化成网页（file:// 报错的主角，且 Download 公开副本不存在） |
| html-games | 单个 color-breakout.html | 退化成网页 |
| sudoku-game | 单个 index.html | 退化成网页 |

**5 个项目里 4 个是模型悄悄退化写的 HTML**——AIUI 链路走不通后模型选了它最熟悉的路径，而 HTML 又因 file:// bug + 容器读不到 → 全死。

### 根因排序

1. **技能学习成本吃光只读轮**：aiui-dev 是目录式大技能（152KB/20 文件），SKILL.md 要求按任务加载 7-8 份参考文档，每次 load_skill(_section) 计 1 只读轮；旧预算 8 轮全烧在读文档上还没写一行代码。
2. **大 .ink 截断死亡**：跑酷游戏单页数百行，旧 max_tokens=8192 + 无 JSON 截断修复 → 写 index.ink 必截断 → 反复失败烧光 6 个动作轮 → 只剩 app.json。**（截断自愈 + 64k 重放已治）**
3. **🚨 lab-runtime.md 不在 SKILL.md 阅读清单**：它是唯一讲「彩色=手机预览专属（§2.4 两步）/ 眼镜单色绿屏 DOM-not-canvas / 交付自检（§8）」的宿主须知——SKILL.md 官方清单只列 framework/events/components/wxss/design-system/apis/checklist，模型**根本不知道要读它**。SKILL.md 属官方 13 文件禁改，只能靠系统提示补引导。
4. **退化无守门**：系统提示没有"AIUI 必须走 .ink"条款，save_code_file 条款反而鼓励"网页项目用 start_web_preview 验证"——模型把眼镜应用写成 HTML 无人拦截。
5. **验证闭环存在但没接进生成流程**：`open_aiui_app(target=phone)` 对刚生成的项目能就地打包演示（AiuiToolProvider），但系统提示只在"打开/演示"场景提它，生成条款里只字未提。

### 已实施修复（本轮）

- **系统提示 AIUI 生成纪律**（`open_aiui_app` 条件块新增条款）：必走 aiui-dev 技能 + .ink 四块结构、禁 HTML 冒充；彩色=手机预览专属（点名著 lab-runtime §2.4 两步）；单 .ink ~150 行拆分纪律；**写完必须 open_aiui_app(target=phone) 演示自检再交付**。save_code_file 条款补"AIUI/眼镜应用不适用 HTML 流程"交叉引用。
- **MAX_READONLY_ROUNDS 8→10**（文档装载留裕量）、MAX_TOTAL_ROUNDS 20→22（保持=两分账之和）。

## 八、项目删除/残留专项（2026-10-01 第六批）

### 残留来源取证（真机 + 代码）

| 残留物 | 来源 |
|---|---|
| `Download/<项目>/` 空目录 | MediaStore 删文件行**不动磁盘目录**，删完留下空壳 |
| `aiui_packages/<uuid>.color.aix` 孤儿（5 个） | buildAix(colorTheme=true) 另产的彩色预览包，与绿包并存；管理页/注册表清理只删绿包 |
| 残缺项目（只有 app.json） | 生成中止后无人清理，localProjects 仍当项目展示 |
| 眼镜端 .aix 残留 | 对话内 `delete_file` 删项目**完全不联动**注册表/本地包/眼镜——手机删了眼镜上还能语音打开 |

### 已实施修复（编译通过 exit=0）

1. **`AiuiProject.deletePackageFiles(agentId)`**：绿包 + 彩包一起删；管理页 deleteRecord 与 purgeObsoleteFiles 改用它。
2. **空目录修剪**：`WebTools.deleteDownloadedProject` 删完 MediaStore 行后自底向上修剪 `Download/<proj>/` 空目录（delete() 对非空目录天然返回 false，安全；未授全权时静默无害）。
3. **对话内删除联动眼镜**：`FileWorkspace.unlinkRegistryRecords`——整项目删除（path 空）时注销登记 + 删本地包 + `deleteAixOnGlasses` 尽力删眼镜（未连接时如实报告，不阻塞手机侧删除）；镜像在/不在两个分支都接入，结果拼进工具回复告知模型。
4. **生成纪律补⑤**：任务失败/中止必须用 delete_file 清残缺文件（project 传名、path 空 = 整项目删），不许留半个项目占位。

## 九、全链路审计（2026-10-01 第七批）：生成 / 运行 / 修改 / 删除

### 链路 1 · 生成 ✅（各接缝已核）

闲聊快路(0工具) → 意图路由(单域) → 菜单探测(全量点名，含伪工具) → [TOOLS]/[NEED_TOOLS] 升级全量并锁定 → autoThink 全程开思考(medium) → save_code_file 落盘（.ink SFC 校验拒 Vue 风格 / 私有镜像 / MediaStore 覆盖式公开副本）→ isCodeGenCall 切 AIUI 子集（含 SHELL 域）→ 截断 JSON 自愈 + finish=length 64k 重放 → 未知工具编辑距离纠错 / Deny 按来源给出路 → 项目规则注入。

### 链路 2 · 运行 ✅

| 目标 | 通道 | 状态 |
|---|---|---|
| HTML/网页 | start_web_preview → proot 常驻 → `$PREVIEW_PORT` + `--directory /mnt/download/<项目名>` → 聊天页预览条 | ✅（proot 已装、/mnt/download 新 bind、全权已授） |
| Python/脚本 | run_shell（/mnt/download 只读镜像） | ✅ |
| AIUI 手机预览 | open_aiui_app(target=phone[,color=true]) 就地打包演示，**每次 open 从镜像重建** | ✅ |
| AIUI 眼镜 | install 打包推送 → open_aiui_app(target=glasses)，覆盖更新自动重解压 | ✅ |
| 彩色 | 眼镜物理单色；彩色=手机预览专属（lab-runtime §2.4 两步），系统提示已点名 | ✅ |

### 链路 3 · 对话修改 ✅（含一处小同步）

- **发现项目**：`list_files(scope=project)` 枚举镜像 / `list_my_aiui_apps` 含未安装的本地项目（今日核实 ✓）
- **读源码**：`read_code_file`（空 path=文件清单，带 path=全文）
- **改**：save_code_file 覆盖（镜像+公开副本同步、.ink 校验照跑）
- **生效**：HTML 重开预览；AIUI 手机预览 open 时重建；眼镜版 install 覆盖更新
- 同步：`delete_file` schema 描述补眼镜联动说明

### 链路 4 · 删除 ✅（本轮修复，见第八节）

对话 delete_file 整项目 = 镜像 + 公开副本 + 空目录修剪 + 登记注销 + 本地包(含彩包) + 眼镜尽力删；管理页补 deletePackageFiles；生成纪律⑤失败自清。

### 横切面 ✅

- **压缩**：滚动摘要保最近 N 轮；技能章节被压掉可重 load（只读预算 10 兜底）
- **轮次**：只读 10 / 动作 12 / 续段 2 / 总顶 22
- **审批**：delete_file=PROCEED（本机产物）、run_shell=LOCAL_SIDE_EFFECT、未信任 MCP=BLOCK
- **缓存**：任务内 schema 锁定不换档、规则/技能逐字节稳定

### 已知残余（记录，不阻塞）

1. 菜单探测轮也开思考 → 任务首响应慢一点（可接受）
2. deepseek-flash 对 `reasoning_effort` 的接受度未实测（有被拒撤回兜底）
3. 路由误判时任务轮沿用轮 0 的关思考决定（低概率）
4. 项目名拼写漂移无强校验（模型可能建重复项目；靠 list_files 先枚举缓解）
5. 超长任务技能章节被压缩后需重 load
