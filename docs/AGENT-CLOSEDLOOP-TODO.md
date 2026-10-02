# Agent 闭环审计 · 结案记录

> 来源：2026-09-29 三轴闭环审计（功能↔工具缺口 / AIUI 链路 / 执行层与路由）。
> **状态：全部结清（2026-09-30）** —— R1–R14 已修，§1 已拍板，**R2 已明确关闭（不做）**。
>
> 已修条目的实现细节 / 踩坑 / 验证过程**已从本文移除**（当时代码行号引用早因改动失效，
> 留着只会误导）。过程记录见 `.workbuddy/memory/2026-09-29.md` 与 `2026-09-30.md`，
> 以及各文件自身的 KDoc。**本文只保留决定与跨项不变式。**
> 复现整套审计：读 skill `rokidlab-agent-closedloop-audit`（三轴并行流程 + 已知坑）。

---

## 一、关闭决定（需要"表态"的两项）

### §1 隐私域：AIUI 页面对 hearing / vision 域的开放口径 → **B. 保持全开放**（2026-09-30 拍板）

- 第三方 `.aix` 页面**可以**调 `listen_ambient`（远场开麦）与 `look_at_view`（眼镜拍照）。
- `SESSION_AIUI_DOMAINS` 不含这两域只是**代码生成场景的 token 省减**，不是安全边界；
  `RULES §12.2` 的口径「基线全开放，只摘 shell / screen」与此一致。
- ⚠️ 要收窄时的唯一改法：改 `ai/approval/PageScope.kt` 的 `ALLOWED_DOMAINS`（**页面安全边界只有一个产地**），
  并同步 `RULES §12.2` 与 `assets/skills/aiui-dev/lab-runtime.md` §9。**别改 ToolRegistry 的域注释来"表态"。**

### R2 手机投屏启停 → **不做，关闭**（2026-09-30 用户决定）

- 底层现成：`mirror/PhoneMirrorService.kt` 的 `startService(context, glassesIp, port, resultCode, data, isBluetooth)`，
  唯一触发点在 `app/MainActivity.kt` 的 `MediaProjection` 回调。
- **关闭理由**：`start` 必须由用户在系统弹窗上授予 `MediaProjection`，工具**拿不到授权** ⇒
  只能退化成"让用户自己去点 UI"，与"让 agent 代办"的目标不符，价值不足。
- **不再列入待办**。若将来重启此需求，先定形态：只做 `stop`/`status`（不需授权），还是引导式 `start`（工具只负责把页面/入口摆到用户面前）。

---

## 二、已修项一览（一行一项，细节见当日 memory）

| # | 项 | 一句话 |
|---|---|---|
| §1 | hearing/vision 域口径 | 拍板 B；修正 `ToolRegistry` 矛盾注释（见上） |
| R1 | 眼镜应用管理 | `manage_glasses_app`（装/停/卸），EXTERNAL+BLOCK，`deviceControl` 回调闩锁桥接 |
| R3 | 眼镜文件（只读） | `list_glasses_files` / `read_glasses_file` / `download_glasses_file`；走**共享 ADB 会话**跑 shell，不用 `AdbFileManagerClient`（宿主特化） |
| R4 | 日历删 / 改 | 新写 `PhoneTools.deleteCalendarEvent` / `updateCalendarEvent`；拆两个工具：`delete_calendar_event`(EXTERNAL+BLOCK) / `update_calendar_event`(LOCAL) |
| R5 | MCP server 列表 | `list_mcp_servers`（只读）；增删仍走设置页 |
| R6 | 主动行为开关 | `manage_proactive`（list/toggle），只碰六个用户开关 |
| R7 | 审批等待逃出页窗 | `resolveAsk` 来源感知：页面 18s 上限 + **超时一律 Deny**；网关动态 join 预算 23s |
| R8 | 超时错误到不了页面 | 眼镜端同步超时补 `deliverToolResult`；手机端下行失败补 `LogCollector` |
| R9 | push 失败一律归"没连" | `AsrPushServer` 新增 `PushOutcome` + `pushDetailed`，按原因回传 |
| R10 | `list_tools` 下发 hidden 工具 | `ToolGateway.listAllowed()` 补 `.filterNot { it.hidden }` |
| R11 | 伪工具点名被静默吞 | 菜单轮把伪工具并入菜单文本 + 点名集合 |
| R12 | 限流注释与实现不符 | 按实况更正注释（限流在 Guard5 ⇒ 被 6/7 拒的调用**已扣**配额；不可把限流挪到确认之后） |
| R13 | 大结果无体积校验 | per-tool 超限 **fail-loud**；下行侧加 60 000 字符硬闸 + 短错误兜底 |
| R14 | MCP 双快照窗口 / 上限截断位置 | `states`+`origin` 合并为单个不可变 `Snapshot` 原子发布；上限改为校验通过后计数 |

---

## 三、跨项不变式（新增功能前必读，比条目本身更值钱）

1. **新增 `EXTERNAL_SIDE_EFFECT` 工具 ⇒ 必须跑 `ApprovalGateTest`**，同步 **E1** 的名单不变式并补一条摘要用例
   （像 E3b/E3c/E3d/E3e 那样）。**只编译不跑测试会漏** —— 2026-09-29 新增 `delete_knowledge_doc` 时就是这么漏的。
2. **AIUI 页面路径的确认窗口受页窗约束**：`ApprovalGate.PAGE_CONFIRM_TIMEOUT_MS = 18s`（页窗 25s − 开销 − 执行余量）
   + `ToolGateway` 总预算 23s；页面来源**超时未确认一律 Deny**（fail-closed，不看 `confirmPolicy`）。
   对话路径不受此限（确认 35s/40s 不变）。
3. **结果体积**：需要放宽的工具**必须登记**在 `ToolGateway.PER_TOOL_MAX_RESULT_CHARS`；
   未登记的超限走通用 8000 截断，登记后超限则 **fail-loud**（返回 oversize 错误，页面不会拿到半截 JSON）。
   下行硬闸 `CxrLHiRokidSession.DOWNLINK_HARD_LIMIT_CHARS = 60_000`。量 JSON 长度用 `toString().length`
   （`JSONObject.length()` 是**键个数**）。
4. **RokidLink 源码已改（R8 / R9）** ⇒ 打 phone-app 包**不要**加 `-x buildRokidLinkDebug/Release`，
   让 `assets/RokidLink.apk` 跟随最新构建。
5. **MCP 安全落点在准入、不在闸门**：必须 https（明文 http 仅回环）、新工具默认关、schema 不合规整个丢弃；
   **增删 server 仍走设置页**（不开放给模型）。改 `states` 一律经 `publish()`，别直接赋值（快照/派生表必须原子）。
6. **AIUI 页不可调的能力面**（`PageScope`）：摘掉整个 `shell` 域与 `screen` 域 + 黑名单
   `open_aiui_app` / `list_sessions` / `read_session` / `session_trace` / `research_subtask`；
   `manage_glasses_app`、`delete_calendar_event`、`delete_file` 等 BLOCK 档工具在页面路径**必然被拒**。
