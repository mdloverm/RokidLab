package com.rokidlab.phone.store

import com.rokidlab.phone.ai.AgentStep
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

    /** 序列化为一行 JSONL（不含换行符） */
    fun toLine(msg: ChatMsg): String = JSONObject().apply {
        put("id", msg.id)
        put("isUser", msg.isUser)
        put("content", msg.content)
        put("time", msg.time)
        put("isStatus", msg.isStatus)
        msg.imageUrl?.let { put("imageUrl", it) }
        // 过程步骤：空列表不落字段（老版本读到也不出错；老历史没有该字段=无过程）
        if (msg.trace.isNotEmpty()) put("trace", traceToJson(msg.trace))
        // 本轮成本：整组全空时不落字段（老历史没有该字段=用量未知）
        usageToJson(msg.usage)?.let { put("usage", it) }
        // 轮号（单字母键，理由同 trace 的键名策略）
        msg.turn?.let { put("t", it) }
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
            u.modelCalls == null && u.elapsedMs == null
        ) {
            return null
        }
        return JSONObject().apply {
            u.inputTokens?.let { put("i", it) }
            u.outputTokens?.let { put("o", it) }
            u.modelCalls?.let { put("c", it) }
            u.elapsedMs?.let { put("e", it) }
        }
    }

    /** JSON → 本轮成本；缺字段保持 null（"不知道"，不是 0），全空则整组返回 null */
    private fun usageFromJson(o: JSONObject?): MsgUsage? {
        if (o == null) return null
        val u = MsgUsage(
            inputTokens = if (o.has("i")) o.optInt("i") else null,
            outputTokens = if (o.has("o")) o.optInt("o") else null,
            modelCalls = if (o.has("c")) o.optInt("c") else null,
            elapsedMs = if (o.has("e")) o.optLong("e") else null,
        )
        if (u.inputTokens == null && u.outputTokens == null &&
            u.modelCalls == null && u.elapsedMs == null
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
                    state = runCatching { AgentStep.State.valueOf(o.optString("s")) }
                        .getOrDefault(AgentStep.State.OK),
                    planSteps = planSteps,
                    fullText = o.optString("f"),
                )
            )
        }
        return out
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
        return if (all.size > maxHistory) all.subList(all.size - maxHistory, all.size).toList() else all
    }

    /**
     * 落盘里残留的 [AgentStep.State.RUNNING] 一律按「上一轮没来得及收尾」处理。
     *
     * ⚠️ 这里修的是**损坏数据**，不是某个 bug 的解法 —— 别拿"反正读的时候会兜住"当理由省掉收尾。
     * 正常收尾（`ChatStateHolder.finishTrace` / `finalizeTraceReply`）一定会把本轮锚点那条消息里
     * 的 RUNNING 全部写成终态；而 `ChatStateHolder.addImage` 会在**轮中途**带着 RUNNING 落一次盘
     * （之后由收尾那行覆盖 —— 同 id 后写覆盖先写）。
     * ⇒ **盘上最终仍留着 RUNNING，只可能是那一轮被杀进程 / 强退**。不清掉的话重启后
     * 「过程」卡片会永远转圈（"那一轮没结束"这件事不会自己变好）。
     * 归为 OK 而非 FAILED：我们并不知道那一步到底失败没有，标 FAILED 等于凭空编一个错误。
     */
    private fun ChatMsg.settleStaleTrace(): ChatMsg {
        if (trace.none { it.state == AgentStep.State.RUNNING }) return this
        return copy(
            trace = trace.map {
                if (it.state == AgentStep.State.RUNNING) it.copy(state = AgentStep.State.OK) else it
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

    /** 用给定行整体重写文件（迁移旧格式 / 压实重复行 / 清空）。[lines] 为空时删除文件 */
    fun rewrite(file: File, lines: List<String>) {
        if (lines.isEmpty()) {
            file.delete()
            return
        }
        file.parentFile?.mkdirs()
        file.writeText(lines.joinToString(separator = "\n", postfix = "\n"))
    }

    private fun parseLegacyArray(text: String): List<ChatMsg> {
        val arr = runCatching { JSONArray(text) }.getOrNull() ?: return emptyList()
        val out = ArrayList<ChatMsg>(arr.length())
        for (i in 0 until arr.length()) {
            val o = runCatching { arr.getJSONObject(i) }.getOrNull() ?: continue
            fromJson(o)?.let { out.add(it) }
        }
        return out
    }

    /** 回放 JSONL：后写覆盖先写（位置不变），无 id 的行按独立条目保留 */
    private fun parseJsonLines(text: String): List<ChatMsg> {
        val out = ArrayList<ChatMsg>()
        val indexById = HashMap<Long, Int>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val o = runCatching { JSONObject(line) }.getOrNull() ?: continue
            val msg = fromJson(o) ?: continue
            val at = if (msg.id > 0L) indexById[msg.id] else null
            if (at != null) {
                out[at] = msg
            } else {
                if (msg.id > 0L) indexById[msg.id] = out.size
                out.add(msg)
            }
        }
        return out
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
        )
    }
}
