package com.rokidlab.phone.aiui

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * AIUI 页面运行时的**日志环形缓冲**（2026-10-09，gps-live 事故 P2-15）。
 *
 * ## 为什么需要
 *
 * gps-live 案里最决定性的一条证据是运行时的这句报错：
 *
 * ```
 * Host capability geolocation.getCurrentPosition is not configured on Web host
 * ```
 *
 * 它精确指出了"页面拿不到位置"的原因（宿主没实现该能力）。但这条消息**只进了 logcat**，
 * 而 agent 读不到 logcat（`read_screen` 只能看像素）——
 * 于是它只能凭用户一句"拿不到位置"去猜，最后猜成了"HTTPS/浏览器限制"（错因），
 * 并在后续 3 轮里都基于这个错前提推理。
 *
 * **判据：凡是"只有用户能看见、agent 看不见"的故障信号，都是必然的误诊来源。**
 *
 * ## 为什么是 object（而不是 AiuiWebHost 的成员）
 *
 * WebView / Activity 会被销毁重建，而**工具调用要读的是"刚刚那个页面留下了什么"**——
 * 数据必须活得比宿主实例长。同时按**项目名**分桶：读某个项目时不该看到别的项目的报错。
 */
internal object AiuiPageLog {

    /** 每个项目保留的条数上限（够看清"最近出了什么错"，又不至于把上下文灌满） */
    private const val MAX_ENTRIES = 80

    /** 项目数上限（超出丢最旧的，防长跑进程里无限增长） */
    private const val MAX_PROJECTS = 16

    private data class Entry(val ts: Long, val level: String, val text: String)

    private val logs = LinkedHashMap<String, ArrayDeque<Entry>>()

    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    /**
     * 记一条。`level` 用简短英文（`error` / `warn` / `log`），
     * 由 [recent] 决定怎么呈现 —— 这里不做格式化，保持存储最小。
     *
     * @param project 项目名（分桶键）；空串 = 归到"未知项目"桶
     */
    fun note(project: String?, level: String, text: String) {
        val key = project?.trim().orEmpty().ifEmpty { UNKNOWN }
        if (text.isBlank()) return
        synchronized(logs) {
            val dq = logs.getOrPut(key) { ArrayDeque() }
            dq.addLast(Entry(System.currentTimeMillis(), level, text.take(600)))
            while (dq.size > MAX_ENTRIES) dq.removeFirst()
            if (logs.size > MAX_PROJECTS) logs.keys.firstOrNull()?.let { logs.remove(it) }
        }
    }

    /**
     * 最近 N 条的**给模型看**的文本。
     *
     * 格式刻意朴素（时间 + 级别 + 正文），且**明确区分 error 与 log** ——
     * 模型最需要的是"有没有报错、报的什么"，让它自己从一大段 console 里找不现实。
     *
     * @return 没有日志时返回空串（调用方据此不追加任何东西）
     */
    fun recent(project: String?, limit: Int = 30): String {
        val key = project?.trim().orEmpty().ifEmpty { UNKNOWN }
        val items = synchronized(logs) { logs[key]?.toList().orEmpty() }
        if (items.isEmpty()) return ""
        val tail = items.takeLast(limit.coerceIn(1, MAX_ENTRIES))
        val header = if (items.size > tail.size) {
            "页面运行日志（共 ${items.size} 条，最近 ${tail.size} 条）："
        } else {
            "页面运行日志（${items.size} 条）："
        }
        return header + "\n" + tail.joinToString("\n") { e ->
            "- [${fmt.format(Date(e.ts))}] ${e.level}: ${e.text}"
        }
    }

    /** 某个项目是否有过 **error 级**记录（供调用方决定要不要额外提醒模型"页面报错了"）。 */
    fun hasError(project: String?): Boolean {
        val key = project?.trim().orEmpty().ifEmpty { UNKNOWN }
        return synchronized(logs) { logs[key]?.any { it.level == "error" } == true }
    }

    /** 清空某个项目的日志（重新打开同一项目时用：避免把上一次运行的报错当成这次的）。 */
    fun clear(project: String?) {
        val key = project?.trim().orEmpty().ifEmpty { UNKNOWN }
        synchronized(logs) { logs.remove(key) }
    }

    private const val UNKNOWN = "(未知项目)"
}
