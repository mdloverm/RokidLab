package com.rokidlab.phone.ai

/**
 * 内部协议标记的**终态护栏**（2026-10-09，gps-live 事故 P0-1）。
 *
 * ## 事故现场
 *
 * 三层降载（闲聊快路 / 意图路由 / 菜单探测）用一个**只该给框架看**的标记做逃生舱：
 * 模型手头工具子集不够用时，按约定只回一行 `[NEED_TOOLS]`，主循环挂全量工具重建请求重来。
 *
 * 但升级分支**只生效一次**（`routedToolNames`/`menuModeActive` 被清空后 `probeMode=false`），
 * 第二次喊标记时没有任何分支接住它 ⇒ `reply = turn.content` 原样落到用户可见文本上。
 * 真机表现：**聊天气泡里就是一行 `[NEED_TOOLS]`**，而且该轮被记成 `reason=completed`（"做完了"）。
 * 实测 5 轮全部命中（T2/T3/T5/T6/T7），每轮还白烧 2 次模型调用。
 *
 * ## 为什么护栏放在**终态**而不是升级分支
 *
 * 升级分支只能覆盖"它认得的那几种情形"。标记的**变形**是无穷的
 * （模型会写 `[NEED_TOOLS]`、`[NEED TOOLS]`、`[NEED-TOOLS]`、`[TOOLS]`…），
 * 而**唯一能穷尽的情形是"它最后变成了要播给用户的那段文本"** ——
 * 所以判据放在收尾处：**凡是给用户看/念的文本里出现标记，就是未产出**。
 *
 * 同源判据：**安全不变量要放在"签名就装不下坏数据"的地方** ——
 * 这里就是"用户可见文本"这一个出口，显示、TTS、会话记忆三处都从它取。
 */
object InternalMarkerGuard {

    /**
     * 判定用的**归一化**：去掉空白、下划线、连字符并转大写。
     *
     * 为什么必须归一化：模型写标记时三种分隔符都会用（`NEED_TOOLS` / `NEED TOOLS` / `NEED-TOOLS`），
     * 只比对原样字面量会漏掉后两种（真机上模型确实写成过带空格的形态）。
     */
    private fun normalize(s: String): String =
        s.uppercase().replace(Regex("[\\s_\\-]+"), "")

    /** 归一化后的标记字面量（唯一产地） */
    private val NO_TOOLS = normalize(ChatFastPath.NEED_TOOLS_MARKER)      // [NEEDTOOLS]
    private val PICK_TOOLS = normalize(ToolIntentRouter.TOOLS_PICK_MARKER) // [TOOLS]

    /** 判定用的长度上限：正常回复里不该出现这些标记，超长文本一律不判（防误伤长文） */
    private const val MAX_SCAN_CHARS = 400

    /**
     * 这段文本是不是"**只由内部标记构成**"（= 未产出）。
     *
     * 判据刻意收窄成**整段就是标记**（允许前后空白与少量标点），而不是"包含标记"：
     *  - 误判代价不对称：把正常回复误判为"未产出"会让用户白等一轮重试；
     *  - 真·未产出的形态**就是**整段一个标记（约定是"只回复一行"）；
     *  - 用户问「[NEED_TOOLS] 是什么」时模型会正常解释，那是回答、不该被拦。
     *
     * @param text 待检查的**用户可见**文本（reply / summaryText）
     */
    fun isMarkerOnly(text: String?): Boolean {
        val t = text?.trim().orEmpty()
        if (t.isEmpty() || t.length > MAX_SCAN_CHARS) return false
        // 去掉包裹的常见标点（模型偶尔写 `[NEED_TOOLS]。`）
        val stripped = normalize(t).trim('。', '.', '!', '！', '?', '？', '~', '～', '，', ',', '*', '`', '"', '\'')
        return stripped == NO_TOOLS || stripped == PICK_TOOLS
    }

    /**
     * 从任意文本里**剥掉**内部标记（用于清洗，不用于判定）。
     *
     * 场景：模型可能写"我看看…\n[NEED_TOOLS]"（带一句前缀）。判定走 [isMarkerOnly]（严格），
     * 而**清洗**走本方法（宽）—— 两者分工不同：判定要防误伤，清洗要防漏。
     * 剥完若什么都不剩，调用方按"空回复"处理。
     */
    fun stripMarkers(text: String?): String {
        val t = text ?: return ""
        // 整行就是标记 → 删整行；行内出现的 → 只删标记本身
        var out = t.replace(Regex("(?im)^[ \\t]*\\[\\s*NEED[\\s_\\-]*TOOLS\\s*]\\s*$"), "")
        out = out.replace(Regex("(?im)^[ \\t]*\\[\\s*TOOLS\\s*]\\s*$"), "")
        out = out.replace(Regex("\\[\\s*NEED[\\s_\\-]*TOOLS\\s*]", RegexOption.IGNORE_CASE), "")
        out = out.replace(Regex("\\[\\s*TOOLS\\s*]", RegexOption.IGNORE_CASE), "")
        return out.trim()
    }

    /** 覆盖说明的幂等标记（出现它就说明已经撤过旧指令，不再重复追加） */
    private const val OVERRIDE_TAG = "【已挂载全量工具】"

    /**
     * 撤掉尾部那段"不够用时回复 `[NEED_TOOLS]`"的旧指令（2026-10-09，P0-2）。
     *
     * ## 为什么必须撤
     *
     * `<tool_menu>` 段的约定是在**本轮 tail**注入的（`OpenAiService.buildContextTailMessage`），
     * 一旦注入就留在上下文里到本轮结束。而升级到全量时，**非探测轮只换工具表、不重建
     * messages**（重建会劈散 in-flight 的 tool 配对 → 服务端 400）——
     * 于是模型**既拿到了全量工具，又仍然读到"要回 `[NEED_TOOLS]`"这条指令**，
     * 而且拿不到任何"已经升级完成"的反馈 ⇒ 它照着旧指令又喊了一次标记。
     * 这是 gps-live 事故里**每轮多烧 2 次模型调用**的直接原因。
     *
     * 判据同「指令不能指向一个已经不成立的状态」（审计判据 ⑥）：
     * 升级之后，"你手头工具不够"这个前提**已经不成立**，那条指令必须随之失效。
     *
     * ## 为什么是"追加覆盖"而不是"删掉那段"
     *
     * tail 是一整条拼好的 system 消息，段落边界靠自然语言而非结构；
     * 精确删除容易误伤相邻段落（记忆/知识库/会话要求）。**追加一段显式覆盖**更安全：
     * 位置更靠后 = 优先级更高（提示词的位置本身就是优先级信号），且幂等可重复调用。
     *
     * @param content  含 `<tool_menu>` 的 system 消息正文
     * @param toolCount 本轮真正挂载的工具数（写进文案，让模型知道"已经给全了"）
     * @return 处理后的正文；不含菜单段、或已撤过则**原样返回**
     */
    fun neutralizeMenuInstruction(content: String?, toolCount: Int): String? {
        val c = content ?: return null
        if (!c.contains("<tool_menu>")) return c
        if (c.contains(OVERRIDE_TAG)) return c
        return c + "\n\n" + OVERRIDE_TAG +
            "本轮已经为你挂载全部 $toolCount 个工具（完整参数定义都在工具表里）。" +
            "**上面 <tool_menu> 里关于回复 " + ChatFastPath.NEED_TOOLS_MARKER + " 或 " +
            ToolIntentRouter.TOOLS_PICK_MARKER + " 的约定即刻作废** —— " +
            "不要再输出任何方括号标记，直接从工具表里选择并调用你需要的工具。"
    }
}
