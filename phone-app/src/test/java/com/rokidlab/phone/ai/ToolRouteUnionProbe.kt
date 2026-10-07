package com.rokidlab.phone.ai

import org.junit.Test

/**
 * 并集膨胀探针（2026-10-07）。
 *
 * ## 问题
 *
 * 补`search`/`app`/`nearby` 三条规则后，高频覆盖从 70% → 77%，但**工具数变多了**：
 * 「打开音乐」2→4、「帮我打开闹钟」3→5、「查一下明天天气」2→4。
 * 原因是通用词（打开 / 启动 / 查一下 /帮我查…）会**同时命中多条规则**，
 * 路由取并集 ⇒ 子集膨胀。命中率涨了，钱被回吐。
 *
 * 这是「子集不自动扩全量」的**反面**：不是自动扩全量，而是**规则之间自动并集**。
 *
 * ## 怎么读
 *
 * 本探针列出每个通用词的**命中规则集合**与**最终工具数**，用来判断：
 * 某个词该归谁所有 —— 归属对了，并集自然收敛，不需要暴力否决。
 *
 * ⚠️ 只 print 不 fail（同ToolRouteCoverageProbe：尺子不是门禁）。
 */
class ToolRouteUnionProbe {

    private fun probeWords(vararg words: String) {
        println("\n=== 通用词归属分析 ===")
        for (w in words) {
            // 构造一个只含该词的短句，避开其他规则关键词
            val routed = ToolIntentRouter.route(w)
            println("  「$w」→ ${routed?.size ?: 0} 个工具 ${routed?.sorted() ?: "落全量"}")
        }
    }

    private fun probeSentences() {
        val cases = listOf(
            "打开音乐", "启动音乐", "放一首晴天",
            "帮我打开闹钟", "明早七点叫我起床",
            "查一下明天天气", "现在这里的天气", "查一下快递",
            "打开空调", "查一下明天的日程",
            "附近有什么好吃的", "帮我搜一下附近有什么好吃的", "帮我搜一下附近的超市",
        )
        println("\n=== 关键句子的工具数 ===")
        for (c in cases) {
            val r = ToolIntentRouter.route(c)
            println("  $c → ${r?.size ?: "全量"}")
        }
    }

    @Test
    fun `union bloat probe`() {
        probeWords("打开", "启动", "开一下", "帮我打开", "帮我启动")
        probeWords("查一下", "帮我查")
        probeSentences()
        println(
            "\n判据：合并不是错，错的是**通用词被多个规则同时认领**。" +
                "归属收敛后并集自然变小，不需要对通用词做全局否决。",
        )
    }
}