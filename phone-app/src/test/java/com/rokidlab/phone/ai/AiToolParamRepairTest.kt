package com.rokidlab.phone.ai

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具参数修复的回归测试（[AiToolParamRepair]）。
 *
 * 背景：这些逻辑原先是 `AiConversationService.sendAiTextViaLink` 里的**局部函数** ——
 * 单测碰不到，所以「AI 空轮 400」和「参数截断白耗一轮」这两类根因只能靠真机肉眼验证。
 * 2026-10-05 抽成独立 object 后，本测试把行为逐条锁住。
 *
 * ⚠️ 这些断言即「契约」：修复行为若要改（例如放宽/收紧闭合规则），必须先确认
 * 真机上的截断形态没变，再回来改断言 —— 不要为了让测试变绿而改断言。
 */
class AiToolParamRepairTest {

    // ===== closeTruncatedJson =====

    @Test
    fun `本来就闭合的片段不修复`() {
        // 无事可做 ⇒ 返 null，让调用方走正常解析路径（避免重复解析开销）
        assertNull(AiToolParamRepair.closeTruncatedJson("""{"a":1}"""))
        assertNull(AiToolParamRepair.closeTruncatedJson("""{"a":[1,2],"b":"x"}"""))
    }

    @Test
    fun `补未闭合的对象括号`() {
        assertEquals("""{"a":1}""", AiToolParamRepair.closeTruncatedJson("""{"a":1"""))
    }

    @Test
    fun `补未闭合的数组括号且保持栈序`() {
        // 栈是后进先出：{"a":[1,2  ⇒  先补 ] 再补 }
        assertEquals("""{"a":[1,2]}""", AiToolParamRepair.closeTruncatedJson("""{"a":[1,2"""))
    }

    @Test
    fun `补未闭合的字符串引号`() {
        // 补引号后栈里还剩 } ⇒ 两个缺口都要补
        assertEquals("""{"a":"hel"}""", AiToolParamRepair.closeTruncatedJson("""{"a":"hel"""))
    }

    @Test
    fun `字符串里的括号不参与栈计算`() {
        // 括号在字符串内 ⇒ 不可当结构位。正确结果只补最外层 }
        assertEquals("""{"a":"(x"}""", AiToolParamRepair.closeTruncatedJson("""{"a":"(x"""))
    }

    @Test
    fun `转义引号不误判为字符串结束`() {
        // \" 是字符串内的字面量引号，字符串其实还开着 ⇒ 只补一个收尾 "，再补 }
        assertEquals(
            """{"a":"say \"hi\""}""",
            AiToolParamRepair.closeTruncatedJson("""{"a":"say \"hi\"""") ,
        )
    }

    @Test
    fun `悬空转义符先摘掉再补引号`() {
        // 截断正落在反斜杠后：直接补 " 会被 \ 吞掉，变成非法 \"
        val out = AiToolParamRepair.closeTruncatedJson("""{"a":"x\""")
        assertNotNull(out)
        assertTrue("不应残留悬空反斜杠: $out", !out!!.endsWith("\\\""))
        assertNotNull(JSONObject(out).optString("a").ifEmpty { "x" })
    }

    @Test
    fun `悬空冒号补 null`() {
        assertEquals("""{"a":null}""", AiToolParamRepair.closeTruncatedJson("""{"a":"""))
    }

    @Test
    fun `结构已错乱时拒绝修复`() {
        // 闭括号多于开括号 ⇒ 括号匹配已经坏了，补只会更糟
        assertNull(AiToolParamRepair.closeTruncatedJson("""{"a":1}}"""))
        assertNull(AiToolParamRepair.closeTruncatedJson("""{"a":[1,2]}"""))
        assertNull(AiToolParamRepair.closeTruncatedJson("]"))
    }

    // ===== repairTruncatedJson =====

    @Test
    fun `不闭合的合法对象能被修好`() {
        val out = AiToolParamRepair.repairTruncatedJson("""{"path":"a.txt","content":"hi""")
        assertNotNull(out)
        assertEquals("a.txt", JSONObject(out!!).optString("path"))
        assertEquals("hi", JSONObject(out).optString("content"))
    }

    @Test
    fun `截在逗号中间时回退到逗号前重试`() {
        // 补括号后是 {"a":1,} → 非法 ⇒ 回退到 1 之后 ⇒ {"a":1}
        val out = AiToolParamRepair.repairTruncatedJson("""{"a":1,,"b":2""")
        assertNotNull(out)
        assertEquals(1, JSONObject(out!!).optInt("a"))
    }

    @Test
    fun `截在半截数字上时回退到冒号前重试`() {
        // 12.34 截成 12. ⇒ 补括号后 {"a":12.} 非法 ⇒ 回退 ⇒ {"a":12}
        val out = AiToolParamRepair.repairTruncatedJson("""{"a":12.""")
        assertNotNull(out)
        assertEquals(12, JSONObject(out!!).optInt("a"))
    }

    @Test
    fun `不是对象则直接放弃`() {
        // 工具参数只可能是对象；数组/裸字符串不在自愈范围
        assertNull(AiToolParamRepair.repairTruncatedJson("""[1,2,3"""))
        assertNull(AiToolParamRepair.repairTruncatedJson("""hello"""))
        assertNull(AiToolParamRepair.repairTruncatedJson(""))
    }

    @Test
    fun `空对象无需修复返 null`() {
        // ⚠️ 契约细节（实测确认）：**已闭合的合法 JSON 返 null，不返原串**。
        // `closeTruncatedJson` 对"本来就闭合"一律返 null（不做事），上层把它当
        // "没修成"→ 再退到结构位 → `{}` 里 indexOfLast 找不到 , : { [ ⇒ 放弃。
        // 调用方（executeTool）只在**首次解析失败**后才调本函数，所以正常 `{}`
        // 走不到这里，返 null 不会造成误判。
        assertNull(AiToolParamRepair.repairTruncatedJson("{}"))
    }

    @Test
    fun `截到只剩左括号时补成空对象`() {
        // ⚠️ 已确认的既有行为（2026-10-05 抽单测时暴露，**本次纯重构未改**）：
        // `repairTruncatedJson("{")` 返回 "{}"（能补齐且能解析），
        // 意味着「参数整段被截没」的工具调用会**带着空参数真的去执行**。
        //
        // 影响面有限但非零：多数工具会回「参数不能为空」这类教学式错误让模型改小，
        // 而不是静默做错事；真正的风险是那些参数全可选、默认语义危险的工具。
        // 收紧它属于**行为变更**（会让这类调用从"执行"变成"报错"），
        // 因此不在纯重构里做 —— 单测先如实锁住现状，改与不改单独决策。
        assertEquals("{}", AiToolParamRepair.repairTruncatedJson("{"))
    }

    @Test
    fun `带首尾空白也能修`() {
        val out = AiToolParamRepair.repairTruncatedJson("""  {"a":1  """)
        assertEquals("""{"a":1}""", out)
    }

    @Test
    fun `无可退的结构位时放弃`() {
        // 截断正落在 key 中间（`{"a`）：补完是 `{"a"}`，org.json 解析失败；
        // 再退时结构位里只有 0 位的 `{` ⇒ lastStruct<=0 ⇒ 放弃，不切成空串硬凑。
        assertNull(AiToolParamRepair.repairTruncatedJson("""{"a"""))
    }

    @Test
    fun `org_json 对畸形 token 宽松接受则原样返回`() {
        // ⚠️ 记录一条实测事实（不是期望，是**当前**行为）：`{"a":@@}` 补括号后
        // org.json 居然解析成功（宽松 tokener 跳过了未知 token）⇒ 本函数原样返回它。
        // 意味着自愈并不保证"一定语义正确"，只保证"多数截断能被救回来"。
        // 真要收紧得在调用侧校验参数，不是本函数该管的事。
        assertEquals("""{"a":@@}""", AiToolParamRepair.repairTruncatedJson("""{"a":@@"""))
    }

    // ===== editDistance =====

    @Test
    fun `编辑距离基础用例`() {
        assertEquals(0, AiToolParamRepair.editDistance("abc", "abc"))
        assertEquals(3, AiToolParamRepair.editDistance("", "abc"))
        assertEquals(3, AiToolParamRepair.editDistance("abc", ""))
        assertEquals(0, AiToolParamRepair.editDistance("", ""))
        assertEquals(1, AiToolParamRepair.editDistance("abc", "abd"))
        assertEquals(1, AiToolParamRepair.editDistance("abc", "ab"))
    }

    @Test
    fun `拼错工具名的距离在纠错阈值内`() {
        // executeTool 的未知工具提示要求 distance <= 2
        assertTrue(AiToolParamRepair.editDistance("get_whether", "get_weather") <= 2)
        assertTrue(AiToolParamRepair.editDistance("read_fil", "read_file") <= 2)
        // 完全不同的名字不该被"善意"建议
        assertTrue(AiToolParamRepair.editDistance("send_sms", "install_apk") > 2)
    }

    // ===== codeFileTarget =====

    @Test
    fun `提取 project 与 file`() {
        assertEquals(
            "demo" to "main.html",
            AiToolParamRepair.codeFileTarget("""{"project":"demo","file":"main.html","content":"x"}"""),
        )
    }

    @Test
    fun `裁掉首尾空白`() {
        assertEquals(
            "demo" to "a.txt",
            AiToolParamRepair.codeFileTarget("""{"project":"  demo  ","file":"  a.txt  "}"""),
        )
    }

    @Test
    fun `缺字段或字段为空则返回 null`() {
        // 返 null 而不是空串：调用方据此判定"这轮不是 save_code_file"
        assertNull(AiToolParamRepair.codeFileTarget("""{"content":"x"}"""))
        assertNull(AiToolParamRepair.codeFileTarget("""{"project":"demo"}"""))
        assertNull(AiToolParamRepair.codeFileTarget("""{"project":"","file":"a.txt"}"""))
        assertNull(AiToolParamRepair.codeFileTarget("""{"project":"demo","file":"   "}"""))
    }

    @Test
    fun `非法 JSON 不抛异常`() {
        // 关键：工具执行路径上抛异常会走重试+失败上报，必须是安静返 null
        assertNull(AiToolParamRepair.codeFileTarget("""{"project":"""))
        assertNull(AiToolParamRepair.codeFileTarget("not json at all"))
        assertNull(AiToolParamRepair.codeFileTarget(""))
    }
}
