package com.rokidlab.phone.ai

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具 Schema **共享实例**的护栏测试（2026-10-05）。
 *
 * 背景：`AiConversationService.buildTools` 单次请求内最多调用伪工具 `schema()` **5 次**
 * （buildTools / 菜单升级 / 菜单点名 / 探测升级 / aiuiMode 切换各一次），
 * 每次都重建 `JSONObject` 嵌套结构 + 重新拷贝长中文字符串。
 * 现已改为 `by lazy` 缓存**同一个实例**，所以必须钉死「消费侧绝不改这个对象」这个前提 ——
 * 一旦有人改了它，缓存就变成**跨轮跨线程的污染源**（症状是工具描述在多轮之间缓慢漂移，
 * 极难定位）。
 *
 * 覆盖的不变式：
 *  1. 缓存确实生效（同一实例）—— 否则这轮优化等于没做，白担风险；
 *  2. 序列化结果逐字节稳定 —— prompt caching 依赖前缀稳定，这是**最关键**的一条；
 *  3. 所有伪工具的 `name` 都能被 [ToolRegistry.nameOf] 正确抽出（新增的单数版 API）；
 *  4. 消费侧（校验器 / 装配 / 序列化）确实只读。
 */
class PseudoToolSchemaCacheTest {

    // ── 1. 缓存生效 ──

    @Test
    fun `伪工具 schema 缓存后返回同一实例`() {
        assertSame(
            "load_skill schema 未被缓存 —— 会退回每次重建的开销",
            SkillRegistry.schema(),
            SkillRegistry.schema(),
        )
        assertSame(SkillRegistry.sectionSchema(), SkillRegistry.sectionSchema())
        assertSame(SkillRegistry.installSchema(), SkillRegistry.installSchema())
        assertSame(SkillRegistry.listSchema(), SkillRegistry.listSchema())
        assertSame(SkillRegistry.deleteSchema(), SkillRegistry.deleteSchema())
        assertSame(AgentPlan.schema(), AgentPlan.schema())
        assertSame(LongTermMemoryManager.schema(), LongTermMemoryManager.schema())
    }

    // ── 2. 序列化逐字节稳定（prompt caching 的前提）──

    @Test
    fun `伪工具 schema 序列化逐字节稳定`() {
        // 同一实例重复序列化当然相同；真正要防的是「有人往 schema 里塞了非常量内容」
        // （例如时间戳 / 随机数 / 读取外部状态），那会让系统提示的缓存前缀每轮失效。
        val first = SkillRegistry.schema().toString()
        val second = SkillRegistry.schema().toString()
        assertEquals("schema 序列化不稳定 ⇒ prompt caching 前缀每轮失效", first, second)

        // 关键结构断言：缓存的是完整 schema，不是空壳
        val fn = SkillRegistry.schema().getJSONObject("function")
        assertEquals("load_skill", fn.getString("name"))
        assertTrue("description 不能为空", fn.getString("description").length > 50)
        assertEquals("object", fn.getJSONObject("parameters").getString("type"))
    }

    @Test
    fun `update_plan 与 manage_memory 的 schema 结构未被缓存改动`() {
        // update_plan 的 steps 有历史事故（2026-09-15：误写成数组节点导致整请求 400），
        // 改缓存时最容易顺手改错结构，故钉死。
        val steps = AgentPlan.schema().getJSONObject("function")
            .getJSONObject("parameters")
            .getJSONObject("properties")
            .getJSONObject("steps")
        assertEquals("array", steps.getString("type"))
        assertNotNull("steps 必须有 items 定义", steps.optJSONObject("items"))

        assertEquals(
            "manage_memory",
            LongTermMemoryManager.schema().getJSONObject("function").getString("name"),
        )
    }

    // ── 3. 新增的单数版 API ──

    @Test
    fun `nameOf 抽出工具名`() {
        val schema = JSONObject().put(
            "function",
            JSONObject().put("name", "save_code_file"),
        )
        assertEquals("save_code_file", ToolRegistry.nameOf(schema))
    }

    @Test
    fun `nameOf 对畸形 schema 返回 null 而非抛异常`() {
        // 与 namesOf 的既有语义一致：解析失败按「不在名单」处理，不中断装配。
        assertNull(ToolRegistry.nameOf(JSONObject()))
        assertNull(
            "function 节点缺name 时应返回 null",
            ToolRegistry.nameOf(JSONObject().put("function", JSONObject())),
        )
    }

    @Test
    fun `nameOf 与 namesOf 结果一致`() {
        // 两者是同一判据的单数版 / 复数版，任何漂移都会让路由与装配看到不同的工具集。
        val schemas = listOf(
            SkillRegistry.schema(),
            SkillRegistry.sectionSchema(),
            AgentPlan.schema(),
        )
        assertEquals(ToolRegistry.namesOf(schemas), schemas.mapNotNull { ToolRegistry.nameOf(it) }.toSet())
    }

    // ── 4. 消费侧只读（缓存的前提）──

    @Test
    fun `校验全部伪工具 schema 不修改入参`() {
        val schema = AgentPlan.schema()
        val before = schema.toString()
        // auditToolSchemas 走的就是这条路径（收集全部伪工具 + 校验）
        //签名是 List<Pair<标签, schema>> —— 标签取工具名，与 auditToolSchemas 的 collect() 一致。
        val problems = ToolSchemaValidator.validateAll(
            listOf(
                "update_plan" to AgentPlan.schema(),
                "load_skill" to SkillRegistry.schema(),
                "install_skill" to SkillRegistry.installSchema(),
                "list_skills" to SkillRegistry.listSchema(),
                "manage_memory" to LongTermMemoryManager.schema(),
                "delete_skill" to SkillRegistry.deleteSchema(),
                "load_skill_section" to SkillRegistry.sectionSchema(),
            ),
        )
        assertTrue("伪工具 schema 不应校验失败：$problems", problems.isEmpty())
        assertEquals("校验器修改了 schema 对象 ⇒ 缓存会被污染", before, schema.toString())
    }

    @Test
    fun `伪工具全部登记在案且 name 可被抽出`() {
        // buildTools 依赖这些名字做白名单判断（技能开关 / 静默执行名单等），
        // 任何一个抽不出名字 ⇒ 装配侧会静默漏挂该工具。
        val all = listOf(
            SkillRegistry.schema(),
            SkillRegistry.sectionSchema(),
            SkillRegistry.installSchema(),
            SkillRegistry.listSchema(),
            SkillRegistry.deleteSchema(),
            AgentPlan.schema(),
            LongTermMemoryManager.schema(),
        )
        val names = all.mapNotNull { ToolRegistry.nameOf(it) }
        assertEquals("有伪工具抽不出名字", all.size, names.size)
        assertEquals("伪工具名重复", names.size, names.toSet().size)
        listOf(
            "load_skill", "load_skill_section", "install_skill",
            "list_skills", "delete_skill", "update_plan", "manage_memory",
        ).forEach { expected ->
            assertTrue("缺少伪工具 $expected", names.contains(expected))
        }
    }
}
