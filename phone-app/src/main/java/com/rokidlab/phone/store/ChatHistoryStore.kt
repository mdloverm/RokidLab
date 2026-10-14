package com.rokidlab.phone.store

import com.rokidlab.phone.ai.AgentStep
import com.rokidlab.phone.ai.writeTextFileAtomically
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 聊天历史上限：超过则裁掉最旧的消息，防止长会话把内存/落盘文件撑爆 */
internal const val MAX_HISTORY = 500

/**
 * 聊天历史的落盘格式与回放逻辑。
 *
 * 纯 JVM 实现（除 `org.json` 外无 Android 依赖），因此可被 JVM 单测直接驱动
 * （见 `ChatHistoryStoreTest`）——[ChatStateHolder] 只负责"何时写、在哪个线程写"。
 *
 * **格式：JSONL** —— 每行一条完整消息。相比旧实现"每次追加都重写整份 JSON 数组"，
 * 追加只写一行，把 O(n²) 的写放大降为 O(n)。
 *
 * **同一 id 多行时后写覆盖先写**（位置沿用首次出现的位置）：[ChatStateHolder.finalizeLastAi]
 * 用完整回复修正流式消息时只需追加一行覆盖行，不必重写整份文件。
 *
 * **兼容旧格式**：首字符为 `[` 的文件按"整份 JSON 数组"解析（旧版 `chat_history.json`），
 * 调用方在冷启动时用 [rewrite] 把它迁移成 JSONL。
 *
 * **崩溃容错**：无法解析的行（例如写到一半被杀进程留下的半行）被跳过，其余历史照常恢复。
 */
internal object ChatHistoryStore {

    /** 私有项目镜像目录名（与应用内 `filesDir/aiui_projects/` 保持一致，仅用于旧数据展示名补全） */
    private const val PROJECTS_DIR = "aiui_projects"

    /** 序列化为一行 JSONL（不含换行符） */
    fun toLine(msg: ChatMsg): String = JSONObject().apply {
        put("id", msg.id)
        put("isUser", msg.isUser)
        put("content", msg.content)
        put("time", msg.time)
        put("isStatus", msg.isStatus)
        msg.imageUrl?.let { put("imageUrl", it) }
        // 本地文本附件：只存路径/名字/字数（正文在磁盘上，见 ChatMsg.filePath 的说明）
        msg.filePath?.let { put("filePath", it) }
        msg.fileName?.let { put("fileName", it) }
        msg.fileChars?.let { put("fileChars", it) }
        // 过程步骤：空列表不落字段（老版本读到也不出错；老历史没有该字段=无过程）
        if (msg.trace.isNotEmpty()) put("trace", traceToJson(msg.trace))
        // 本轮成本：整组全空时不落字段（老历史没有该字段=用量未知）
        usageToJson(msg.usage)?.let { put("usage", it) }
        // 轮号（单字母键，理由同 trace 的键名策略）
        msg.turn?.let { put("t", it) }
        // 失败标记：只在 true 时落键（老历史无此字段 = 正常消息）
        if (msg.failed) put("failed", true)
        // 用户反馈：1=有用 -1=没用；null 不落键 = 未标记
        msg.feedback?.let { put("fb", it) }
    }.toString()

    /**
     * 本轮成本 → JSON（单字母键，理由同 [traceToJson]）。
     *
     * 某个字段为 null 就**不落该键**：文件里出现的数字一律是服务端真给过的，
     * 读的时候"没有这个键" = 不知道，与"0"是两件事。
     */
    private fun usageToJson(u: MsgUsage?): JSONObject? {
        if (u == null) return null
        if (u.inputTokens == null && u.outputTokens == null &&
            u.modelCalls == null && u.elapsedMs == null && u.promptCacheHitTokens == null
        ) {
            return null
        }
        return JSONObject().apply {
            u.inputTokens?.let { put("i", it) }
            u.outputTokens?.let { put("o", it) }
            u.modelCalls?.let { put("c", it) }
            u.elapsedMs?.let { put("e", it) }
            u.promptCacheHitTokens?.let { put("h", it) }
        }
    }

    /** JSON → 本轮成本；缺字段保持 null（"不知道"，不是 0），全空则整组返回 null */
    private fun usageFromJson(o: JSONObject?): MsgUsage? {
        if (o == null) return null
        val u = MsgUsage(
            inputTokens = if (o.has("i")) o.optInt("i") else null,
            outputTokens = if (o.has("o")) o.optInt("o") else null,
            promptCacheHitTokens = if (o.has("h")) o.optInt("h") else null,
            modelCalls = if (o.has("c")) o.optInt("c") else null,
            elapsedMs = if (o.has("e")) o.optLong("e") else null,
        )
        if (u.inputTokens == null && u.outputTokens == null &&
            u.modelCalls == null && u.elapsedMs == null && u.promptCacheHitTokens == null
        ) {
            return null
        }
        return u
    }

    /**
     * 过程步骤 → JSON 数组。
     *
     * 用单字母键：一次复杂任务的过程可达十几步、每步都带参数与结果摘要，
     * 聊天历史是 JSONL 全量落盘（每条消息一行），键名膨胀会直接放大文件体积。
     * 键名只在 [traceFromJson] 消费，无对外兼容要求。
     */
    private fun traceToJson(steps: List<AgentStep>): JSONArray = JSONArray().apply {
        steps.forEach { s ->
            put(JSONObject().apply {
                put("k", s.key)
                put("i", s.kind.name)
                put("t", s.title)
                put("d", s.detail)
                put("s", s.state.name)
                // 思考全文（限长，见 AgentStep.MAX_FULL_TEXT_CHARS）；空则不写，省历史文件体积
                if (s.fullText.isNotEmpty()) {
                    put(
                        "f",
                        if (s.fullText.length > AgentStep.MAX_FULL_TEXT_CHARS) {
                            s.fullText.substring(0, AgentStep.MAX_FULL_TEXT_CHARS)
                        } else {
                            s.fullText
                        },
                    )
                }
                // 计划清单：[{t: 标题, s: 状态}]
                if (s.planSteps.isNotEmpty()) {
                    put("p", JSONArray().apply {
                        s.planSteps.forEach { ps ->
                            put(JSONObject().put("t", ps.title).put("s", ps.status))
                        }
                    })
                }
                // 文件产物：[{p: 绝对路径, l: 展示名, a: 动作}]
                // ⚠️ 必须落盘：否则**重启后产物芯片全部消失**（文件还在，用户却点不开了）——
                // 那种"新会话能看、旧会话不能看"的不一致比不做更让人怀疑数据丢了。
                if (s.artifacts.isNotEmpty()) {
                    put("r", JSONArray().apply {
                        s.artifacts.forEach { a ->
                            put(JSONObject().put("p", a.path).put("l", a.label).put("a", a.action.name))
                        }
                    })
                }
                // 命令类工具：命令本体 + **展示用**输出副本（见 AgentStep.command / output）。
                // 必须落盘，否则重启后命令卡退化成普通行（命令与输出都没了）。
                // ⚠️ output 只是本机展示副本，**不进模型上下文**；长度已由 AgentStep 截断。
                s.command?.takeIf { it.isNotBlank() }?.let { put("c", it) }
                s.output?.takeIf { it.isNotBlank() }?.let { put("o", it) }
                // apply_patch 展示 diff（AgentStep.diff）：与 output 同一条纪律 ——
                // 只进本地 UI 与聊天历史，不进模型上下文；长度已由 AgentStep 截断。
                s.diff?.takeIf { it.isNotBlank() }?.let { put("x", it) }
                // 终态耗时（毫秒）；RUNNING 步骤为 null 不写
                s.durationMs?.let { put("m", it) }
            })
        }
    }

    /** JSON 数组 → 过程步骤。无法识别的枚举值按安全默认降级（不因一条脏数据丢掉整段历史） */
    private fun traceFromJson(arr: JSONArray?): List<AgentStep> {
        if (arr == null || arr.length() == 0) return emptyList()
        val out = ArrayList<AgentStep>(arr.length())
        for (i in 0 until arr.length()) {
            val o = runCatching { arr.getJSONObject(i) }.getOrNull() ?: continue
            val key = o.optString("k")
            if (key.isEmpty()) continue
            val planSteps = o.optJSONArray("p")?.let { pa ->
                (0 until pa.length()).mapNotNull { pi ->
                    val po = pa.optJSONObject(pi) ?: return@mapNotNull null
                    val title = po.optString("t").trim()
                    if (title.isEmpty()) return@mapNotNull null
                    com.rokidlab.phone.ai.AgentPlan.PlanStep(title, po.optString("s", "pending"))
                }
            }.orEmpty()
            out.add(
                AgentStep(
                    key = key,
                    kind = runCatching { AgentStep.Kind.valueOf(o.optString("i")) }
                        .getOrDefault(AgentStep.Kind.TOOL),
                    title = o.optString("t"),
                    detail = o.optString("d"),
                    // ★ M5（2026-10-12 修）：未知 / 缺失的 state **不直接降级成 OK**。
                    //   OK 意味着"已确认成功"，把读不懂的状态画成绿勾等于伪造成功。
                    //   改为落到 RUNNING，交给 [settleStaleTrace]（唯一一处决定"结果未知怎么收场"
                    //   的文档化出口）按"上一轮没来得及收尾"统一处理。
                    state = runCatching { AgentStep.State.valueOf(o.optString("s")) }
                        .getOrDefault(AgentStep.State.RUNNING),
                    planSteps = planSteps,
                    fullText = o.optString("f"),
                    artifacts = artifactsFromJson(o.optJSONArray("r")),
                    command = o.optString("c").takeIf { it.isNotBlank() },
                    output = o.optString("o").takeIf { it.isNotBlank() },
                    diff = o.optString("x").takeIf { it.isNotBlank() },
                    durationMs = if (o.has("m")) o.optLong("m") else null,
                )
            )
        }
        return out
    }

    /**
     * 文件产物数组 → [AgentStep.Artifact]。
     *
     * 降级纪律（与枚举一致）：**没有路径的条目直接丢**，动作不认识按 [Action.TOUCH]（中性）。
     * 宁可少显示一个芯片，也不要给一个打不开的路径 —— 后者会让用户以为是文件丢了。
     */
    private fun artifactsFromJson(arr: JSONArray?): List<AgentStep.Artifact> {
        if (arr == null || arr.length() == 0) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = runCatching { arr.getJSONObject(i) }.getOrNull() ?: return@mapNotNull null
            val path = o.optString("p").trim()
            if (path.isEmpty()) return@mapNotNull null
            val stored = o.optString("l").trim()
            // 展示名补全：本次改动前落盘的旧数据只存了**文件名**（如 `index.html`），
            // 用户分不清是哪个项目；这里从绝对路径补出「项目名/项目内相对路径」。
            // 已有 `/` 的新数据直接用；不在项目镜像里的路径（下载目录等）保持原样。
            val fromProject = path.substringAfter("/$PROJECTS_DIR/", "")
            val label = when {
                stored.contains('/') -> stored
                fromProject.isNotEmpty() -> fromProject
                stored.isNotEmpty() -> stored
                else -> path.substringAfterLast('/')
            }
            AgentStep.Artifact(
                path = path,
                label = label,
                action = runCatching { AgentStep.Action.valueOf(o.optString("a")) }
                    .getOrDefault(AgentStep.Action.TOUCH),
            )
        }
    }

    /** 是否为旧的"整份 JSON 数组"格式 */
    fun isLegacyFormat(text: String): Boolean = text.trimStart().startsWith("[")

    /**
     * 解析落盘文本（自动识别 JSONL / 旧版 JSON 数组），按 id 去重后保留最新 [maxHistory] 条。
     * 非法行与非法条目被跳过。
     */
    fun parse(text: String, maxHistory: Int = MAX_HISTORY): List<ChatMsg> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList()
        val all = (if (isLegacyFormat(trimmed)) parseLegacyArray(trimmed) else parseJsonLines(trimmed))
            .map { it.settleStaleTrace() }
            // ★ M4（2026-10-12 修）：**状态行不进历史**。状态行（`isStatus`）的语义是"进行中"，
            //   重启后不可能还有"进行中"的动作；而它原先靠 `clearStatusLines` 在轮末清掉，
            //   该调用已随"状态行归位过程面板"改造而消失 ⇒ 盘上的残留（尤其老会话里的
            //   "正在识别文字…"）会永远立在聊天里。这里统一在回放时丢弃。
            .filterNot { it.isStatus }
        return if (all.size > maxHistory) all.subList(all.size - maxHistory, all.size).toList() else all
    }

    /**
     * 落盘里残留的 [AgentStep.State.RUNNING] / [AgentStep.State.AWAITING_CONFIRM]
     * 一律按「上一轮没来得及收尾」处理。
     *
     * ⚠️ 这里修的是**损坏数据**，不是某个 bug 的解法 —— 别拿"反正读的时候会兜住"当理由省掉收尾。
     * 正常收尾（`ChatStateHolder.finishTrace` / `finalizeTraceReply`）一定会把本轮锚点那条消息里
     * 的 RUNNING 全部写成终态；而 `ChatStateHolder.addImage` 会在**轮中途**带着 RUNNING 落一次盘
     * （之后由收尾那行覆盖 —— 同 id 后写覆盖先写）。
     * ⇒ **盘上最终仍留着 RUNNING，只可能是那一轮被杀进程 / 强退**。不清掉的话重启后
     * 「过程」卡片会永远转圈（"那一轮没结束"这件事不会自己变好）。
     * 归为 OK 而非 FAILED：我们并不知道那一步到底失败没有，标 FAILED 等于凭空编一个错误。
     *
     * ★ AWAITING_CONFIRM 一并处理（2026-10-06）：它同样表示"等一个外部回应"，
     * 同样会在强退后卡住（"等你确认…"永远停在那里 ⇒ 比 RUNNING 更迷惑，
     * 因为那条永远不会有后续回应来改写它）。
     */
    private fun ChatMsg.settleStaleTrace(): ChatMsg {
        // 与 [settleRunning] 同一口径：这两个状态都表示"没等到收尾"
        fun isStale(state: AgentStep.State) =
            state == AgentStep.State.RUNNING || state == AgentStep.State.AWAITING_CONFIRM

        if (trace.none { isStale(it.state) }) return this
        return copy(
            trace = trace.map {
                if (isStale(it.state)) it.copy(state = AgentStep.State.OK) else it
            },
        )
    }

    /** 读取历史文件；文件不存在或不可读时返回空列表 */
    fun readHistory(file: File, maxHistory: Int = MAX_HISTORY): List<ChatMsg> {
        if (!file.exists()) return emptyList()
        return parse(file.readText(), maxHistory)
    }

    /** 追加一行（JSONL 增量写）。父目录不存在时自动创建 */
    fun appendLine(file: File, line: String) {
        file.parentFile?.mkdirs()
        file.appendText(line + "\n")
    }

    /**
     * 用给定行整体重写文件（迁移旧格式 / 压实重复行 / 清空）。[lines] 为空时删除文件。
     *
     * ⚠️ 走**原子写**（2026-10-06 审计修）：这是典型的「读旧 + 拼新 + 写回」序列，
     * 提交后台线程后被 LMK 杀掉 / 磁盘满⇒ 中途留下**半截或 0 字节**文件。
     * 而 `readHistory` 对空文件 `parse` 得空 ⇒ **整个会话历史消失**，且没有任何报错。
     * 函数已存在（`writeTextFileAtomically`），不用它才需要理由。
     */
    fun rewrite(file: File, lines: List<String>) {
        if (lines.isEmpty()) {
            file.delete()
            return
        }
        file.parentFile?.mkdirs()
        writeTextFileAtomically(file, lines.joinToString(separator = "\n", postfix = "\n"))
    }

    private fun parseLegacyArray(text: String): List<ChatMsg> {
        val arr = runCatching { JSONArray(text) }.getOrNull() ?: return emptyList()
        val out = ArrayList<ChatMsg>(arr.length())
        for (i in 0 until arr.length()) {
            val o = runCatching { arr.getJSONObject(i) }.getOrNull() ?: continue
            fromJson(o)?.let { out.add(it) }
        }
        // ★ M2（2026-10-12 修）：旧数组格式也要按 id 去重 —— 迁移过来的文件里若同一 id 出现多次
        //   （历史上被追加过两次），不去重会在聊天流里显示成**两条一模一样的消息**。
        return dedupeById(out)
    }

    /**
     * 按 [ChatMsg.id] 去重：后写覆盖先写，位置沿用首次出现的位置；id ≤ 0 的行无法识别身份，
     * 按独立条目保留（[fromJson] 已挡掉既无 id 又无 content 的脏行）。
     *
     * JSONL 与旧数组格式共用同一份去重语义（M2，2026-10-12），避免两条路径行为不一致。
     */
    private fun dedupeById(msgs: List<ChatMsg>): List<ChatMsg> {
        val out = ArrayList<ChatMsg>(msgs.size)
        val indexById = HashMap<Long, Int>()
        for (m in msgs) {
            val at = if (m.id > 0L) indexById[m.id] else null
            if (at != null) {
                out[at] = m
            } else {
                if (m.id > 0L) indexById[m.id] = out.size
                out.add(m)
            }
        }
        return out
    }

    /** 回放 JSONL：后写覆盖先写（位置不变），无 id 的行按独立条目保留 */
    private fun parseJsonLines(text: String): List<ChatMsg> {
        val raw = ArrayList<ChatMsg>()
        for (line in text.lineSequence()) {
            val t = line.trim()
            if (t.isEmpty()) continue
            val o = runCatching { JSONObject(t) }.getOrNull() ?: continue
            fromJson(o)?.let { raw.add(it) }
        }
        return dedupeById(raw)
    }

    private fun fromJson(o: JSONObject): ChatMsg? {
        // 既无 id 又无 content 视为脏数据（如 `{}` 或被截断的残行）
        if (!o.has("id") && !o.has("content")) return null
        return ChatMsg(
            id = o.optLong("id", 0L),
            isUser = o.optBoolean("isUser", false),
            content = o.optString("content", ""),
            time = o.optString("time", ""),
            isStatus = o.optBoolean("isStatus", false),
            imageUrl = o.optString("imageUrl", "").ifBlank { null },
            trace = traceFromJson(o.optJSONArray("trace")),
            usage = usageFromJson(o.optJSONObject("usage")),
            turn = if (o.has("t")) o.optInt("t") else null,
            // 本地文本附件（用户上传 / AI 产出）：键名统一 file* 前缀，与 imageUrl 分开
            filePath = o.optString("filePath", "").ifBlank { null },
            fileName = o.optString("fileName", "").ifBlank { null },
            fileChars = if (o.has("fileChars")) o.optInt("fileChars") else null,
            failed = o.optBoolean("failed", false),
            feedback = if (o.has("fb")) o.optInt("fb") else null,
        )
    }
}
