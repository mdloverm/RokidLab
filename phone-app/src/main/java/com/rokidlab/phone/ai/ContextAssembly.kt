package com.rokidlab.phone.ai

/**
 * 轮变上下文的**装配**（提示词注入的内容侧，纯函数、无Android 依赖）。
 *
 * ## 为什么要抽出来
 * 改造前这段逻辑内联在 `AiConversationService.sendAiTextViaLink` 里，而那个文件
 * **3100 行、零单测**。于是 2026-10-07 审计出的两条高危（自动 RAG 绕过隔离、
 * 教训库被种进不可信原文）**都发生在没有单测的地方** —— 不是巧合，是
 * 「编排层没有测试」必然产出的盲区。
 *
 * 抽出来后可直接钉住：
 *  - 知识库片段的**长度上限与截断告知**（不静默截）；
 *  - 未命中时仍给「目录」而非正文（否则模型不知道有文档可查）；
 *  - 资料合并的顺序与空值处理；
 *  - 预算文案里三个常数的取值（写死数字会在改配置后静默失效）。
 *
 * ⚠️ **不要把「注入哪些内容」的判断搬进来**：本类只负责**把给定的内容拼成字符串**，
 * 「要不要检索」「要不要注入」属于编排决策，仍留在主循环。判据＝本类必须能纯 JVM 单测。
 */
object ContextAssembly {

    /**
     * 知识库命中片段的字符上限。
     *
     * 天然上限已由 `KnowledgeBase.CHUNK_SIZE = 500` + top-2 检索（约 1000 字符）保证；
     * 这个 take 是**防御性**的—— 将来调大 CHUNK_SIZE 或提高 topN 都是无告警的静默改动，
     * 不设防的话历史注入会跟着膨胀成成本黑洞。
     *
     * 📌 按「一个 chunk 正常长度的 3 倍」定；截断时必须**告知模型**（见 [knowledgeContext]
     * 的尾句），否则模型会把残缺段落当完整依据 —— 那比截断本身更糟。
     */
    const val KB_HIT_MAX_CHARS = 1500

    /** 未命中时列出的文档名上限（只给"目录"不给正文，避免无关内容被误当依据） */
    const val KB_DOC_NAMES_MAX = 12

    /** 一条检索命中在注入文本里的呈现：`《文档名》第N块：正文`（[chunkIdx] 为 0 起） */
    fun formatHit(docName: String, chunkIdx: Int, text: String): String =
        "《$docName》第${chunkIdx + 1}块：$text"

    /**
     * 把知识库检索结果装配成注入文本。
     *
     * @param hitLines 已格式化的命中行（用 [formatHit] 产出，保留真实块号）
     * @param docCount 库里文档总数
     * @param docNames 文档名列表（用于未命中时给目录）
     * @return 注入文本；**库里一篇文档都没有**时返回 null（无内容可注入）
     */
    fun knowledgeContext(
        hitLines: List<String>,
        docCount: Int,
        docNames: List<String>,
    ): String? {
        if (docCount <= 0) return null
        val digest = hitLines
            .joinToString("\n")
            .let {
                if (it.length > KB_HIT_MAX_CHARS) it.take(KB_HIT_MAX_CHARS) + "…（检索片段过长已截断）" else it
            }
            .takeIf { it.isNotBlank() }
        // ⚠️ 未命中**也要**给目录：改造前只有"命中才注入"，于是用户换个说法、或问
        // 「这个文档讲了什么」这种没有可命中关键词的泛问时，模型连"存在知识库"都不知道
        // ⇒ 既没有资料、也不会去调 search_knowledge_base，表现就是"问文档里的内容答不出来"。
        return digest ?: (
            "（按当前提问没有自动检索到相关段落）本地知识库中已有 $docCount 份文档：" +
                docNames.take(KB_DOC_NAMES_MAX).joinToString("、") { "《$it》" } +
                "。若与该问题相关，请调用 search_knowledge_base 用文档里更可能出现的关键词检索后再回答；" +
                "若无关则忽略本条。"
            )
    }

    /**
     * 合并多个资料来源（知识库片段 + 调用方自带的 contextText），共用一个注入槽位。
     *
     * 空串/纯空白一律跳过；全空返回 null（不注入空段）。
     */
    fun mergeSources(vararg sources: String?): String? =
        sources.filterNot { it.isNullOrBlank() }
            .joinToString("\n\n")
            .ifBlank { null }

    /**
     * 预算自感知文案：把本轮轮次预算如实写进提示词，让模型能规划"把额度花在哪"。
     *
     * 旧行为是触顶后静默截断 —— 模型和用户都不知道为什么任务只做了一半。
     *
     * @param contextWindow 模型上下文窗口；**≤0 表示未知** ⇒ token 上限整句省略
     *   （给一个猜的数反而会让模型按错误假设分配步骤；口径与 forWindow 一致）
     */
    fun budgetNote(
        maxTotalRounds: Int,
        maxReadonlyRounds: Int,
        maxActionRounds: Int,
        maxAutoContinues: Int,
        contextWindow: Int,
    ): String = buildString {
        append("【本次回答的运行预算】最多进行 $maxTotalRounds 轮工具调用，其中只读类（查询/检索/读取）")
        append("累计不超过 $maxReadonlyRounds 轮、含动作类（落盘/装机/拨号/发消息等）不超过 $maxActionRounds 轮；")
        append("触顶后平台会**自动追加一段额度**（$maxAutoContinues 次），所以你不需要为了省额度而跳过必要步骤，")
        append("更不要在中途停下来让用户说「继续」。接近上限时请优先完成关键步骤并给出阶段性结论；")
        append("若确实做不完，请如实说明还剩哪些步骤没做，并提示用户说「继续」以便下次接着完成。")
        if (contextWindow > 0) {
            append("此外本次回答的总消耗以约 ${contextWindow * 4} token 为上限（按 ${contextWindow / 4} token/轮估算），")
            append("超出会提前进入收尾：别在前面几轮就写超长的中间产物，把篇幅留给最后的结论。")
        }
    }

    /**
     * 长任务续做提示：TTL 内未完成任务的流程级状态注入。
     *
     * 用户说「继续」时模型能接着做，而不是从头重来；已完成或过期任务由调用方保证不注入。
     */
    fun taskNote(pending: String?): String? =
        pending?.takeIf { it.isNotBlank() }
}