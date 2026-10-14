package com.rokidlab.phone.ai

import java.io.File

/**
 * 本次工具调用**产出的文件**（结构化产物回报，2026-10-13 审计加）。
 *
 * ## 解决什么
 * `AgentStep.ARTIFACT_SPECS` 是「按**调用参数**推产物路径」的表 —— 对 `apply_patch` /
 * `save_code_file` 这类"参数里就写着目标路径"的工具够用。但有两类工具**参数里没有产物路径**：
 *  - `zip_files`：产物是下载目录里的 `<名字>.zip`，名字来自 `zipName`/`project` 并经 sanitize；
 *  - `save_summary_txt`：产物是下载目录里的 `<标题>.txt`，标题同样经 sanitize。
 * 硬把它们的入参（`project` / `title`）登记进那张表，会指向**输入**甚至不存在的路径 ——
 * 正是本项目铁律禁止的"指令指向不存在的对象"（用户点了芯片打不开）。
 *
 * ⇒ 产物路径只有**工具自己**在写完那一刻才知道。本类就是那条回报通道：
 * 工具在成功落盘后 [record] 真实绝对路径，主循环在**同一线程** [take] 走并放进过程行的产物芯片。
 *
 * ## 为什么是 ThreadLocal
 * 与 [com.rokidlab.phone.ai.tools.VisionToolProvider] 的 `pendingImage`、
 * [com.rokidlab.phone.ai.tools.ShellToolProvider] 的 `setLiveOutputSink` 同一手法：
 * 工具执行发生在**并行泳道的专属线程**或**串行泳道的生成线程**上，写入与读取必然同线程
 * （见 `AiConversationService.runToolWorker`）。用 ThreadLocal 让"哪次调用产出什么"天然隔离，
 * 不需要给每条产物路径再想办法绑 callId。
 * ⚠️ 前提同上：**改到池线程/协程派发时这条不成立**，必须先回来改这里（同 LiveOutputSink 的警告）。
 *
 * ## 纪律
 *  - 只收**绝对路径**（相对路径宁可少显示也不猜 —— 与 [AgentStep.artifactOf] 同一判据）；
 *  - `begin()` 在每次工具执行前调用，`take()` 取走即清 —— 不留残留给下一个复用该线程的任务；
 *  - 失败路径不 record（失败就不该有产物芯片）。
 */
internal object ProducedFileSink {

    /** 一件产出：绝对路径 + 展示名（展示名缺省取文件名） */
    data class Produced(val path: String, val label: String)

    private val holder = ThreadLocal<MutableList<Produced>?>()

    /** 开始记录一次工具调用（执行前调用；清掉同线程可能存在的残留） */
    fun begin() = holder.set(null)

    /**
     * 登记一件产出。**只接受绝对路径**（相对路径无法定位，宁可少显示）。
     *
     * @param label 展示名（芯片上显示的短名）；为空时取文件名
     */
    fun record(path: String, label: String = "") {
        val p = path.trim()
        if (p.isEmpty() || !File(p).isAbsolute) return
        val list = holder.get() ?: ArrayList<Produced>(2).also { holder.set(it) }
        if (list.none { it.path == p }) {
            list.add(Produced(p, label.trim().ifBlank { File(p).name }))
        }
    }

    /** 取走本次产出并清空槽位（执行后、构造终态过程行之前调用） */
    fun take(): List<Produced> {
        val list = holder.get() ?: return emptyList()
        holder.set(null)
        return list
    }

    /** 兜底清理（`runToolWorker` 的 finally 里调，异常路径下不留残留） */
    fun clear() = holder.set(null)
}
