package com.rokidlab.phone.ai.session

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 事件流里的一条记录 = 事件 + 它在时间线上的位置。
 *
 * ★ 为什么 `seq` / `ts` 在这里而不在 [SessionEvent] 上：它们是**日志的属性**，
 * 不是事件的属性。同一个事件被记录两次本就会得到两个不同的 seq；
 * 拆开之后 `append` 不必回填事件字段（Kotlin 的 data class 做不到优雅回填），
 * "事件的业务含义"与"它在时间线上的位置"也不会在复制/比较时互相干扰。
 */
internal data class SessionRecord(
    val seq: Long,
    val ts: Long,
    val event: SessionEvent,
)

/**
 * 会话事件流的**追加式落盘**（对齐 deepseek-harness 的 session log）。
 *
 * 格式：JSONL，**每行一个自洽的记录**（每行都带 `v` 版本号）。这不是为了好看 ——
 * 是"写坏一行只丢一条"这条鲁棒性要求的直接推论：半行/脏行无法解析时跳过它即可，
 * 前后的事件照常回放。若把版本号放在文件头，一次半截写入就能让**整个文件**作废。
 *
 * 三条不变式（接线后由 `SessionProjection` 消费，因此必须钉住）：
 *  1. **只追加**，绝不改写已写行。压缩也不删行 —— 它追加一条 [CompactionSummary]
 *     声明"这些 seq 已被摘要覆盖"（见 [CompactionSummary] 的说明）。
 *  2. **seq 单调递增、会话内唯一**。由本类分配，调用方无法构造出重复 seq。
 *  3. **读得动旧文件**：解析失败的行、未知的 `type`、比当前 [FORMAT_VERSION] 更新的行
 *     一律**跳过**（不是抛异常）—— 旧版本 App 遇到新版本写的文件时，
 *     表现为"少看到几条事件"，而不是"打不开会话"。
 *
 * 纯 JVM（除 `org.json` 外无 Android 依赖），因此可被 JVM 单测直接驱动。
 */
internal class SessionLog(
    /** 事件流文件；父目录不存在时 `append` 自动创建 */
    private val file: File,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    companion object {
        /**
         * 当前格式版本。**落盘每行都带**。
         *
         * 升级规则（对齐 DSH 的"代际不可改名"）：将来格式不兼容时改这个数字，
         * 并且**写到新文件名**（如 `<id>.v2.jsonl`），旧文件原地保留 ——
         * 升级只做"加"，不做"改"，这样回退旧版本 App 时数据还在。
         */
        const val FORMAT_VERSION = 1

        /** 事件流文件所在子目录（相对 `filesDir`） */
        const val DIR_NAME = "agent_sessions"

        /**
         * 单个文本字段的落盘上限（字符）。
         *
         * 事件流是**只追加**的，一条超大内容会永久留在文件里（不像 UI 聊天记录那样
         * 会在下次结构性编辑时被重写掉），所以必须在这里设上限。
         * 超限时由写入方截断并置 `truncated = true`（见 [clip]）——
         * 截断必须**显式标记**，否则"模型看到的和文件里存的不一样"就无从判断。
         */
        const val MAX_FIELD_CHARS = 4000

        /** 按会话 id 定位事件流文件（落盘位置的唯一产地） */
        fun fileFor(dir: File, sessionId: String): File =
            File(File(dir, DIR_NAME), "$sessionId.jsonl")

        /**
         * 超长文本截断（[truncated] 同时返回，供事件的 `truncated` 字段使用）。
         *
         * 附尾标是为了**人看日志时一眼可见** —— 否则一段恰好被截在句子中间的内容，
         * 读起来像是"模型就输出了这么多"。
         */
        fun clip(text: String, max: Int = MAX_FIELD_CHARS): Pair<String, Boolean> {
            if (max <= 0 || text.length <= max) return text to false
            return text.take(max) + "…[truncated ${text.length - max} chars]" to true
        }

        // ===== 键名 =====

        private const val KEY_VERSION = "v"
        private const val KEY_SEQ = "seq"
        private const val KEY_TS = "ts"
        private const val KEY_TYPE = "type"
        private const val KEY_TURN = "turn"
        private const val KEY_STEP = "step"
        private const val KEY_SOURCE = "source"
        private const val KEY_TEXT = "text"
        private const val KEY_DETAIL = "detail"
        private const val KEY_REASON = "reason"
        private const val KEY_OUTCOME = "outcome"
        private const val KEY_CALL_ID = "callId"
        private const val KEY_NAME = "name"
        private const val KEY_ARGS = "args"
        private const val KEY_CONTENT = "content"
        private const val KEY_TRUNCATED = "truncated"
        private const val KEY_ORIGIN = "origin"
        private const val KEY_TRIGGER = "trigger"
        private const val KEY_SEQS = "seqs"
        private const val KEY_CLEAR_DIGEST = "clearDigest"
        private const val KEY_TURNS = "turns"
        private const val KEY_ERROR = "error"

        /** 用量三件套（可空：服务端没返回 usage 时**不落字段**，"有没有这个字段"没有歧义） */
        private const val KEY_PROMPT_TOKENS = "promptTokens"
        private const val KEY_COMPLETION_TOKENS = "completionTokens"
        private const val KEY_MODEL_CALLS = "modelCalls"
    }

    /** 已分配的最大 seq 缓存（-1 = 尚未从文件加载）。写操作与读操作都在 [lock] 内访问 */
    private var cachedLastSeq: Long = -1L

    private val lock = Any()

    // ═══════════════════════ 写 ═══════════════════════

    /**
     * 追加一个事件，返回带 seq/ts 的完整记录。
     *
     * seq 由本方法分配（调用方**无法**指定），因此"单调递增且唯一"不需要调用方配合。
     * 落盘失败（磁盘满/无权限）时抛异常 —— 静默吞掉会让调用方以为自己记下来了。
     */
    fun append(event: SessionEvent): SessionRecord {
        synchronized(lock) {
            val seq = lastSeqLocked() + 1
            val record = SessionRecord(seq, clock(), event)
            file.parentFile?.mkdirs()
            file.appendText(encode(record) + "\n")
            cachedLastSeq = seq
            return record
        }
    }

    // ═══════════════════════ 读 ═══════════════════════

    /** 已写入的最大 seq（空文件 = 0）。结果缓存，重复调用不重扫文件。 */
    fun lastSeq(): Long = synchronized(lock) { lastSeqLocked() }

    /**
     * 回放事件流。
     *
     * @param from 只返回 `seq >= from` 的记录（增量读取用；0 = 全部）
     */
    fun read(from: Long = 0L): List<SessionRecord> {
        val records = parse(readText())
        // 注意 from 过滤放在解析之后：坏行跳过是**解析期**的事，
        // 与"要读哪一段"无关 —— 否则 from 之后的第一条坏行会改变增量读的边界。
        return if (from <= 0L) records else records.filter { it.seq >= from }
    }

    /** 文件中是否还没有任何可解析的事件 */
    fun isEmpty(): Boolean = synchronized(lock) {
        if (cachedLastSeq > 0L) false else parse(readText()).isEmpty()
    }

    /** 删除整个事件流文件（清空会话记忆用；调用后 seq 从 1 重新开始） */
    fun clear() {
        synchronized(lock) {
            runCatching { file.delete() }
            cachedLastSeq = 0L
        }
    }

    // ═══════════════════════ 内部 ═══════════════════════

    private fun lastSeqLocked(): Long {
        if (cachedLastSeq >= 0L) return cachedLastSeq
        val last = parse(readText()).lastOrNull()?.seq ?: 0L
        cachedLastSeq = last
        return last
    }

    /** 读文件文本；不存在或不可读时返回空串（"没有历史"与"读不出来"都按空处理，不抛） */
    private fun readText(): String {
        if (!file.exists()) return ""
        return runCatching { file.readText() }.getOrDefault("")
    }

    // ═══════════════════════ 编解码（纯函数，可单测）═══════════════════════

    /** 编码为一行 JSONL（不含换行符） */
    internal fun encode(record: SessionRecord): String {
        val e = record.event
        val o = JSONObject().apply {
            put(KEY_VERSION, FORMAT_VERSION)
            put(KEY_SEQ, record.seq)
            put(KEY_TS, record.ts)
            put(KEY_TYPE, e.kind.wire)
            e.turn?.let { put(KEY_TURN, it) }
            e.step?.let { put(KEY_STEP, it) }
        }
        // 用作语句的 when 覆盖全部子类型；新增事件类型时这里会给出编译期提醒
        when (e) {
            is TurnStart -> o.put(KEY_SOURCE, e.source.wire)

            is TurnEnd -> {
                o.put(KEY_REASON, e.reason.wire)
                e.detail?.let { o.put(KEY_DETAIL, it) }
                // null 不落字段。文件里出现的用量一律是服务端真给过的数字，
                // 读的时候"没有这个字段"= 不知道，与"0 token"是两件事
                e.promptTokens?.let { o.put(KEY_PROMPT_TOKENS, it) }
                e.completionTokens?.let { o.put(KEY_COMPLETION_TOKENS, it) }
                e.modelCalls?.let { o.put(KEY_MODEL_CALLS, it) }
            }

            is UserMessage -> {
                o.put(KEY_TEXT, e.text)
                o.put(KEY_SOURCE, e.source.wire)
            }

            is AssistantAttempt -> {
                o.put(KEY_OUTCOME, e.outcome.wire)
                e.detail?.let { o.put(KEY_DETAIL, it) }
            }

            is AssistantMessage -> o.put(KEY_TEXT, e.text)

            is ToolCall -> {
                o.put(KEY_CALL_ID, e.callId)
                o.put(KEY_NAME, e.name)
                o.put(KEY_ARGS, e.args)
            }

            is ToolResult -> {
                o.put(KEY_CALL_ID, e.callId)
                o.put(KEY_NAME, e.name)
                o.put(KEY_CONTENT, e.content)
                // 默认值不落字段：文件里出现的 `truncated` 一律是 true，
                // "有没有这个字段"比"字段是不是 false"读起来更没有歧义
                if (e.truncated) o.put(KEY_TRUNCATED, true)
            }

            is ContextInject -> {
                o.put(KEY_ORIGIN, e.origin)
                o.put(KEY_CONTENT, e.content)
                if (e.truncated) o.put(KEY_TRUNCATED, true)
            }

            is CompactionStart -> o.put(KEY_TRIGGER, e.trigger)

            is CompactionSummary -> {
                o.put(KEY_SEQS, JSONArray(e.shadowedSeqs))
                o.put(KEY_TEXT, e.text)
                if (e.turnsCompressed != 0) o.put(KEY_TURNS, e.turnsCompressed)
            }

            is CompactionEnd -> e.error?.let { o.put(KEY_ERROR, it) }

            is VisibilityCut -> {
                o.put(KEY_SEQS, JSONArray(e.seqs))
                // 同 truncated：默认值不落字段，文件里出现 `clearDigest` 一律是 true，
                // "有没有这个字段"比"字段是不是 false"读起来更没有歧义
                if (e.clearDigest) o.put(KEY_CLEAR_DIGEST, true)
                o.put(KEY_REASON, e.reason)
            }
        }
        return o.toString()
    }

    /**
     * 解析一行。**任何形式的看不懂都返回 null**（跳过该行），绝不抛：
     *   - 空行 / 半截写入留下的残行 → JSON 解析失败
     *   - 缺少 `v` / `seq` / `type` → 不是本格式
     *   - `v` 比当前版本新 → 未来格式，当前版本读不懂，跳过
     *   - `type` 不在 [SessionEventKind] 里 → 未来新增的事件类型
     *   - 必填字段缺失（如 [TurnEnd] 没有 `reason`）→ 该行作废
     */
    internal fun decode(line: String): SessionRecord? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null
        val o = runCatching { JSONObject(trimmed) }.getOrNull() ?: return null
        val version = o.optInt(KEY_VERSION, 0)
        if (version <= 0 || version > FORMAT_VERSION) return null
        val seq = o.optLong(KEY_SEQ, 0L)
        if (seq <= 0L) return null
        val kind = SessionEventKind.fromWire(o.optString(KEY_TYPE)) ?: return null
        val turn = if (o.has(KEY_TURN)) o.optInt(KEY_TURN) else null
        val step = if (o.has(KEY_STEP)) o.optInt(KEY_STEP) else null

        val event: SessionEvent = when (kind) {
            SessionEventKind.TURN_START -> TurnStart(
                turn = turn ?: return null,
                step = step,
                source = MessageSource.fromWire(o.optString(KEY_SOURCE)) ?: MessageSource.UNKNOWN,
            )

            SessionEventKind.TURN_END -> TurnEnd(
                turn = turn ?: return null,
                step = step,
                reason = TurnEndReason.fromWire(o.optString(KEY_REASON)) ?: return null,
                detail = o.optString(KEY_DETAIL).ifBlank { null },
                // 缺字段 = 服务端没返回用量（旧文件里必然没有）→ 保持 null，
                // 绝不能兜成 0：那会让面板显示"本轮 0 token"这种错误信息
                promptTokens = if (o.has(KEY_PROMPT_TOKENS)) o.optInt(KEY_PROMPT_TOKENS) else null,
                completionTokens = if (o.has(KEY_COMPLETION_TOKENS)) o.optInt(KEY_COMPLETION_TOKENS) else null,
                modelCalls = if (o.has(KEY_MODEL_CALLS)) o.optInt(KEY_MODEL_CALLS) else null,
            )

            SessionEventKind.USER_MESSAGE -> UserMessage(
                turn = turn ?: return null,
                step = step,
                text = o.optString(KEY_TEXT),
                source = MessageSource.fromWire(o.optString(KEY_SOURCE)) ?: MessageSource.UNKNOWN,
            )

            SessionEventKind.ASSISTANT_ATTEMPT -> AssistantAttempt(
                turn = turn ?: return null,
                step = step,
                outcome = AttemptOutcome.fromWire(o.optString(KEY_OUTCOME)) ?: return null,
                detail = o.optString(KEY_DETAIL).ifBlank { null },
            )

            SessionEventKind.ASSISTANT_MESSAGE -> AssistantMessage(
                turn = turn ?: return null,
                step = step,
                text = o.optString(KEY_TEXT),
            )

            SessionEventKind.TOOL_CALL -> ToolCall(
                turn = turn ?: return null,
                step = step,
                callId = o.optString(KEY_CALL_ID),
                name = o.optString(KEY_NAME),
                args = o.optString(KEY_ARGS),
            )

            SessionEventKind.TOOL_RESULT -> ToolResult(
                turn = turn ?: return null,
                step = step,
                callId = o.optString(KEY_CALL_ID),
                name = o.optString(KEY_NAME),
                content = o.optString(KEY_CONTENT),
                truncated = o.optBoolean(KEY_TRUNCATED, false),
            )

            SessionEventKind.CONTEXT_INJECT -> ContextInject(
                turn = turn ?: return null,
                step = step,
                origin = o.optString(KEY_ORIGIN),
                content = o.optString(KEY_CONTENT),
                truncated = o.optBoolean(KEY_TRUNCATED, false),
            )

            SessionEventKind.COMPACTION_START -> CompactionStart(
                turn = turn, // 可为 null：手动压缩不归属任何一轮
                step = step,
                trigger = o.optString(KEY_TRIGGER),
            )

            SessionEventKind.COMPACTION_SUMMARY -> CompactionSummary(
                turn = turn,
                step = step,
                shadowedSeqs = readSeqList(o.optJSONArray(KEY_SEQS)),
                text = o.optString(KEY_TEXT),
                turnsCompressed = o.optInt(KEY_TURNS, 0),
            )

            SessionEventKind.COMPACTION_END -> CompactionEnd(
                turn = turn,
                step = step,
                error = o.optString(KEY_ERROR).ifBlank { null },
            )

            SessionEventKind.VISIBILITY_CUT -> VisibilityCut(
                turn = turn,
                step = step,
                seqs = readSeqList(o.optJSONArray(KEY_SEQS)),
                clearDigest = o.optBoolean(KEY_CLEAR_DIGEST, false),
                reason = o.optString(KEY_REASON),
            )
        }
        return SessionRecord(seq, o.optLong(KEY_TS, 0L), event)
    }

    /** JSON 数组 → seq 列表（元素非数字时跳过，不整条作废） */
    private fun readSeqList(arr: JSONArray?): List<Long> {
        if (arr == null || arr.length() == 0) return emptyList()
        val out = ArrayList<Long>(arr.length())
        for (i in 0 until arr.length()) {
            val v = arr.optLong(i, 0L)
            if (v > 0L) out.add(v)
        }
        return out
    }

    /** 按行解析（跳过所有不可解析行），seq 不保证递增 —— 调用方不依赖顺序时无需排序 */
    internal fun parse(text: String): List<SessionRecord> {
        if (text.isEmpty()) return emptyList()
        val out = ArrayList<SessionRecord>()
        for (raw in text.lineSequence()) {
            decode(raw)?.let { out.add(it) }
        }
        return out
    }
}
