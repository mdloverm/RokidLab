package com.rokidlab.phone.ai

import org.json.JSONArray
import org.json.JSONObject

/**
 * Agent 一轮回答里的**一步过程**（思考 / 工具调用），供手机端聊天窗口的「过程」时间线渲染。
 *
 * 背景：工具调用的进度文案其实一直有算（[ToolRegistry.statusText]），但只经
 * `AiConversationService.sendGlassesProgress` 推给**眼镜**，另外落进 App 内日志面板
 * （`CxrLHiRokidSession.onStatus` 默认只 `LogCollector.i`）——手机端对话窗口因此
 * 完全看不到「调用了什么工具、结果如何」，表现为一段长时间的黑盒等待。
 * 本类就是补上「过程」这条 UI 通道的数据载体。
 *
 * 交付方式是 **App 级全局汇聚点**（`AiConversationService.setAgentTraceSink`，在
 * `LabApplication.setCxrL` 注册），不是逐次调用传参 —— AI 入口有 4 条（打字 / 眼镜语音 ASR /
 * 拍照答题 / 定时自主任务），传参式设计漏一条那条路就完全没有过程显示（实测漏掉了眼镜语音）。
 *
 * **同 [key] 覆盖**：一次工具调用先发 RUNNING（"正在执行…"）、完成后用同一 key 再发一次
 * OK/FAILED 覆盖，UI 侧无需自己做状态机。约定 key：
 *  - 工具：`tool:<tool_call_id>`（每次调用唯一，多轮同名工具互不覆盖）
 *  - 思考：`think:<round>`（第几轮工具循环）
 *  - 知识库检索：`kb:auto`（一轮最多一条，是主循环的自动检索而非模型调用）
 *
 * 纯数据类，**不含任何 Android 依赖**，因此可被 `store/ChatHistoryStore`（纯 JVM，见其类注释）
 * 直接序列化，也可被 JVM 单测覆盖。
 */
data class AgentStep(
    /** 覆盖键：同 key 的后续步骤替换先前步骤（见类注释） */
    val key: String,
    val kind: Kind,
    /**
     * 主标题。
     *
     * - [Kind.TOOL]：工具名**原样**（`play_song` / `save_code_file`），不翻译 —— 它就是模型
     *   实际调用的标识符，用户在排查时需要对得上日志。
     *   ⚠️ **这里存的是标识符，不是显示名**：面向用户的名字由 UI 层用
     *   `ToolRegistry.displayNameOf()` 换成"当前时间" / "服务器名 · 工具名"。
     *   因此**不要把显示名写进来** —— 那会污染历史数据（改名/换语言后旧消息不会跟着变），
     *   也断掉与日志的对应关系。
     * - [Kind.THINKING] / [Kind.PLAN]：**恒为空**。文案由 UI 层按 [state] 本地化渲染。
     */
    val title: String,
    /** 次要说明：工具参数摘要 / 结果摘要 / 思考预览。可空 */
    val detail: String = "",
    val state: State = State.RUNNING,
    /**
     * [Kind.PLAN] 专用：结构化计划步骤（来自 `update_plan` 伪工具）。
     * UI 渲染成勾选清单而非工具时间线；空 = 非计划步骤。
     */
    val planSteps: List<AgentPlan.PlanStep> = emptyList(),
    /**
     * [Kind.THINKING] 专用：推理全文（仅结束态推送一次）。
     * [detail] 只存一行预览；用户在过程卡片上点击展开时看这份。落盘时由序列化层截断。
     */
    val fullText: String = "",
    /**
     * ★ **本步骤涉及的文件产物**（2026-10-10）：用户可点开预览的对象。
     *
     * ## 为什么必须是结构化字段，而不是把路径塞进 [detail]
     * 改造前 [detail] 是 `参数 → 结果` 的**拍平字符串**（上限 [MAX_DETAIL_CHARS] = 240）：
     * - `apply_patch` 的参数里含完整 diff，`save_code_file` 的参数里含源码正文 ——
     *   `file` 字段往往在 120 字符（[MAX_ARGS_CHARS]）内就被顶掉，**用户看不到"改了哪个文件"**；
     * - 正文里的裸路径既不可点，也无法确认它是否还存在。
     *
     * ⇒ 路径必须**独立于文案**存在，否则「产物可点开」这件事永远做不到。
     *
     * ## 落点约定
     * 产物**只在过程面板里内联**（可点芯片），不再另起对话气泡 ——
     * 一轮过程与它产出的东西应当出现在同一个可折叠区块里，否则文件卡像是凭空出现的。
     * 用户上传的文件仍走对话气泡（那是用户自己发的东西，不是 AI 的过程产物）。
     *
     * @see artifactOf 从工具参数里提取产物
     */
    val artifacts: List<Artifact> = emptyList(),
    /**
     * ★ **命令类工具专用：要执行的命令**（[COMMAND_TOOLS]：`run_shell` / `start_web_preview` / `run_script`）。
     *
     * 非命令类工具为 null，UI 走通用工具行；本字段落地前的旧历史也是 null ⇒ 自动退回旧渲染，
     * **不需要数据迁移**。同 key 覆盖：RUNNING 那次就带上命令，终态那次用同一 key 覆盖。
     *
     * ## 为什么必须独立成字段（而不是留在 [detail] 里）
     * 命令卡要把「命令」渲染成 monospace 正文、把「状态」渲染成独立徽标。而 [detail] 是
     * `参数 → 结果` 的拍平字符串，UI 侧去 split 它太脆 —— 命令本身就可能含 `→`（重定向）。
     */
    val command: String? = null,
    /**
     * ★ **命令类工具专用：展示用的输出副本**（2026-10-10，用户拍板「方案 B」）。
     *
     * ## ⚠️ 只进本地 UI 与聊天历史 JSONL，**绝不进模型上下文**
     * 模型看到的是工具原始回填（`buildMessages` 只发 `role/content`），与这份副本无关。
     * 所以展示多长的输出都**不增加任何 token** —— 展示设计与 token 成本是解耦的。
     *
     * 未做字段分离时，[detail] 只留结果首行（"退出码 0"），因为整段输出搬进过程行既刷屏
     * 又是内容泄漏；现在输出有了专属的**收起/展开**容器，才谈得上把全量放进来。
     *
     * ⚠️ 放进来的是**摘掉给模型看的提示段之后**的副本（见 [tool] 里的 [displayOutput] /
     * `SHELL_OUTPUT_TOOLS`）：`[工作目录]` / `[共享目录]` / `[服务日志]` 这类内部话术绝不外泄。
     *
     * 落盘前按 [MAX_OUTPUT_CHARS] 截断（防长日志把历史文件撑大）；null = 还在跑。
     */
    val output: String? = null,
    /**
     * ★ **apply_patch 专用：改动内容着色 diff**（2026-10-12 对标 Codex 的过程卡缺口）。
     *
     * 行首保留 `+`/`-`/空格 语义前缀，UI 据此着色（+绿 / −红 / 上下文灰）并折叠：
     * 收起时只显示 `+12/−4` 统计行。与 [output] 同一条纪律：**只进本地 UI 与聊天历史，
     * 不进模型上下文** —— 模型看到的是工具原始回填，这份副本不花任何 token。
     * 从参数 patch 解析（[com.rokidlab.phone.ai.PatchApplier.parseHunks]），
     * RUNNING 与终态都带（用户在执行中就能看改动）；落盘前按 [MAX_DIFF_CHARS] 截断。
     */
    val diff: String? = null,
    /**
     * ★ **本步骤实际耗时**（毫秒，仅终态填）。2026-10-12 对标 Codex：终态行右端显示
     * `1.2s` / `2m05s`，用户才能区分「一条命令 0.3s」和「装包 8 分钟」，
     * 也才能看出「这轮慢在哪一步」。RUNNING / AWAITING_CONFIRM 时为 null。
     */
    val durationMs: Long? = null,
) {
    /**
     * 一个可点开预览的文件产物。
     *
     * @param path 绝对路径（可直接喂 [java.io.File] 读；不存在时 UI 如实显示"文件已不在"）
     * @param label 展示名（一般是文件名或 `项目/相对路径`；不含目录前缀，避免撑开过程行）
     * @param action 这个产物对该文件的**动作**（读 / 改 / 新建 / 删 / 移），
     *   决定芯片图标与措辞。同一文件先读后改时，两条步骤各带各的动作。
     */
    data class Artifact(
        val path: String,
        val label: String,
        val action: Action = Action.TOUCH,
    )

    /** 文件动作（决定 UI 呈现，也决定无障碍描述） */
    enum class Action {
        /** 只读（读文件 / 搜索 / 列目录） */
        READ,

        /** 改内容（编辑 / 打补丁 / 写文件） */
        WRITE,

        /** 新建（此前不存在） */
        CREATE,

        /** 删除 */
        DELETE,

        /** 移动 / 重命名 */
        MOVE,

        /** 动作不明（工具未登记）—— UI 按中性呈现，不谎报 */
        TOUCH,
    }

    enum class Kind {
        /** 模型思考（含未开启长思考时的"等待模型响应"） */
        THINKING,

        /** 工具调用 */
        TOOL,

        /**
         * **知识库自动检索** —— 注意它**不是模型调用的工具**。
         *
         * 对话主循环在调模型**之前**会用当前提问去本地知识库自动检索、把命中段落注入
         * system 提示词（`AiConversationService` 的自动 RAG）。这条路径不产生 tool_call，
         * 因此既没有 `tool:<id>` 也没有工具名 —— 早先它就**在「过程」里完全不可见**：
         * 用户只看到"AI 直接回答了"，不知道答案其实是从自己导入的文档里查出来的。
         *
         * 单独一个 Kind 而不是复用 [TOOL]，是为了**如实**：TOOL 行意味着"模型决定调了工具"，
         * 排查"模型为什么没调检索工具"时把两者混在一起会误导。
         */
        KNOWLEDGE,

        /**
         * **任务计划清单**（`update_plan` 伪工具）：模型为多步任务立的步骤计划与进度。
         *
         * 同 `plan:auto` 键覆盖：模型每次更新计划都整体替换上一版（UI 永远只显示最新计划），
         * 渲染成勾选清单（pending/in_progress/done），不进工具时间线。
         */
        /**
         * **压缩历史**（上下文溢出恢复，2026-10-06 审计加）。
         *
         * 服务端以"输入超长"拒绝后，框架会压缩历史并原地重试同一轮。
         * 之前这个过程**只有一行 Log** —— 用户只觉得"这轮怎么这么慢"，
         * 不知道自己之前提过的那些历史被压掉了。
         * 这是**用户应该知道**的事（它改变了后续回答的基础），
         * 与 [TOOL]（模型自己调了什么）、[THINKING]（模型在想）都不同。
         */
        COMPRESS,

        /**
         * **收尾阶段**（上下文/轮次用尽后的落盘补跑，2026-10-10）。
         *
         * ## 为什么必须与 [THINKING] / [TOOL] 分开
         * 主循环结束后，框架还会跑最多 [SUMMARY_MAX_ROUNDS] 轮让模型把文件写完
         * （每轮最长 120s）。这段此前**完全静默** —— 过程卡片停在最后一个
         * `THINKING:OK`，用户以为已经答完了，而模型还在写文件，界面看着像空转。
         *
         * 但它也不该伪装成 [THINKING]（"模型在想"）或 [TOOL]（"模型自己决定调了这个工具"）：
         * 收尾轮是**框架强制发起**的，模型是被推着走的。
         * ⇒ 单独一个 Kind，如实说"框架在补跑收尾"。
         */
        SUMMARY,

        /**
         * **框架代做的事**（重试、路由降载、预算追加…）。
         *
         * 与 [TOOL] 的区别：TOOL 是模型的决定，框架行为不是。
         * 用户需要知道"为什么这轮比平时慢 / 为什么它突然换了工具列表"，
         * 但不该被当成"模型调用了什么"。
         */
        FRAMEWORK,

        /**
         * **上下文注入**（自动 RAG 之外的记忆 / 教训 / 技能 / 续做任务 / 预算说明）。
         *
         * 回答的是"**它凭什么知道这些**" —— 事件流里有 6 个 `CONTEXT_INJECT` origin，
         * 改造前只有知识库那一个在过程里可见，其余 5 类对用户完全隐形。
         * ⚠️ **只显示注入了什么来源，绝不显示注入内容**：那里面可能含外部文档正文，
         * 原样回显既刷屏又可能把外部内容重新摆到用户面前。
         */
        MEMORY,

        /**
         * **自省事件**（工具重复调用、标记泄漏、诉求与动作不匹配）。
         *
         * 这些是**框架发现的问题**，用户有权知道：
         * "它刚才把同一个文件读了 5 遍"比"它回答了"更能解释为什么这次这么慢/这么差。
         */
        REFLECT,

        PLAN,
    }

    enum class State {
        /** 进行中 */
        RUNNING,

        /**
         * **正在等用户点头确认**（2026-10-06 审计加）。
         *
         * ## 为什么必须与 [RUNNING] 区分开
         * 副作用工具执行前要过审批闸门，而闸门是**同步阻塞**的：
         * 眼镜 35s / 手机 40s / AIUI 页面 18s（[com.rokidlab.phone.ai.approval.ApprovalGate]）。
         * 原来这条步骤在闸门**之前**就以 [RUNNING] 发出，于是过程卡片上一个点
         * **转圈 40 秒且没有任何"等你确认"的字样** ——
         * 用户无法区分"正在拨号"和"正在等我点头"，会以为卡死，
         * 然后去点别的工具 ⇒ 可能触发第二个审批弹窗。
         *
         * 它等的恰恰是**最危险的副作用动作**（发短信/ 卸应用 / 删文档），
         * 让用户知道"现在轮到你"比让他知道"我在忙"重要得多。
         */
        AWAITING_CONFIRM,

        /** 成功结束 */
        OK,

        /** 失败（工具抛错、被安全策略拦截、重试耗尽） */
        FAILED,

        /**
         * **用户明确取消**（2026-10-06 审计加）。
         *
         * ★ 与 [FAILED] 区分的理由很实际：用户自己点的"取消"，回来看到一圈红点 + "失败"，
         * 等于告诉他"你的操作失败了" —— 而实际发生的是"你说了不做，它就没做"。
         * 后者不是错误，是**用户的决定**。
         */
        CANCELLED,
    }

    /**
     * 工具结果摘要的最大展示长度。
     *
     * 工具回给模型的结果可能很长（`dumpsys`、文件清单、源码全文），全量塞进 UI 会把
     * 对话列表撑成瀑布。这里只留开头一段供用户确认"确实拿到了东西"，详情看 App 内日志面板。
     */
    companion object {
        const val MAX_DETAIL_CHARS = 240

        /** 工具参数摘要长度：够看清 `{songName:"西厢"}` 这类关键入参即可 */
        const val MAX_ARGS_CHARS = 120

        /**
         * 推理全文落盘/内存展示上限。单轮长思考实测可达 ~2.5 万字符，
         * 全量进聊天历史文件会把 JSONL 撑大；超出的尾部对"回顾怎么想的"价值也低。
         */
        const val MAX_FULL_TEXT_CHARS = 8_000

        /**
         * 命令**展示副本**（[AgentStep.output]）的落盘上限（字符）。
         *
         * 为什么是 4000：够看清命令回显 / 报错栈的关键几屏，又不会让一条消息把 JSONL
         * 放大几百 KB —— 聊天历史是**整份读取**的，单行过大直接拖慢冷启动。
         * 超出即截断（UI 侧按 `length >= MAX_OUTPUT_CHARS` 判定并如实标注"已截断"）。
         */
        const val MAX_OUTPUT_CHARS = 4_000

        /**
         * apply_patch 的**展示用 diff**（[AgentStep.diff]）上限（字符）。
         *
         * 与 [MAX_OUTPUT_CHARS] 同一量级考量：够看清改动全貌（典型补丁几十行），
         * 又不会把 JSONL 撑爆；超出截断，UI 按 `length >= MAX_DIFF_CHARS` 标注"已截断"。
         */
        const val MAX_DIFF_CHARS = 4_000

        /**
         * 构造一条工具调用的过程步骤。
         *
         * @param callId 模型的 tool_call id，用作覆盖键
         * @param state 进行中 / 成功 / 失败
         * @param argsRaw 原始参数 JSON（模型可能给出非法 JSON，这里只做字符串截断，不做解析）
         * @param result 工具返回文本（仅结束态传入）
         * @param durationMs 实际耗时毫秒（仅终态由主循环传入；RUNNING 保持 null）
         * @param artifacts 产物覆盖（特殊工具如 `save_code_file` 的产物形态不在通用参数表里，
         *   由调用方算好传入；null = 按 [artifactOf] 自行提取）
         */
        fun tool(
            callId: String,
            name: String,
            state: State,
            argsRaw: String = "",
            result: String? = null,
            durationMs: Long? = null,
            artifactBaseDir: String? = null,
            artifacts: List<Artifact>? = null,
        ): AgentStep {
            val arts = artifacts ?: artifactOf(name, argsRaw, artifactBaseDir)
            val args = argsDisplay(name, argsRaw)
            // ★ 命令类工具走**专用命令卡**（2026-10-10 用户选「方案 B」）：
            //   命令与输出各自成字段，detail 留空 —— 再拼一句"命令 → 退出码"就是重复。
            val isCommand = name in COMMAND_TOOLS
            // ★ apply_patch 的展示 diff（2026-10-12）：RUNNING 与终态都带 ——
            //   用户在执行中就能点开看改了什么，不必等到完成。
            val diff = diffOf(name, argsRaw)
            val detail = when {
                isCommand -> ""
                // ★ 有产物芯片时，文件身份由芯片承载，detail 只留结果句
                //（2026-10-10 用户反馈：save_code_file 的参数原文把生成源码的开头漏进了过程卡片）。
                // READ 类（读文件/列目录/搜索）的结果**本身就是文件内容**——更不该进过程行，点芯片看。
                arts.isNotEmpty() && arts.all { it.action == Action.READ } -> ""
                arts.isNotEmpty() -> compact(result.orEmpty(), MAX_DETAIL_CHARS)
                result != null -> buildString {
                    if (args.isNotEmpty()) append(args).append(' ')
                    append("→ ").append(compact(resultFor(name, result), MAX_DETAIL_CHARS))
                }
                // save_code_file 执行中（结果还没有）：参数摘要后补一句「正在写入 N KB」——
                // ★ 不替代参数摘要（用户仍要知道在写哪个文件），只在后面追加大小。
                args.isNotEmpty() -> {
                    val size = if (name == "save_code_file") contentSizeOf(argsRaw) else ""
                    if (size.isEmpty()) args else "$args · 正在写入 $size"
                }
                else -> ""
            }
            return AgentStep(
                key = "tool:$callId",
                kind = Kind.TOOL,
                title = name,
                detail = detail,
                state = state,
                artifacts = arts,
                command = if (isCommand) args.ifBlank { null } else null,
                // 还在跑（result == null）时没有输出；终态才带展示副本。
                output = if (isCommand && result != null) {
                    if (name in SHELL_OUTPUT_TOOLS) {
                        // run_shell / run_script：结果是命令输出（退出码 / [标准输出] / [标准错误]）⇒
                        // 整段留作可展开副本，但必须先摘掉给模型的提示段（见 [displayOutput]）。
                        clipKeepLines(displayOutput(result), MAX_OUTPUT_CHARS).takeIf { it.isNotBlank() }
                    } else {
                        // start_web_preview：结果是"服务已启动 + [工作目录] + [服务日志] + 给模型的操作
                        // 提示"，没有可给用户看的命令输出 ⇒ 只留状态首行（不展开，也就无从外泄）。
                        firstLine(result).takeIf { it.isNotBlank() }
                    }
                } else {
                    null
                },
                diff = diff,
                durationMs = durationMs?.takeIf { state == State.OK || state == State.FAILED || state == State.CANCELLED },
            )
        }

        /**
         * apply_patch 的参数 → 展示用 diff 文本（行首 `+`/`-`/空格 前缀，UI 据此着色）。
         *
         * 复用 [PatchApplier.parseHunks] 而不是自己 split：解析规则（@@ 分段、裸空行=上下文、
         * 无前缀行宽容处理）必须与真正执行补丁的引擎**同源**，否则 UI 展示的改动与
         * 实际落盘的改动可能不一致。非 apply_patch / patch 为空 / 解析不出 → null。
         */
        private fun diffOf(name: String, argsRaw: String): String? {
            if (name != "apply_patch") return null
            val patch = runCatching { JSONObject(argsRaw).optString("patch") }.getOrNull()
            if (patch.isNullOrBlank()) return null
            val hunks = runCatching { PatchApplier.parseHunks(patch) }.getOrNull().orEmpty()
            if (hunks.isEmpty()) return null
            val sb = StringBuilder()
            outer@ for (h in hunks) {
                for (op in h.ops) {
                    val line = op.kind.toString() + op.text
                    if (sb.length + line.length + 1 > MAX_DIFF_CHARS) break@outer
                    sb.append(line).append('\n')
                }
            }
            return sb.toString().trimEnd('\n').takeIf { it.isNotEmpty() }
        }

        /**
         * save_code_file 参数 content 的人类可读大小（`680 B` / `3.2 KB`）。
         * 解析不出（非法 JSON / 无 content）→ 空串，detail 就不含大小，不编数字。
         */
        private fun contentSizeOf(argsRaw: String): String {
            val content = runCatching { JSONObject(argsRaw).optString("content") }.getOrNull()
            if (content.isNullOrEmpty()) return ""
            return if (content.length < 1024) "${content.length} B"
            else String.format(java.util.Locale.ROOT, "%.1f KB", content.length / 1024.0)
        }

        /**
         * 参数的展示形态。
         *
         * ## 两条硬规矩（都来自真机截屏的教训，2026-10-10「过程显示太乱」）
         * 1. **不拍 JSON 原文**：`{"command": "…"}` / `{"url": "…"}` 那层外壳既占宽又难读，
         *    多键挤成一行更是一片引号花括号 ⇒ 渲染成 `key: 值`；单键直接给**值本身**
         *    （用户参考的 TRAE 过程卡片显示的正是命令/路径本身，不是它的 JSON）。
         * 2. **不搬内容**：多行值（脚本 / diff / 源码）只留首行；内容键（[CONTENT_ARG_KEYS]）
         *    整个跳过 —— 宁可少显示，也不把源码开头漏进过程卡片。
         *
         * 白名单 [CONTENT_ARG_TARGET_KEYS] 命中时只认登记键；未登记的工具走通用**标量**提取，
         * 而不是退回 `compact(argsRaw)` 把 JSON 原文贴上来（真机就是这么乱起来的）。
         */
        private fun argsDisplay(name: String, argsRaw: String): String {
            if (argsRaw.isBlank()) return ""
            val obj = runCatching { JSONObject(argsRaw) }.getOrNull() ?: return ""
            val whitelist = CONTENT_ARG_TARGET_KEYS[name]
            val keys = whitelist ?: obj.keys().asSequence().toList()
            val kept = keys
                .filter { it !in CONTENT_ARG_KEYS }
                .mapNotNull { k ->
                    val raw = obj.opt(k) ?: return@mapNotNull null
                    // 数组 / 对象（如 `files: [...]`）拍进卡片又是一屏 JSON，跳过
                    if (raw is JSONObject || raw is JSONArray) return@mapNotNull null
                    val v = raw.toString().trim()
                    if (v.isBlank()) return@mapNotNull null
                    k to firstLine(v)
                }
            // 一个可用参数都没有（如只给了 content）⇒ 返回空，别渲染一个多余的 "{}"
            if (kept.isEmpty()) return ""
            // 白名单单键（如 run_shell 的 `command`）直接给**值本身**：标题已经说明了动作，
            // 再带一次键名（"command: …"）是噪音；未登记的工具没有这层把握，键名要留着。
            if (whitelist != null && kept.size == 1) return compact(kept[0].second, MAX_ARGS_CHARS)
            return compact(kept.joinToString(" · ") { (k, v) -> "$k: $v" }, MAX_ARGS_CHARS)
        }

        /**
         * **内容类**参数键：值就是文件正文 / 脚本 / diff 本身，任何工具都不显示。
         *
         * 与 [CONTENT_ARG_TARGET_KEYS] 分工不同：那张表说"这个工具该显示哪个键"，
         * 这张表说"任何工具都不许显示的键" —— 未登记的工具走通用提取时靠它兜住内容。
         */
        private val CONTENT_ARG_KEYS: Set<String> =
            setOf("content", "code", "diff", "patch", "script", "body")

        /**
         * 结果的展示形态。
         *
         * 命令类工具的返回是**批量文本**（`退出码 1\n\n[标准输出]\n…`）：把整段输出搬进过程
         * 卡片既刷屏又是内容泄漏（输出里往往就是文件清单 / 脚本回显）。只留首行
         * （"退出码 0"这种）就够表达成功与否，其余要看的去 App 内日志面板。
         */
        private fun resultFor(name: String, result: String): String =
            if (name in BULK_RESULT_TOOLS) firstLine(result) else result

        /** 取首个非空行（命令脚本 / 命令回显这类多行文本只留第一行） */
        private fun firstLine(raw: String): String =
            raw.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()

        /**
         * 截断但**保留换行**（[AgentStep.output] 要按行铺开）。
         *
         * 不能复用 [compact]：那个把 `\n` 全压成空格，一压就没法做"点开看多行输出"了。
         * 不追加"已截断"提示文案（那是用户可见文本，必须走语言包）—— UI 侧按
         * `output.length >= MAX_OUTPUT_CHARS` 自行判定并本地化标注。
         */
        private fun clipKeepLines(raw: String, max: Int): String {
            val text = raw.trim()
            if (text.length <= max) return text
            // 不切断 UTF-16 代理对（生僻字/emoji 会产生半个字符，Compose 渲染成方块）
            var end = max
            if (Character.isHighSurrogate(text[end - 1])) end -= 1
            return text.substring(0, end)
        }

        /**
         * 结果 → 用户可见的**输出副本**（[AgentStep.output]）：真实输出留住，给模型的提示摘掉。
         *
         * ## 为什么必须摘（项目硬约束：内部话术不得外泄）
         * shell 类工具在结果尾部统一追加 `[工作目录]` / `[共享目录]` / `[服务日志（最近几行）]`
         * 三段**给模型看的**提示 —— 里面直接写着"请引导其到「设置 → 本机执行环境」授权"这类
         * 内部话术（见 `ShellToolProvider.shareHint`）。原样搬进用户可见的展开区就是外泄。
         *
         * 以这些段头为界**截断**，保留前面的真实输出（`退出码 N` / `[标准输出]` / `[标准错误]`）。
         * 命令的 stdout 若自己也有一行以这些段头开头（极端巧合）会被提前截断 ——
         * 方向是"少显示"，可接受（宁可少显示，也不外泄）。
         */
        private fun displayOutput(raw: String): String {
            val kept = ArrayList<String>()
            for (line in raw.trim().lines()) {
                if (HINT_HEADERS.any { t -> line.trim().startsWith(t) }) break
                kept.add(line)
            }
            return kept.joinToString("\n").trim()
        }

        /**
         * 从工具参数里提取文件产物（**纯函数**，可 JVM 单测）。
         *
         * ## 为什么按"工具名 → 参数键 + 动作"这张表来，而不是通用地扫所有路径样字符串
         * 通用扫会踩两个坑：
         * 1. **把内容里的路径误当产物**：`save_code_file` 的 `content` 是源码，
         *    里面出现的 `import …/foo.kt` 会被当成"它操作了这个文件"；
         * 2. **分不清动作**：读到路径不等于改了它 —— 用户看到"修改 X"结果 X 没变，
         *    比不显示更糟（这是**撒谎**，不是漏报）。
         *
         * ⇒ 只认**登记在册的参数键**，动作由工具语义决定。未知工具返回空列表
         * （UI 不显示产物，而不是显示一个猜出来的动作）。
         *
         * @param baseDir 相对路径的解析基准（文件类工具的工作区根，见调用点）；
         *   为 null 时只接受绝对路径 —— **宁可少显示，也不给一个打不开的路径**。
         */
        fun artifactOf(
            name: String,
            argsRaw: String,
            baseDir: String? = null,
        ): List<Artifact> {
            val spec = ARTIFACT_SPECS[name] ?: return emptyList()
            if (argsRaw.isBlank()) return emptyList()
            val args = runCatching { JSONObject(argsRaw) }.getOrNull() ?: return emptyList()
            val out = ArrayList<Artifact>(2)
            for (key in spec.keys) {
                val raw = args.optString(key).trim()
                if (raw.isEmpty()) continue
                // 一个键可能是逗号分隔的多路径（apply_patch 的 path 允许列表形态）
                raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { one ->
                    val abs = absolutize(one, baseDir) ?: return@forEach
                    // 展示名 = **项目名 + 项目内相对路径**（`html-test/pages/index/index.ink`）：
                    // 只给相对路径时用户看到的可能是光秃秃的 `index.html`，多项目并行根本分不清归属
                    // （2026-10-10 用户反馈："现在光是文件名太短了，要求显示项目名目录"）。
                    // 工作区外的绝对路径只留文件名（整条 /sdcard/… 太长，会把过程行撑爆）。
                    val base = baseDir?.trimEnd('/')
                    val label = if (base != null && abs.startsWith("$base/")) {
                        val projName = base.substringAfterLast('/')
                        val rel = abs.removePrefix("$base/")
                        if (projName.isBlank()) rel else "$projName/$rel"
                    } else {
                        abs.substringAfterLast('/').ifBlank { abs }
                    }
                    val existing = out.indexOfFirst { it.path == abs }
                    // 同一文件多键命中（如 path 与 file 都指向它）：**动作取更强的那一个**
                    //（读 < 改 < 建/删），不能后写覆盖前写 —— 否则"读了又改"会显示成"读了"。
                    if (existing >= 0) {
                        if (actionRank(spec.action) > actionRank(out[existing].action)) {
                            out[existing] = out[existing].copy(action = spec.action)
                        }
                    } else {
                        out.add(Artifact(abs, label, spec.action))
                    }
                }
            }
            return out
        }

        /**
         * 相对路径 → 绝对路径；不是绝对路径且没有基准时返回 null（不编造路径）。
         *
         * ## 为什么用字符串拼接而不是 `File(base, rel).absolutePath`
         * 这些是**Android（Linux）路径**，分隔符恒为 `/`。用 `java.io.File` 拼接会引入
         * 宿主平台的分隔符 —— 单测跑在 JVM（开发机可能是 Windows）上时拼出
         * `\data\user\0\...\a.txt`，与设备上的真实路径**长得不一样**：
         * 既让纯逻辑测试无法断言，也会让将来任何在 JVM 侧比较路径的代码出错。
         * 路径语义归 Android，就不该借宿主的 File 来拼。
         */
        private fun absolutize(raw: String, baseDir: String?): String? {
            val trimmed = raw.trim().replace('\\', '/')
            if (trimmed.isEmpty()) return null
            if (trimmed.startsWith("/")) return trimmed.replace(Regex("/{2,}"), "/")
            val base = baseDir?.takeIf { it.isNotBlank() } ?: return null
            return (base.trimEnd('/') + "/" + trimmed).replace(Regex("/{2,}"), "/")
        }

        /**
         * 某工具的参数 → 产物的映射表：**键 + 动作**。
         *
         * ⚠️ **新增文件类工具必须在这里登记**，否则它的产物在「过程」里点不开
         * （不会报错、不会崩 —— 只是用户看不到可点的东西）。
         *
         * 📌 `rank` 由顶层函数 [actionRank] 现算，不存字段 ——
         *   存成 `val rank = ...` 会在 companion 初始化时前向引用尚未初始化的 `ARTIFACT_RANK`
         *   （Kotlin 禁止 val 前向引用）；`by lazy` 能编过但把一个常量查找变成每步一个
         *   Lazy 对象，收益为零。
         */
        private class ArtifactSpec(val keys: List<String>, val action: Action)

        private val ARTIFACT_RANK = mapOf(
            Action.READ to 0, Action.TOUCH to 1, Action.MOVE to 2,
            Action.WRITE to 3, Action.CREATE to 4, Action.DELETE to 5,
        )

        /** 动作强度（读 < 触碰 < 移动 < 改 < 建/删）；未登记的动作按最弱处理 */
        private fun actionRank(a: Action): Int = ARTIFACT_RANK[a] ?: 0

        /**
         * **参数里带源码/diff 全文**的工具 ⇒ 原始参数绝不进过程卡片，
         * 只按白名单提取目标键展示（见 [argsDisplay]）。
         * ⚠️ 新增内容类工具必须在这里登记，否则它的正文会漏进「过程」行
         *（2026-10-10 用户反馈：对话里出现了生成结果的内容）。
         */
        private val CONTENT_ARG_TARGET_KEYS: Map<String, List<String>> = mapOf(
            "save_code_file" to listOf("project", "file"),
            "edit_text_file" to listOf("path", "file"),
            "apply_patch" to listOf("path", "file"),
            "restore_file" to listOf("path", "file"),
            // 命令参数可能是整段 heredoc 脚本（python3 - <<'EOF' …）——
            // 只取 `command` 且只留首行，脚本体绝不进过程卡片（2026-10-10 用户反馈）。
            "run_shell" to listOf("command"),
            // 同一套「拿命令起东西」的参数：预览服务也是 `command` + `cwd`
            // （真机症状：不登记就落到兜底，整段 `{"command": …}` 原样进过程卡片）。
            "start_web_preview" to listOf("command"),
            // 脚本库「按名字跑」：命令卡里该显示脚本名本身（`demo.sh`），
            // 不登记会退化成泛化提取的 `name: demo.sh · timeoutSec: 60`。
            "run_script" to listOf("name"),
        )

        /**
         * **命令类工具** ⇒ 走专用「命令卡」渲染（shell 图标 + monospace 命令 + 状态徽标 + 可展开输出）。
         *
         * ⚠️ 新增"跑一条命令/脚本"的工具**必须在这里登记**，否则它只会渲染成普通工具行
         * （不报错、不崩 —— 只是没有那张卡，且 [AgentStep.command] 恒为 null）。
         */
        private val COMMAND_TOOLS: Set<String> = setOf("run_shell", "start_web_preview", "run_script")

        /**
         * 命令类工具里**结果真的是命令输出**的那些（`退出码` / `[标准输出]` / `[标准错误]`）
         * ⇒ 才给可展开的输出副本（见 [AgentStep.output] 的分支）。
         *
         * `start_web_preview` 刻意不在其中：它的返回是"服务已启动 + [工作目录] + [服务日志] +
         * 给模型的操作提示"，没有可给用户看的命令输出 —— 只留状态首行，展开即外泄。
         */
        private val SHELL_OUTPUT_TOOLS: Set<String> = setOf("run_shell", "run_script")

        /**
         * shell 类工具在结果尾部追加的**给模型看的**段落头（[displayOutput] 的截断界标）。
         *
         * 与 `ShellToolProvider.cwdEcho` / `shareHint`、`WebPreviewToolProvider` 的
         * `[服务日志（最近几行）]` 一一对应；那三处改了这里必须同步，否则提示会漏进用户可见区。
         */
        private val HINT_HEADERS: List<String> = listOf("[工作目录]", "[共享目录]", "[服务日志")

        /**
         * 结果是**批量文本**（命令回显 / 日志）的工具 ⇒ 结果只留首行（见 [resultFor]）。
         *
         * 单独一张表而不是"所有工具都取首行"：多数工具的结果本来就是一句话，取首行是恒等；
         * 但 `list_files` 这类的结果是"首行 + 条目列表"，一概取首行会把有用信息丢掉。
         */
        private val BULK_RESULT_TOOLS: Set<String> = setOf(
            // 返回体形如 `退出码 0\n\n[标准输出]\n…`
            "run_shell",
            // 返回体形如 `网页预览已启动：http://… \n\n[工作目录] …\n[服务日志（最近几行）] …`
            // —— 后两段是**给模型看的**提示（连"请把地址告诉用户"都在里面），
            // 原样搬进过程卡片就是刷屏 + 把内部提示漏给用户（2026-10-10 真机截屏发现）。
            "start_web_preview",
        )

        private val ARTIFACT_SPECS: Map<String, ArtifactSpec> = mapOf(
            // —— 读：键名按各工具 schema 实际声明的来（file/path/dir/target 混用）——
            "read_code_file" to ArtifactSpec(listOf("file", "path"), Action.READ),
            "read_text_file" to ArtifactSpec(listOf("path", "file"), Action.READ),
            "list_files" to ArtifactSpec(listOf("path", "dir"), Action.READ),
            "search_files" to ArtifactSpec(listOf("path", "dir"), Action.READ),
            "parse_document" to ArtifactSpec(listOf("path", "file"), Action.READ),
            // —— 改 ——
            "edit_text_file" to ArtifactSpec(listOf("path", "file"), Action.WRITE),
            "apply_patch" to ArtifactSpec(listOf("path", "file"), Action.WRITE),
            "restore_file" to ArtifactSpec(listOf("path", "file"), Action.WRITE),
            // —— 建/删/移 ——
            "delete_file" to ArtifactSpec(listOf("path", "file"), Action.DELETE),
            "move_file" to ArtifactSpec(listOf("to", "from"), Action.MOVE),
            "unzip_file" to ArtifactSpec(listOf("target", "path"), Action.CREATE),
            // ⚠️ `zip_files` / `save_summary_txt` **刻意不登记在这里**（2026-10-13 审计）：
            //   本表按**参数**推产物路径，而这两个工具的产物是「下载目录」里的新文件
            //   （名字来自 zipName / title，且经 sanitize），参数里没有可信的输出路径 ——
            //   硬登记入参（project / paths / title）会指向"输入"甚至不存在的路径，
            //   那正是铁律禁止的"指令指向不存在的对象"（用户点了芯片打不开）。
            //   ⇒ 这两者的产物由**工具自己在写成功后**经 [com.rokidlab.phone.ai.ProducedFileSink]
            //   回报真实绝对路径，主循环在 `runToolWorker` 里合并进芯片（同一个产物不会重复）。
        )

        /**
         * 构造一条「**正在等你确认**」的工具步骤（2026-10-06 审计加）。
         *
         * ## 为什么需要单独一条，而不是把 RUNNING 的 detail 改一下
         * 覆盖键必须与后续终态**相同**（`tool:<callId>`），否则闸门返回后
         * 追加的 OK/FAILED 会变成"新的一行"，用户看到"转圈那条 + 结果那条"两条。
         * 而 [tool] 会把 `result`拼进 detail（形如 `参数 → 结果`），
         * 等待阶段没有结果、也不该显示参数 —— 故单开一个工厂，
         * 只改 state 与 detail，其余（key/kind/title）完全一致 ⇒ 天然被后续终态覆盖。
         *
         * @param reason 给用户看的说明（为什么不问了：没人可问 / 通道不可用）
         */
        fun awaitingConfirm(
            callId: String,
            name: String,
            argsRaw: String = "",
            reason: String = "",
        ): AgentStep = AgentStep(
            key = "tool:$callId",
            kind = Kind.TOOL,
            title = name,
            // ★ 与 tool() 同规矩：内容类工具只展示目标键，原始参数（含源码/diff）绝不进过程卡片
            detail = compact(
                listOf(argsDisplay(name, argsRaw), reason).filter { it.isNotBlank() }
                    .joinToString(" "),
                MAX_ARGS_CHARS,
            ),
            state = State.AWAITING_CONFIRM,
            // ★ 等待期也带产物：审批可能阻塞 40 秒，这段时间用户最想知道"它要动哪个文件"。
            // 只显示路径、不显示"已完成" —— 动作由工具语义给，不代表已经发生。
            artifacts = artifactOf(name, argsRaw),
        )

        /**
         * 构造一条「**用户已取消**」的工具步骤（2026-10-06 审计加）。
         *
         * 与 [tool] 同 key ⇒ 覆盖掉那条转圈的步骤；state 区别在于 UI 上呈现为
         * 中性的"已取消"而不是红色失败（用户自己点的取消不是错误）。
         */
        fun cancelled(callId: String, name: String, reason: String = "你已取消本次操作"): AgentStep =
            AgentStep(
                key = "tool:$callId",
                kind = Kind.TOOL,
                title = name,
                detail = reason,
                state = State.CANCELLED,
            )

        /**
         * 构造一条**子代理内层**工具调用的过程步骤（2026-10-06 审计加）。
         *
         * ## 为什么需要它
         * 子代理（[com.rokidlab.phone.ai.subagent.ReadOnlySubagent]）最多跑 [MAX_ROUNDS_CAP] 轮，
         * 每轮内含多个只读工具（联网搜索 / 读网页 / 查知识库），此前**全部只进 logcat** ——
         * 用户在过程时间线上只看到外层一行 `research_subtask` 在转圈，最长 90 秒。
         * 这是唯一一条"用户能明确感到它在骗我"的路径：90 秒的阻塞操作，界面只有 1 行。
         *
         * @param key **必须带调用方作用域前缀**（主循环用 `sub:<callId>:<round>:<tool>`）：
         *   [tool] 用的是 `tool:<callId>`，若沿用同一 key 空间会互相覆盖，
         *   子代理里同名工具调两次会挤掉主循环那一行。
         */
        fun subStep(
            key: String,
            name: String,
            state: State,
            result: String? = null,
            // ★ 产物芯片（2026-10-12）：write 型子代理内层会写文件/打补丁，
            //   只有工具名没有芯片 = 用户看到"它在调 apply_patch"却不知道动了哪个文件。
            //   由调用方用 artifactOf(toolName, argsJson, baseDir) 现算后传入，
            //   这里不做解析（subStep 保持纯展示，解析失败宁可没芯片也不猜）。
            artifacts: List<Artifact> = emptyList(),
        ): AgentStep = AgentStep(
            key = key,
            kind = Kind.TOOL,
            title = name,
            detail = compact(result.orEmpty(), MAX_DETAIL_CHARS),
            state = state,
            artifacts = artifacts,
        )

        /**
         * 构造/更新一条思考步骤。
         *
         * @param round 第几轮工具循环，用作覆盖键（每轮一条）
         * @param detail 已累积的推理文本（思考未开启时为空）。**只作展示预览**：
         *   UI 原样显示在标题下方，不做字数统计、也不据此改文案。
         * @param state RUNNING=正在等待模型；OK=本轮已出结果
         * @param fullText 推理全文（仅 [state] = OK 时由调用方传一次）：UI 点击思考行展开查看。
         *   不落 UI 预览通道，由落盘层按 [MAX_FULL_TEXT_CHARS] 截断。
         */
        fun thinking(
            round: Int,
            detail: String = "",
            state: State = State.RUNNING,
            fullText: String = "",
        ): AgentStep = AgentStep(
            key = "think:$round",
            kind = Kind.THINKING,
            title = "",
            detail = compact(detail, MAX_DETAIL_CHARS),
            state = state,
            fullText = fullText,
        )

        /**
         * 构造一条**任务计划**步骤（`update_plan` 伪工具的结构化呈现）。
         *
         * 同 `plan:auto` 键：模型每更新一版计划就整体覆盖上一版，UI 只渲染最新清单。
         * [detail] 同步压一行进度摘要（如「2/5 · 正在查询天气」），保证不支持新 Kind 的
         * 旧渲染路径/眼镜单行通道也有可读文本。
         */
        fun plan(steps: List<AgentPlan.PlanStep>): AgentStep {
            val done = steps.count { it.status == "done" }
            val current = steps.firstOrNull { it.status == "in_progress" }
                ?: steps.firstOrNull { it.status == "pending" }
            val summary = if (current != null && done < steps.size) {
                "$done/${steps.size} · ${current.title}"
            } else {
                "${steps.size}/${steps.size}"
            }
            // ★ 截断后补占位：让"只显示了前8 步"这件事在 UI 上可见，而不是静默丢一半。
            // 取前 8 步而不是"最后 8 步"：计划是顺序执行的，当前进度在前面的可能性更高。
            val visible = steps.take(MAX_PLAN_STEPS_VISIBLE)
            val hidden = steps.size - visible.size
            val shown = if (hidden > 0) {
                visible + AgentPlan.PlanStep(
                    title = "还有 $hidden 步未显示（计划共 ${steps.size} 步）",
                    status = "pending",
                )
            } else {
                visible
            }
            return AgentStep(
                key = "plan:auto",
                kind = Kind.PLAN,
                title = "",
                detail = compact(summary, MAX_DETAIL_CHARS),
                state = if (done == steps.size) State.OK else State.RUNNING,
                planSteps = shown,
            )
        }

        /** 计划清单最多显示几步（超出补"还有 N 步"占位，见 [plan]） */
        const val MAX_PLAN_STEPS_VISIBLE = 8

        /**
         * 构造一条「压缩了历史」过程步骤（2026-10-06 审计加）。
         *
         * ## 为什么单独一个 Kind，而不混进语义
         * [THINKING] 的含义是"模型在想" —— 压缩不是模型行为，
         * 是**框架改变了后续回答的基础**（历史被压缩了）。
         * 用 THINKING 表达会让用户以为"它在思考"，而事实是"我为你的问题省了地方"。
         * 分开说才如实 —— 用户跟着追问时必须知道自己之前说过的东西被压掉了。
         */
        fun compressed(detail: String): AgentStep = AgentStep(
            key = "compress:auto",
            kind = Kind.COMPRESS,
            title = "",
            detail = compact(detail, MAX_DETAIL_CHARS),
            state = State.OK,
        )

        // ═══════════ 流程状态（App 自己做的事，不是模型行为） ═══════════

        /**
         * 构造一条「App 正在做某事」的状态步骤。
         *
         * 典型来源：拍照答题的"正在识别文字…"、OCR 模型下载百分比。
         *
         * ## 为什么用 [Kind.FRAMEWORK] 而不是 [TOOL] / [THINKING]
         * 这一切都是**App 本地流程**在推进（拍照 → 下载模型 → OCR），
         * 既不是模型调的工具，也不是模型在想。用 TOOL 会让用户去找对应的工具名，
         * 用 THINKING 会让他以为模型在推理 —— 两者都是**编造一个不存在的因果**。
         *
         * 同 [STATUS_STEP_KEY] 覆盖：高频进度原地更新，界面上始终只有一行。
         */
        fun statusProgress(text: String): AgentStep = AgentStep(
            key = STATUS_STEP_KEY,
            kind = Kind.FRAMEWORK,
            title = "",
            detail = compact(text, MAX_DETAIL_CHARS),
            state = State.RUNNING,
        )

        /**
         * 结清流程状态（拍照答题一轮结束时调用，否则那颗点会永远转下去）。
         *
         * @param ok false = 这一步**失败**了（拍照失败 / 没识别出文字）。
         *   ⚠️ 必须如实区分：把失败画成绿色对勾，比不显示更糟 ——
         *   用户会以为照片已经处理好了。
         */
        fun statusDone(text: String, ok: Boolean = true): AgentStep = AgentStep(
            key = STATUS_STEP_KEY,
            kind = Kind.FRAMEWORK,
            title = "",
            detail = compact(text, MAX_DETAIL_CHARS),
            state = if (ok) State.OK else State.FAILED,
        )

        /** 流程状态的覆盖键 */
        const val STATUS_STEP_KEY = "status:auto"

        // ═══════════ 本地程序运行（用户在代码卡/文件卡上点"运行"） ═══════════

        /**
         * 构造一条「启动了本地程序」的过程步骤。
         *
         * ## 为什么运行卡**留在对话区**而这条进过程
         * 运行卡的输出（stdout / 退出码）是**用户要看的东西**，是本次交互的产物；
         * 而"启动了它"是**动作**。
         * ⚠️ 但两者必须能对上号 —— 所以 key 带 [runId]，同一次运行只有一个覆盖键。
         */
        fun runStarted(label: String, runId: String): AgentStep = AgentStep(
            key = "run:$runId",
            kind = Kind.FRAMEWORK,
            title = "",
            detail = compact("正在运行（$label）", MAX_DETAIL_CHARS),
            state = State.RUNNING,
        )

        /** 结清某次运行（[tail] 是退出码/超时原因等收尾信息，可空） */
        fun runFinished(runId: String, tail: String): AgentStep = AgentStep(
            key = "run:$runId",
            kind = Kind.FRAMEWORK,
            title = "",
            detail = compact(tail.ifBlank { "运行结束" }, MAX_DETAIL_CHARS),
            state = State.OK,
        )

        // ═══════════ 收尾阶段（Kind.SUMMARY）═══════════

        /** 收尾占位的覆盖键（与该阶段实际工具的 `tool:<callId>` 不同空间，不互相覆盖） */
        const val SUMMARY_STEP_KEY = "summary:auto"

        /** 收尾阶段的标题标识（UI 本地化成"收尾中…"，这里不塞用户可读文案） */
        const val SUMMARY_STEP_TITLE = "finalize"

        /**
         * 构造一条「正在收尾」步骤（框架强制补跑落盘的占位，见 [Kind.SUMMARY]）。
         *
         * 同 [SUMMARY_STEP_KEY] 覆盖：收尾可能跑多轮，但用户看到的是**一条**"收尾中"，
         * 而不是一堆同名行 —— 那些行属于各工具自己（[tool] 的 `tool:<callId>`）。
         */
        fun summaryRunning(): AgentStep = AgentStep(
            key = SUMMARY_STEP_KEY,
            kind = Kind.SUMMARY,
            title = SUMMARY_STEP_TITLE,
            state = State.RUNNING,
        )

        /** 收尾结束（无论有没有产物）。[produced] 是这轮真正落盘的文件数。 */
        fun summaryDone(produced: Int): AgentStep = AgentStep(
            key = SUMMARY_STEP_KEY,
            kind = Kind.SUMMARY,
            title = SUMMARY_STEP_TITLE,
            // 如实给数：0 也要说出来（"收尾了但一个文件都没写"是用户该知道的事实）
            detail = compact("收尾阶段写入 $produced 个文件", MAX_DETAIL_CHARS),
            state = State.OK,
        )

        // ═══════════ 框架行为（Kind.FRAMEWORK）═══════════

        /**
         * 框架代做的一件事（重试、路由降载、预算追加…）。
         *
         * @param reason 简短中文短语（会原样显示，所以由调用方负责写成人话）
         * @param id 同一件事的稳定 id（同 id 覆盖，避免每次重试都新增一行）
         */
        fun framework(reason: String, id: String, state: State = State.OK): AgentStep = AgentStep(
            key = "fw:$id",
            kind = Kind.FRAMEWORK,
            title = "",
            detail = compact(reason, MAX_DETAIL_CHARS),
            state = state,
        )

        // ═══════════ 上下文注入（Kind.MEMORY）═══════════

        /**
         * 一条「注入了上下文」的步骤（回答"它凭什么知道这些"，见 [Kind.MEMORY]）。
         *
         * @param origin 来源标识（`memory` / `lesson` / `skills` / `task` / `budget`…）
         * @param summary **只放来源摘要，绝不放注入内容** ——
         *   注入体可能含外部文档正文，原样回显既刷屏又把外部内容摆回用户面前。
         */
        fun contextInjected(origin: String, summary: String): AgentStep = AgentStep(
            key = "inject:$origin",
            kind = Kind.MEMORY,
            title = "",
            detail = compact(summary, MAX_DETAIL_CHARS),
            state = State.OK,
        )

        // ═══════════ 自省（Kind.REFLECT）═══════════

        /**
         * 一条「框架发现了问题」的自省步骤（见 [Kind.REFLECT]）。
         *
         * @param trigger 稳定标识（`repeat_call` / `marker_leak` / `intent_mismatch`）
         * @param reason 给人看的说明 —— **必须写清"发现了什么"，不是"模型说了什么"**
         */
        fun reflect(trigger: String, reason: String): AgentStep = AgentStep(
            key = "reflect:$trigger",
            kind = Kind.REFLECT,
            title = "",
            detail = compact(reason, MAX_DETAIL_CHARS),
            state = State.OK,
        )

        /**
         * 构造一条**知识库自动检索**步骤（主循环的 RAG，不是模型调的工具，见 [Kind.KNOWLEDGE]）。
         *
         * 文案归属：标题由 UI 按 [state] 本地化（与 [thinking] 同规矩），这里只填**数据**——
         * 命中了哪几块（`《文档名》第N块`）或"库里有多少份却没匹配上"。
         * 这几条来源正是用户核对"答案是从哪来的"的依据，所以**不要**在 UI 侧再截断掉。
         *
         * @param hitCount 命中段数（0 = 库里有内容但没匹配上）
         * @param sources 命中来源标注（如 `《问.txt》第1块`）
         * @param docCount 知识库文档总数（未命中时用于说明"库里确实有东西"）
         */
        fun knowledge(
            hitCount: Int,
            sources: List<String> = emptyList(),
            docCount: Int = 0,
        ): AgentStep = AgentStep(
            key = "kb:auto",
            kind = Kind.KNOWLEDGE,
            title = "",
            detail = compact(
                if (hitCount > 0 && sources.isNotEmpty()) sources.joinToString("、")
                else if (docCount > 0) "库里 $docCount 份文档，未匹配到相关段落"
                else "未匹配到相关段落",
                MAX_DETAIL_CHARS,
            ),
            state = State.OK,
        )

        /**
         * 工具的返回是否应判定为失败。
         *
         * 工具契约是「回一句人话」而不是抛异常，所以失败只能按文案识别。只认几个**由框架
         * 自己产出**的确定性前缀/关键字（重试耗尽、被策略拦截），不按业务文案猜（"没有找到歌曲"
         * 这类属于正常返回，只是结果不理想，仍算 OK）。
         */
        fun isFailureResult(result: String?): Boolean {
            val r = result?.trim().orEmpty()
            if (r.isEmpty()) return true
            return r.startsWith("工具执行失败") ||
                r.contains("被安全策略拦截") ||
                r.startsWith("unknown tool") ||
                r.startsWith("未知工具") ||
                r.startsWith("tool '")
        }

        /** 压成一行并截断（换行会让「过程」卡片高度失控） */
        private fun compact(raw: String, max: Int): String {
            val oneLine = raw.replace('\n', ' ').replace('\r', ' ').replace(Regex("\\s+"), " ").trim()
            if (oneLine.length <= max) return oneLine
            // 不切断 UTF-16 代理对（生僻字/emoji 会产生半个字符，Compose 渲染成方块）
            var end = max
            if (Character.isHighSurrogate(oneLine[end - 1])) end -= 1
            return oneLine.substring(0, end) + "…"
        }
    }
}
