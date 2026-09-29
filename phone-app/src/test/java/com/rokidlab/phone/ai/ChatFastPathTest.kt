package com.rokidlab.phone.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 闲聊快路的装配判定测试（2026-09-29）。
 *
 * 判定是**白名单优先 + 黑名单复核**：误放进快路的代价是能力缺失/模型编造（严重），
 * 漏放的代价只是多花两万 token（维持原状）—— 所以测试的重点是**工具意图一律挡住**。
 */
class ChatFastPathTest {

    // ═══════════ 应进快路（不装配工具）═══════════

    @Test
    fun `A1 问候与时段问候`() {
        listOf("你好", "您好！", "哈喽～", "嗨", "hello", "Hello!", "在吗？", "在不在",
            "早上好", "早安。", "中午好", "下午好~", "晚上好", "晚安", "好梦")
            .forEach { assertTrue("「$it」应进快路", ChatFastPath.isPureChat(it)) }
    }

    @Test
    fun `A2 感谢告别与敷衍应答`() {
        listOf("谢谢", "多谢啦！", "辛苦了", "麻烦你了", "再见", "拜拜~", "我先走了",
            "嗯", "嗯嗯", "好的。", "好嘞", "收到", "明白", "知道了", "懂了！", "ok", "OK")
            .forEach { assertTrue("「$it」应进快路", ChatFastPath.isPureChat(it)) }
    }

    @Test
    fun `A3 陪伴与身份问答`() {
        listOf("爱你", "抱抱", "陪我聊聊天", "好无聊啊", "我回来了",
            "你是谁", "你叫什么名字？", "自我介绍一下", "你能做什么", "你是机器人吗")
            .forEach { assertTrue("「$it」应进快路", ChatFastPath.isPureChat(it)) }
    }

    @Test
    fun `A4 轻创作请求`() {
        listOf("讲个笑话", "说个冷笑话吧", "出个谜语", "夸夸我", "哄哄我", "给我加油！")
            .forEach { assertTrue("「$it」应进快路", ChatFastPath.isPureChat(it)) }
    }

    // ═══════════ 不进快路（全量装配）═══════════

    @Test
    fun `B1 实时信息与设备操作意图必须挡住`() {
        listOf(
            "现在几点", "今天天气怎么样", "明天会下雨吗", "早上好，今天天气怎么样",
            "帮我设个明早七点的闹钟", "提醒我三点开会", "放一首周杰伦的歌", "来一首断桥残雪",
            "打开微信", "手机电量还有多少", "把音量调大", "眼镜还有多少电",
        ).forEach { assertFalse("「$it」不应进快路", ChatFastPath.isPureChat(it)) }
    }

    @Test
    fun `B2 搜索文件与生成意图必须挡住`() {
        listOf(
            "帮我查一下附近的餐厅", "搜一下今天的新闻", "查个单词",
            "看看我生成了哪些文件", "保存这个文件", "写一个快排", "生成一个小游戏",
            "把这段话翻译成英文", "读一下说明书",
        ).forEach { assertFalse("「$it」不应进快路", ChatFastPath.isPureChat(it)) }
    }

    @Test
    fun `B3 结构性排除_超长_多行_链接`() {
        assertFalse("超长文本不应进快路", ChatFastPath.isPureChat("你好".repeat(40)))
        assertFalse("多行文本不应进快路", ChatFastPath.isPureChat("你好\n帮我看看这个"))
        assertFalse("链接不应进快路", ChatFastPath.isPureChat("看看这个 https://example.com"))
        assertFalse("空文本不应进快路", ChatFastPath.isPureChat(""))
        assertFalse("空白不应进快路", ChatFastPath.isPureChat("   "))
    }

    @Test
    fun `B4 问候里夹任务词必须挡住`() {
        // 白名单命中但黑名单复核失败：看着像应答、实际要动手
        listOf("好的，帮我查一下天气", "嗯，打开相机", "好呀，放首歌")
            .forEach { assertFalse("「$it」不应进快路", ChatFastPath.isPureChat(it)) }
    }

    // ═══════════ 升级信号判定 ═══════════

    @Test
    fun `C1 标记识别与防误伤`() {
        assertTrue(ChatFastPath.needsUpgrade("[NEED_TOOLS]"))
        assertTrue(ChatFastPath.needsUpgrade(" [need_tools] \n"))
        assertTrue(ChatFastPath.needsUpgrade("好的[NEED_TOOLS]"))
        // 正常闲聊回复不含标记 → 不升级
        assertFalse(ChatFastPath.needsUpgrade("你好呀！今天过得怎么样？"))
        // 空/超长回复不升级（空回复由主循环按「探测失败」另行升级）
        assertFalse(ChatFastPath.needsUpgrade(null))
        assertFalse(ChatFastPath.needsUpgrade(""))
        assertFalse(ChatFastPath.needsUpgrade("[NEED_TOOLS]".repeat(40)))
    }
}
