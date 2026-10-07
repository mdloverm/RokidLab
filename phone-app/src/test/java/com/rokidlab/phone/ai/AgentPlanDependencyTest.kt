package com.rokidlab.phone.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AgentPlan] 的 `dependsOn` 依赖校验（2026-10-06 新增）。
 *
 * ## 锁的是哪个缺口
 * 原来 `PlanStep(title, status)` 是**平铺数组**，只能表达"顺序"不能表达"前提"。
 * 于是模型可以把「装依赖」标成 done 而「写代码」仍是 pending，系统完全不拦 ——
 * 续做时只看"还有没有 pending"，于是跳过前置条件往下做，产出依赖缺失的产物。
 *
 * ## 判据：宁可漏放、不可误拦
 * 非法依赖（自指 / 前向引用 / 越界）一律**丢弃**而不是报错：拦住模型对 `update_plan`
 * 的正常调用，代价（整个计划丢失）远大于放过一个非法依赖（回到旧行为）。
 */
class AgentPlanDependencyTest {

    private fun steps(vararg spec: Triple<String, String, List<Int>>) =
        spec.map { AgentPlan.PlanStep(it.first, it.second, it.third) }

    // ── 解析 ────────────────────────────────────────────────────

    @Test
    fun `不带dependsOn - 解析正常且依赖为空 - 旧行为不变`() {
        val parsed = AgentPlan.parseSteps(
            """{"steps":[{"title":"A","status":"done"},{"title":"B","status":"pending"}]}"""
        )

        assertNotNull(parsed)
        assertEquals(2, parsed!!.size)
        assertTrue(parsed.all { it.dependsOn.isEmpty() })
    }

    @Test
    fun `合法dependsOn - 被解析出来`() {
        val parsed = AgentPlan.parseSteps(
            """{"steps":[{"title":"装依赖","status":"done"},{"title":"写代码","status":"pending","depends_on":[0]}]}"""
        )

        assertEquals(listOf(0), parsed!![1].dependsOn)
    }

    @Test
    fun `前向引用 - 静默丢弃 - 回到旧行为`() {
        // 第 0 步依赖第 1 步（还没排到）⇒ 顺序本身就蕴含依赖，这个声明是多余的
        val parsed = AgentPlan.parseSteps(
            """{"steps":[{"title":"A","status":"pending","depends_on":[1]},{"title":"B","status":"done"}]}"""
        )

        assertNotNull(parsed)
        assertTrue("前向引用应被丢弃", parsed!![0].dependsOn.isEmpty())
    }

    @Test
    fun `自指与越界 - 一律丢弃而不是抛异常`() {
        val parsed = AgentPlan.parseSteps(
            """{"steps":[{"title":"A","status":"pending","depends_on":[0,5,-1]},{"title":"B","status":"done"}]}"""
        )

        assertNotNull("非法依赖不该让整个调用失败", parsed)
        assertTrue(parsed!![0].dependsOn.isEmpty())
    }

    // ── 校验 ────────────────────────────────────────────────────

    @Test
    fun `依赖未完成却标done - 被判违规`() {
        val s = steps(
            Triple("装依赖", "pending", emptyList()),
            Triple("写代码", "done", listOf(0)),
        )

        // violationsOf 返回 **0-based 下标**（给代码用），文案里才 +1 转成给人看的 1-based
        assertEquals(listOf(1), AgentPlan.violationsOf(s))
        val msg = AgentPlan.violationMessage(s)
        assertNotNull(msg)
        assertTrue("应指出是哪一步（1-based）：$msg", msg!!.contains("第 2 步"))
        assertTrue("应指出缺哪个前置：$msg", msg.contains("装依赖"))
    }

    @Test
    fun `依赖已完成 - 通过`() {
        val s = steps(
            Triple("装依赖", "done", emptyList()),
            Triple("写代码", "done", listOf(0)),
        )

        assertTrue(AgentPlan.violationsOf(s).isEmpty())
        assertNull(AgentPlan.violationMessage(s))
    }

    @Test
    fun `未标done的步骤 - 不算违规 - 顺序内的正常推进不算跳步`() {
        // ⚠️ 真按"数组顺序"强校验会拦住大量合法的乱序推进（模型常先做后面能做的部分），
        // 所以只校验**显式**声明的依赖。
        val s = steps(
            Triple("A", "pending", emptyList()),
            Triple("B", "in_progress", emptyList()),
            Triple("C", "pending", listOf(1)),
        )

        assertTrue(AgentPlan.violationsOf(s).isEmpty())
    }

    @Test
    fun `越界依赖指向不存在的步骤 - 判违规而不是崩`() {
        val s = steps(Triple("A", "done", listOf(9)))

        assertEquals(listOf(0), AgentPlan.violationsOf(s))
    }

    // ── 回执里必须说出来（不静默拒绝整个调用）───────────────────

    @Test
    fun `execute把违规写进回执 - 模型能看到自己标错了`() {
        val out = AgentPlan.execute(
            """{"steps":[{"title":"装依赖","status":"pending"},{"title":"写代码","status":"done","depends_on":[0]}]}"""
        )

        assertTrue("回执必须含步骤清单：$out", out.contains("装依赖"))
        assertTrue("回执必须指出依赖违规：$out", out.contains("⚠️"))
    }

    @Test
    fun `无违规时回执不添加警告`() {
        val out = AgentPlan.execute(
            """{"steps":[{"title":"A","status":"done"},{"title":"B","status":"pending"}]}"""
        )

        assertTrue("不该凭空出现警告：$out", !out.contains("⚠️"))
    }
}
