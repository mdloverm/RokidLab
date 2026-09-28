# 乐奇 Agent 能力专项评估：技术新旧度 · 大厂对标 · 实现合理性

> 评估日期：2026-09-26 · 对象：乐奇 AI Agent 全链路（AiConversationService 主循环 + ai/ 子系统 + RokidLink 语音链路）
> 对标对象：OpenAI（GPT/Realtime/Operator）、Anthropic（Claude/MCP/Agent Skills/computer use）、国内（豆包手机助手 / AutoGLM / Kimi / 通义 Qwen-Agent）、Manus 类通用 Agent
> 证据均来自代码勘察（文件:行号），与 `docs/AGENT-ROADMAP-DSH-ALIGNMENT.md` 已规划项做了甄别，避免把"已规划"误判为"缺失"。

---

## 一、总体结论

**Agent 工程成熟度：移动端第一梯队；协议面（Chat Completions 基础子集）落后云端 Agent 平台一代；多个"落后项"实为端侧约束下的合理取舍。**

三句话概括：
1. **能力盘点不输**：工具循环预算分账、RRF 混合检索、渐进披露技能、proot 沙盒执行、无障碍屏幕操作、事件流可回放——这些在"大厂 agent"里也多数只占一两样，乐奇全有，且是真机事故迭代出来的实战货。
2. **协议面落后一代**：structured output、tool_choice、精确 tokenizer、MCP OAuth 均缺位；请求前缀不稳定导致**白白浪费 DeepSeek 自动缓存 ~98% 的折扣**——这是最划算的一处修复。
3. **语音是最落后的一环，也是约束最死的一环**：半双工轮转 + 伪 barge-in 对比 OpenAI Realtime / 豆包端到端语音差一代，但根因是"眼镜整机无网络 + 官方链路独占音频"，属架构性限制而非实现偷懒。

---

## 二、逐功能评估（四档分类）

### A 档：实现合理，技术不落后（部分领先）

| 功能 | 现状（证据） | 对标评价 |
|---|---|---|
| 工具循环预算分账 | 动作轮≤6 / 只读轮≤8 / 总硬顶 14 + 自动续做 1 + 兜底总结轮≤3（`AiConversationService.kt:81-110`） | 业界 agent 普遍裸跑 max_turns，**分账设计（动作/只读分开计预算）是超出大厂公开做法的** |
| 并行工具调用 | 一轮内多个 tool_calls 用 CountDownLatch 并发执行、按序回填（:1304-1402） | 对齐 OpenAI parallel function calling；缺的只是 `parallel_tool_calls` 开关字段 |
| 流式工具调用增量解析 | `SseStreamAccumulator` 按 index 分片拼 name/arguments，缺 id 兜底生成（`OpenAiService.kt:881-916`） | 标准能力，实现正确且处理了边界（缺 id、usage 末包） |
| 模型能力探测 | 三级证据链 PROBED>DECLARED>PRESET>ASSUMED，含 contextWindow/supportsTools/supportsImage（`ModelPresets.kt:6-9`） | 大多数集成靠硬编码表；这一套在多Provider兼容性上明显更强 |
| 安全闸门 | 七级 ApprovalGuard（求值顺序即安全顺序）+ NetGuard（**先 DNS 解析再验 IP** 防域名指向内网、逐跳重定向复查、proot 旁路如实声明） | SSRF 防护做到 DNS-first + redirect 逐跳复检，属生产级；fail-open 已自知（`McpRegistry.kt:28`） |
| 本地 RAG | BM25(k1=1.2) + 向量 RRF 融合、向量懒回填故障降级、流式导入 8KB 窗口（`KnowledgeBase.kt:100-126`） | 与业界主流（hybrid + RRF）一致；SQLite 无 ANN 用暴力扫描 + 6000 块上限是端侧合理约束 |
| 技能系统 | 三层渐进披露：清单注入 → load_skill 全文 → load_skill_section 分章（`SkillRegistry.kt:14-46`） | 概念对齐 Anthropic Agent Skills；差异 = 技能是纯文本不可带可执行脚本——**在移动端白名单工具模型下这是更安全的选择，不算落后** |
| 沙盒代码执行 | proot 自持 shell 工具（`ShellToolProvider` 2 工具） | 等效 code interpreter 形态，端侧落地；大厂云端 agent 才普遍有这个 |
| 屏幕操作 | 无障碍 screen 域 5 工具（read/tap/swipe/press_key/type_text，~333ms/张） | 手机版 computer use；对比豆包手机助手/AutoGLM 的屏幕理解路线，乐奇是"无障碍执行"路线，**不落后** |
| 可观测性 | 事件流 jsonl 全程落盘（agent_sessions/<id>.jsonl）+ "过程"卡片含 token 成本 | "Model-visible means logged"对齐 DSH；大厂闭源产品反而看不到这层 |
| 评测 | 纯 JVM 金标（SSE 重放安全 / 工具声明完整性 / 提示词路由契约，A1-A6+B1-B7+C1-C10） | 锁确定性环节的做法正确（见 C 档第 7 条缺 LLM-as-judge） |

### B 档：实现合理但技术选型保守（可择机现代化）

| 功能 | 现状 | 问题与方向 |
|---|---|---|
| 主循环线程模型 | 整个工具循环跑单个 `namedThread` + `aiSendLock` 全程串行，取消靠 @Volatile 代际号轮询（`AiConversationService.kt:621,678,198-206`） | 现代做法 = 结构化并发（协程 Job 取消、可并行会话）。**但"真机上出问题可排查"的论点成立**，且代际号语义已被 20+ 处事故打磨过——建议中期改造而非急改 |
| JSON 构造 | org.json 手拼请求体 + `ToolSchemaValidator` 本地自检（每进程一次） | 保守但有护栏；kotlinx.serialization 是方向（编译期校验让 Validator 退休） |
| 上下文压缩摘要 | 滚动摘要**不调模型**：最旧一轮折一行、首句截 60 字（`BasicCompactionEngine.kt:15-18`） | 成本 0、避免窗口将满时再发大请求——动机合理；代价是信息损失大（Claude Code auto-compact 用模型摘要）。建议：加可选开关，压缩时用便宜模型生成 ≤200 字摘要，失败回退现有首句法 |
| web search | 多引擎链 Bing RSS → Bing HTML → 360，全免 Key 正则解析（`WebTools.kt:93-146`） | 全部免 Key 是亮点，但正则解析 HTML **脆弱**（引擎改版即坏）；建议加可选商用 API（博查/Tavily/SerpAPI）作为引擎链头 |
| Markdown 渲染 | 自研零依赖流式解析器，未闭合围栏整体按代码渲染（`ChatMarkdown.kt:31-61`） | "不引库防流式闪跳"的理由成立，表格/公式/Mermaid 都有；短板仅**代码块无语法高亮** |
| MCP 客户端 | 自写 JSON-RPC，仅 Streamable HTTP（2025-06-18 版），双形态响应兼容，无官方 SDK（`McpClient.kt`） | Android 端省略 stdio **合理**（无桌面进程模型）；缺的是 **OAuth**——MCP 官方授权已成 2026 主流，接需要 OAuth 的 server 目前不可能 |

### C 档：落后/缺失且有实际代价（优先补）

1. **请求前缀不稳定 → 浪费服务端自动缓存（最划算的修复）**
   DeepSeek 对重复前缀自动缓存，cache-hit 输入约为 miss 价的 **2%**（V4 Flash $0.007 vs $0.22/M）；OpenAI 同为自动。前提是**前缀逐字节稳定**。而乐奇每轮 `buildSystemMessage` 注入按当轮检索变化的 `<memories>` top-12 / `<lessons>` / 答题指令，工具列表也按域裁剪——**变的内容在前，等于每轮全价**。
   修法（不动架构）：稳定块前置（人设 + 工具 schema 顺序固定）→ 易变块后置（记忆、技能清单、当前输入）。顺手在 `TokenUsage`（`OpenAiService.kt:749-778`）里解析 DeepSeek 的 `prompt_cache_hit_tokens / prompt_cache_miss_tokens`，面板直接显示缓存命中——钱省没省一眼可见。

2. **无 structured output / response_format**（全 ai/ 目录 grep 零匹配）
   非工具 JSON 场景（AIUI 生成的 app.json 清单、结构化答题）全靠提示词约定 + `ReplySanitizer` 兜底。DeepSeek/OpenAI 都支持 `response_format`（json_object / json_schema）。app.json 生成是最该用的点——目前靠正则剥围栏，属于"用字符串手术弥补协议缺失"。

3. **无 tool_choice**（grep 零匹配）
   不能强制"必须先 search 再答"这类硬约束，目前靠提示词工具准则（GoldenEval C 系列在锁这个契约）。加 `tool_choice` 字段 + 端点 400 时撤回重试——`include_usage` 已有同款先例机制（`OpenAiService.kt:478-487`），照抄即可。

4. **无精确 tokenizer**
   1.6 字符/token 系数估算驱动压缩决策（`CompactionPolicy.kt:54`），不同 provider/模型误差漂移；服务端 usage 有真值但不用于压缩。低成本改进：用上一轮 usage 的 input_tokens/字符数**在线校准系数**，比引 tokenizer 库（体积代价）更划算。

5. **语音链路半双工（落后一代，但属架构性）**
   ASR = 眼镜官方识别拦截 `ASR_End`；TTS = 眼镜本地 ONNX；打断 = "ASR_End 拦截 + 本地 Exit 重试 + 双击广播"三件套，注释自认"尽力而为"（`AiTakeoverCoordinator.kt:314-348`）；**无 VAD**（全仓唯一 VAD 线索是一条注释）。对比 OpenAI Realtime / 豆包端到端语音（语音进语音出、信号级 barge-in、情感、300ms 级延迟）落后一代。但根因是眼镜整机无网络 + 官方链路独占音频——**不是实现偷懒**。演进路：手机端跑 VAD（若 CXR-L 能下行音频）或等 Rokid 官方开放实时通道；短期可做的是把"播完重开拾音"的 400ms 尾音缓冲做成可调，压缩连续对话轮转延迟。

6. **子 agent 收缩过紧**
   仅 1 个只读 `research_subtask`：并发 1、90s 硬顶、深度恒 1（`ReadOnlySubagent.kt:50-63`）。对比 Claude Code 并行子 agent / Manus 的任务分解，差距明显——但"并发模型请求在真机不可排查"的约束（自述 :30-32）真实存在。建议演进：并发 2-3 + 加 write 型子代理（受 EXTERNAL_SIDE_EFFECT 审批闸门管），DSH §4.3.2 已有规划，属"按图施工"而非新设计。

7. **update_plan 无执行引擎**
   `AgentPlan` 只做展示 + checkpoint，没有"todo 驱动重入"（对比 Manus 的 todo.md 驱动续跑）。当前 checkpoint 已支持中断恢复，差的是程序化按计划逐项推进，P2。

8. **MCP 无 OAuth**
   仅手配 headers（`McpClient.kt:244`）。2026 年需要 OAuth 的 MCP server（官方授权规范）越来越多，不补 OAuth 则 MCP 生态覆盖面持续缩水。P2（工作量不小：RFC 授权流程 + token 刷新 + 安全存储）。

### D 档：缺失但当前产品形态下可接受

- 后台异步任务 / 云端执行（Manus 类）：端侧 App 形态天然不同，非缺失。
- 图像/视频生成工具：产品定位不含。
- LLM-as-judge 在线质量评估：GoldenEval 锁确定性环节是对的，但模型回答**质量**无度量；DSH 文档已规划事件流只读工具，属已规划项。
- SSE 断点续传：协议本身不支持，自认（`OpenAiService.kt:422`），已有"未推送内容才重放"的安全边界，合理。

---

## 三、大厂对标差距矩阵

| 能力 | 乐奇现状 | OpenAI | Anthropic | 国内（豆包/AutoGLM/Kimi/通义） | 差距结论 |
|---|---|---|---|---|---|
| 工具调用 | 标准 function calling + 并发执行 + 本地 schema 自检 | + parallel 开关/strict 模式 | + tool_choice 强约束 | 同级 | 基本持平，缺 2 个字段 |
| 结构化输出 | 无 | strict json_schema | 同 | 同 | **缺** |
| Prompt 缓存 | 代码无（DeepSeek 服务端自动，但前缀不稳） | 自动 | 显式 cache_control | DeepSeek 自动/Kimi 托管 | **有免费折扣没吃到** |
| 上下文压缩 | 本地首句摘要 + 双向窗口 | 线程自动截断 | auto-compact（模型摘要） | 同左 | 落后，可低成本补 |
| 记忆 | SQLite 双类记忆 + RRF 检索注入 + 90 天过期 | ChatGPT Memory（黑盒） | 无内置 | 豆包/AutoGLM 有 | **不落后**，检索式注入是业界做法 |
| 技能系统 | 渐进披露三层，纯文本 | 无公开等价 | Agent Skills（可带脚本） | 无 | 概念对齐，脚本缺失是安全取舍 |
| 沙盒执行 | proot 本地 | 云端 code interpreter | 云端容器 | 云端 | 形态对齐，端侧更难 |
| 屏幕操作 | 无障碍 5 工具 | Operator（云端浏览器） | computer use API | 豆包手机助手/AutoGLM（同路线） | **不落后** |
| 实时语音 | 半双工 + 伪 barge-in，无 VAD | Realtime API（全双工） | 无官方语音 | 豆包端到端实时语音 | **落后一代（架构受限）** |
| 多 agent | 只读子代理 ×1 | 无公开 | 并行 subagent | Kimi 探索版多 agent | 落后但受真机排查约束 |
| 任务规划 | update_plan 展示 + checkpoint | 无公开 | Claude Code TodoWrite/Task | Manus todo 驱动 | 落后半步（缺执行引擎） |
| MCP | 自写客户端，Streamable HTTP，无 OAuth | 完整 | 完整（OAuth/stdio/sampling） | 陆续跟进 | 缺 OAuth |
| 安全闸门 | 七级 guard + NetGuard(DNS-first) | 服务端 | 权限模型 | 简单确认框 | **端侧实现超出大厂公开做法** |
| 评测 | 纯 JVM 金标 + 事件流回放 | 内部 eval 平台 | 内部 | 内部 | 缺 LLM-as-judge，已规划 |

---

## 四、行动清单（按 ROI 排序）

| # | 事项 | 量级 | 优先级 |
|---|---|---|---|
| 1 | ~~**前缀稳定化**吃 DeepSeek 自动缓存~~ **已修复（2026-09-26）**：system 头拆为逐字节稳定（`buildSystemMessage`），轮变内容（记忆/教训/技能/任务说明/RAG/答题/会话要求）移入 `buildContextTailMessage`，插在历史之后、本轮输入之前；`TokenUsage.promptCacheHitTokens` 解析 DeepSeek `prompt_cache_hit_tokens` / OpenAI `cached_tokens`，随 TurnEnd 落盘并在「过程」面板显示「缓存命中 X」；金标新增 C11（system 头稳定性）+ C12（lessons 闸门）锁回归 | 已完成 | **P0 ✅** |
| 2 | structured output：AIUI 的 app.json 生成等非工具 JSON 场景加 `response_format`（端点不支持时撤回重试，复用 include_usage 先例机制） | 1-2 天 | P1 |
| 3 | `tool_choice` 字段支持（同上撤回重试机制） | 半天 | P1 |
| 4 | 压缩摘要可选 LLM 化（便宜模型 ≤200 字，失败回退首句法） | 1 天 | P1 |
| 5 | search 引擎链加可选商用 API 头（博查/Tavily，用户填 Key 才启用） | 1 天 | P1 |
| 6 | tokenizer 系数在线校准（用上轮 usage 真值回算） | 半天 | P2 |
| 7 | 子 agent 放宽：并发 2-3 + write 型子代理过 EXTERNAL_SIDE_EFFECT 闸门（按 DSH §4.3.2 施工） | 2-3 天 | P2 |
| 8 | MCP OAuth 授权流程 | 1 周 | P2 |
| 9 | 主循环协程化（结构化并发替代代际号轮询） | 1 周+回归 | P2，中期 |
| 10 | update_plan 程序化执行引擎（todo 驱动重入） | 3-5 天 | P3 |
| 11 | 代码块语法高亮（自研渲染器上加简单 tokenizer 或引轻量库） | 1 天 | P3 |
| 12 | 手机端 VAD / 实时语音演进 | 待 Rokid 官方开放 | 观望 |

---

*评估方法：两个并行深勘察（ai/ 核心 10 项 + 语音/视觉/技能/评测/规划文档）+ 缓存计费现状核实（DeepSeek 自动缓存 cache-hit ≈ 2% 价、OpenAI 自动、Anthropic 显式断点、Kimi 托管式，2026-07~09 实测数据）。*
