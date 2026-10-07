package com.rokidlab.phone.ai

import org.junit.Test

/**
 * 覆盖率探针（2026-10-07）—— **不是断言型测试，是度量工具**。
 *
 * ## 为什么需要它
 *
 * 「路由覆盖了多少工具」这个指标有两种算法：
 *  - 分母 = 105 个注册工具 ⇒ 路由只挂 9 条规则，名义覆盖 14/105 ≈ 13%。
 *    **这个算法虚高**：绝大多数工具（截图、装包、改文件、HTTP、知识库…）是
 *    低频高危或任务型，永远不该进路由子集。
 *  - 分母 = 高频日常轮次 ⇒ 才是真正该问的：**用户平时说的话，有多少被路由接住了**。
 *
 * 本探针按第二种算法：把高频句式灌进 [ToolIntentRouter.route]，看命中与否。
 *
 * ## 实测结论（2026-10-07，9条规则）
 *
 * **高频轮次覆盖 22/31 = 70%**。且缺口分两类，性质完全不同：
 *  - **D（工具存在、只是缺规则）**：`send_sms`/`search_web`/`open_phone_app` 都已注册，
 *    但`RULES` 里没有对应规则 ⇒ 补规则就能修，纯白捡的收益。
 *  - **D2（工具根本不存在）**：汇率/股市/外卖/音量/导航 —— **补规则也修不了**，
 *    是能力缺口（要么新加工具，要么老实落全量）。`set_volume`/`navigate` 在
 *    `PhoneToolProvider` 里查无此工具，别误判成「漏写规则」。
 *
 * ## 怎么读这份结果
 *
 *  - `Routed` 分组：路由接住了 ⇒ 这轮只下发个位数 Schema。
 *  - `Fell through` 分组：落回全量 105 个 Schema ⇒ **这里就是成本**。
 *    标 `⚠️ 应命中` 的是缺口（细分为可补规则 / 无工具），标 `（合理）` 的落全量是
 *    **正确行为**（长句/粘贴/造物任务，本就该全量）。
 *
 * ⚠️ 本类只print 不 fail，别把 expected 写成 assert —— 它是给人看趋势的尺子。
 */
class ToolRouteCoverageProbe {

    private fun probe(title: String, cases: List<Pair<String, Boolean>>) {
        val routed = mutableListOf<String>()
        val missed = mutableListOf<String>()
        cases.forEach { (text, shouldHit) ->
            val r = ToolIntentRouter.route(text)
            if (r != null) {
                routed += "$text → ${r.size} 个工具"
            } else if (shouldHit) {
                missed += "⚠️ 应命中: $text"
            } else {
                missed += "（合理）落全量: $text"
            }
        }
        val total = cases.size
        val hit = total - missed.count { it.startsWith("⚠️") }
        println("\n── $title ──")
        println("覆盖 ${hit * 100 / total}% ($hit/$total)")
        if (routed.isNotEmpty()) {
            println("Routed:")
            routed.forEach { println("  ✓ $it") }
        }
        if (missed.isNotEmpty()) {
            println("Fell through:")
            missed.forEach { println("  ✗ $it") }
        }
        // ⚠️ 刻意**不** assert：探针是尺子不是门禁。D2 组（工具不存在）预期就是 0%，
        // 这里若断言会把「能力缺口」伪装成「测试失败」，掩盖真正的结论。
    }

    @Test
    fun `coverage probe by scenario`() {
        probe(
            "A. 天气与时间（规则齐备）",
            listOf(
                "现在这里的天气" to true,
                "明天会下雨吗" to true,
                "杭州冷不冷" to true,
                "今天需要带伞吗" to true,
                "空气质量怎么样" to true,
                "现在几点" to true,
                "今天星期几" to true,
                "明天几号" to true,
                "查一下明天的天气" to true,
                "帮我打开闹钟" to true,
            ),
        )

        probe(
            "B. 音乐与通话（规则齐备）",
            listOf(
                "放一首晴天" to true,
                "打开音乐" to true, // 2026-10-07 修复：原被全局否决词「打开」误杀
                "下一首" to true,
                "给妈妈打电话" to true,
                "拨打 13800138000" to true,
            ),
        )

        probe(
            "C. 定位与计算（规则齐备）",
            listOf(
                "我附近有什么好吃的" to true,
                "我在哪" to true,
                "378乘以56等于多少" to true,
                "128+64再乘12是多少" to true,
                "599打85折是多少钱" to true,
            ),
        )

        probe(
            "D. ⚠️ 高频但工具存在、只是缺规则 = 可补的缺口",
            listOf(
                "查一下快递" to true, // search_web 能办，但没规则接
                "发个短信给张三" to true, // send_sms 存在
                "帮我搜一下附近有什么好吃的" to true, // search_web 存在
                "打开空调" to true, // open_phone_app 可覆盖
                "查一下明天的日程" to true,
                "今天北京天气怎么样" to true,
            ),
        )

        probe(
            "D2. ⚠️ 高频且**工具根本不存在** = 能力缺口，补规则也补不了",
            listOf(
                "今天汇率多少" to true, // 无 finance 类工具
                "看一下股市行情" to true,
                "帮我订个外卖" to true,
                "把音量调大一点" to true, // 无 set_volume（PhoneToolProvider 无此工具）
                "导航去公司" to true, // 无 navigate
            ),
        )

        probe(
            "E. （合理）该落全量的形状—— 不算缺口",
            listOf(
                "写个天气app" to false,
                "做一个天气页面" to false,
                "把今天的天气生成一张图片" to false,
                "帮我搜一下附近的超市" to false, // 搜索不抢 location
                "帮我推荐一部好看的电影" to false,
                "第一行\n第二行天气" to false,
                "这是一段很长的粘贴内容详细描述了一个复杂的多步骤任务需要很多工具配合才能完成请认真处理每一个环节不要遗漏" to false,
                "" to false,
            ),
        )
    }

    @Test
    fun `named coverage report`() {
        // 汇总一行结论，CI 日志里一眼能看
        val all = listOf(
            "现在这里的天气", "明天会下雨吗", "杭州冷不冷", "今天需要带伞吗", "空气质量怎么样",
            "现在几点", "今天星期几", "明天几号", "查一下明天的天气", "帮我打开闹钟",
            "放一首晴天", "打开音乐", "下一首", "给妈妈打电话", "拨打 13800138000",
            "我附近有什么好吃的", "我在哪", "378乘以56等于多少", "128+64再乘12是多少", "599打85折是多少钱",
            "查一下快递", "帮我搜一下附近有什么好吃的", "今天汇率多少", "看一下股市行情",
            "帮我订个外卖", "打开空调", "把音量调大一点", "导航去公司", "发个短信给张三",
            "查一下明天的日程", "今天北京天气怎么样",
        )
        val routed = all.count { ToolIntentRouter.route(it) != null }
        println("\n=== 高频轮次路由覆盖: $routed/${all.size} " +
            "(${(routed * 100) / all.size}%) ===")
        println("注册工具 105 个；路由规则 9 条，挂 14 个工具名。")
        println("⚠️ 工具维度的 13% 是虚高指标 —— 分母含大量低频高危工具。")
    }
}