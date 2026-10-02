package com.rokidlab.phone.platform

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicReference

/**
 * 运行中的**交互式程序**注册表：聊天输入联动（"程序模式"）与运行卡片消息的数据源。
 *
 * ## 为什么是全局单例（仿 [WebPreviewManager]）
 * 代码卡里的运行是 Composable 局部状态 —— 卡片滚出 LazyList 被回收，状态就没了
 * （此前"滚走就杀进程"限制的根源）。把 handle 提升到全局后：
 *  - 程序寿命与卡片解耦：滚走、切会话都不影响正在跑的程序；
 *  - 聊天输入条能拿到"当前在跑什么"，实现**在聊天框里直接跟程序对话**；
 *  - 程序输出可以同时喂"消息流里的运行卡片"和"代码卡自身的流式区"。
 *
 * ## 多程序并发
 * 允许多个（每张代码卡各跑各的）；`latestActive` 取**最近启动**的活跃程序，
 * 聊天"程序模式"绑定它 —— 输入条是单通道，绑定最近启动的符合直觉（刚点运行的就是要玩的）。
 */
object InteractiveRunManager {

    /** 结束运行的历史上限（新会话长聊也不会无限累积；超出丢最旧的） */
    private const val HISTORY_CAP = 20

    /** 一个运行中的程序（+ 它的流式输出缓冲） */
    class Run internal constructor(
        val id: String,
        /** 语言标签（JAVA / PYTHON / SHELL…），UI 展示用 */
        val label: String,
        val startedAtMs: Long,
        internal val handle: ProotShell.InteractiveHandle,
    ) {
        private val _output = MutableStateFlow("")

        /** 累计输出（stdout/stderr 合流）。⚠️ 超过 20k 字符截尾保留尾部，防失控输出撑爆内存 */
        val output: StateFlow<String> get() = _output

        val alive: Boolean get() = handle.alive

        /** 往程序 stdin 写一行（自动补 `\n`；空串只补换行 = 直接回车） */
        fun writeLine(text: String) {
            handle.writeStdin(if (text.isEmpty()) "\n" else "$text\n")
        }

        fun stop() {
            handle.stop()
        }

        internal fun append(line: String) {
            val cur = _output.value
            _output.value = if (cur.length > 20_000) {
                cur.takeLast(16_000) + "\n" + line
            } else {
                cur + line + "\n"
            }
        }
    }

    /** 已结束的运行记录（⑥ 运行结果回流：AI 经 `list_runs`/`read_run_output` 事后可查） */
    class FinishedRun(
        val id: String,
        val label: String,
        val startedAtMs: Long,
        val endedAtMs: Long,
        /** 退出码；未拉起来时为 -1 并带 [failure] */
        val exitCode: Int,
        val failure: String?,
        /** 最终累计输出（已按 [Run.output] 的截尾规则封顶） */
        val output: String,
    )

    private val _history = MutableStateFlow<List<FinishedRun>>(emptyList())

    /** 最近结束的运行（新在尾；上限 [HISTORY_CAP] 条，防长会话无限累积） */
    val history: StateFlow<List<FinishedRun>> get() = _history

    private val _runs = MutableStateFlow<List<Run>>(emptyList())

    /** 全部运行中的程序（聊天页/代码卡据此显示状态） */
    val runs: StateFlow<List<Run>> get() = _runs

    /** 最近启动的活跃程序（聊天"程序模式"的绑定对象）；没有活跃程序时为 null */
    val latestActive: Run? get() = _runs.value.lastOrNull { it.alive }

    /** 按 id 找**运行中**的程序（工具 `read_run_output`/`write_run_input` 的查找半边） */
    fun findRun(id: String): Run? = _runs.value.firstOrNull { it.id == id }

    /** 按 id 找**已结束**的运行（[history] 的查找半边） */
    fun findFinished(id: String): FinishedRun? = _history.value.firstOrNull { it.id == id }

    /**
     * 在容器里交互式跑一段脚本并注册进管理器。
     *
     * - [idleTimeoutSec]：空闲看狗（静默多久自动收尾）。传 `0L` = 不看狗 ——
     *   **仅供后台长任务**（`run_shell` 的 background 参数）：编译/爬虫这类
     *   长时间无输出的活不能按 60s 空闲误杀；回收靠调用方显式 stop。
     * - [onFinished]：结束（自然退出 / 空闲超时 / stop）回调一次 —— **无论返回值是否为 null
     *   都会调**（null = 根本没起来，onFinished 收到带原因的 [ExecResult]）。
     *   第二参 [Run] 是刚结束的实例（含最终 [Run.output]，调用方此刻把它定格进消息流）；
     *   null = 没起来。此刻 Run 已从 [runs] 移除，UI 再读不到流。
     * - 返回 null = 没起来（rootfs 未装 / 环境忙）；返回非 null = 已注册、正在跑。
     */
    fun start(
        ctx: Context,
        script: String,
        label: String,
        idleTimeoutSec: Long = 60L,
        onFinished: (ExecResult, Run?) -> Unit,
    ): Run? {
        val ref = AtomicReference<Run?>(null)
        // onStarted 在 runScriptInteractive 返回**之前**同步触发 ⇒ 返回非 null 时 ref 必已就绪；
        // 返回 null = 没起来（onFinished 会在后台线程带原因回调）
        ProotShell.runScriptInteractive(
            ctx,
            script,
            idleTimeoutSec = idleTimeoutSec,
            onStarted = { h ->
                val run = Run(
                    id = java.util.UUID.randomUUID().toString(),
                    label = label,
                    startedAtMs = System.currentTimeMillis(),
                    handle = h,
                )
                ref.set(run)
                _runs.value = _runs.value + run
            },
            onOutput = { line -> ref.get()?.append(line) },
            onExit = { result ->
                val done = ref.get()
                if (done != null) {
                    _runs.value = _runs.value.filterNot { it.id === done.id }
                    // ⑥ 运行历史：无论谁启动的（代码卡 / 聊天"说运行" / AI 后台任务），
                    // 结束时都留档 —— AI 事后能回答"刚才跑了什么、输出是什么"。
                    _history.value = (_history.value + FinishedRun(
                        id = done.id,
                        label = done.label,
                        startedAtMs = done.startedAtMs,
                        endedAtMs = System.currentTimeMillis(),
                        exitCode = result.code,
                        failure = result.failure,
                        output = done.output.value,
                    )).takeLast(HISTORY_CAP)
                }
                onFinished(result, done)
            },
        )
        return ref.get()
    }
}
