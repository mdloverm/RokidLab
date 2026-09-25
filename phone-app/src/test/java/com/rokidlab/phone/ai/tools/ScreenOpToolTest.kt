package com.rokidlab.phone.ai.tools

import com.rokidlab.phone.access.LabAccessibility
import com.rokidlab.phone.access.ScreenGuard
import com.rokidlab.phone.ai.ToolRegistry
import com.rokidlab.phone.ai.ToolRisk
import com.rokidlab.phone.ai.ToolRiskMap
import com.rokidlab.phone.ai.approval.PageScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 屏幕操作域（无障碍）的**准入与安全不变式**回归测试。
 *
 * 这一域是"让 AI 在整台手机上动手"的能力，出错的代价比别的域高一个量级，
 * 所以把四条边界锁死在测试里 —— 它们都是**改一行就可能悄悄破掉、且不报错**的那种：
 *
 *  1. 风险档（`read_screen` 虽然只读，但**必须**按本机副作用档登记，见下）；
 *  2. 不进只读/无人值守名单（用户不在场时不许读屏、更不许代点）；
 *  3. 不对 AIUI 页面开放（页面是第三方制品，与 `run_shell` 同一条判据）；
 *  4. 提交类文案 / 无文案坐标点击**必须**先问用户。
 */
class ScreenOpToolTest {

    private val screenTools = listOf(
        "read_screen", "tap_screen", "swipe_screen", "press_key", "type_text",
    )

    @Test
    fun `屏幕操作工具都登记在 screen 域且风险档为本机副作用`() {
        val registered = ToolRegistry.toolList.map { it.name }.toSet()
        screenTools.forEach { name ->
            assertTrue("$name 没有登记进工具表（模型永远调不到，且不报错）", name in registered)
            val meta = ToolRegistry.toolList.first { it.name == name }
            assertEquals("$name 必须在 screen 域", ToolRegistry.DOMAIN_SCREEN, meta.group)
            // read_screen 是"只读动作"却按 LOCAL_SIDE_EFFECT 登记，是**刻意**的：
            // 只读档会自动进入无人值守白名单，而无人值守时静默读屏不是产品意图（理由写在 provider 注释里）
            assertEquals(
                "$name 的风险档必须是 LOCAL_SIDE_EFFECT",
                ToolRisk.LOCAL_SIDE_EFFECT,
                ToolRiskMap.riskOf(name),
            )
        }
        assertEquals(
            "screen 域必须进 DOMAIN_ALL，否则主 Agent 会话根本装配不到它",
            true,
            ToolRegistry.DOMAIN_SCREEN in ToolRegistry.DOMAIN_ALL,
        )
    }

    @Test
    fun `屏幕操作不进只读名单也不进无人值守名单`() {
        val readOnly = ToolRegistry.readOnlyToolNames()
        val unattended = ToolRegistry.unattendedToolNames()
        screenTools.forEach { name ->
            assertFalse("$name 不得进只读名单（自主任务会在用户不在场时读屏/代点）", name in readOnly)
            assertFalse("$name 不得进无人值守名单", name in unattended)
        }
    }

    @Test
    fun `屏幕操作域不对 AIUI 页面开放`() {
        assertFalse(
            "screen 域必须与 shell 域一起从页面准入域里摘掉（tap_screen 是整机触摸注入原语）",
            ToolRegistry.DOMAIN_SCREEN in PageScope.ALLOWED_DOMAINS,
        )
        screenTools.forEach { name ->
            assertNotNull(
                "$name 必须被页面准入门拒绝",
                PageScope.rejectReason(name, PageScope.pageVisibleTools(), PageScope::domainOf),
            )
        }
    }

    @Test
    fun `点击类工具的失败重试口径`() {
        val sideEffect = ToolRegistry.SIDE_EFFECT_TOOLS
        // 点击/输入可能触发不可逆动作，存在"命令已送达、回执丢失"形态 ⇒ 失败不自动重放
        listOf("tap_screen", "type_text").forEach {
            assertTrue("$it 必须是 sideEffect（失败不重试，避免重复点/重复输入）", it in sideEffect)
        }
        // 读屏/滑动/按键重放一次的代价很小，允许瞬时失败重试
        listOf("read_screen", "swipe_screen", "press_key").forEach {
            assertFalse("$it 不该被标成 sideEffect", it in sideEffect)
        }
    }

    @Test
    fun `提交类文案与无文案目标都必须先要用户确认`() {
        // 提交类关键词命中的按钮：即使是"点一下"，也不许静默执行
        listOf("发送", "立即支付", "确认转账", "删除", "Submit", "Sign Up").forEach { label ->
            assertNotNull("「$label」属于提交类动作，必须要求确认", ScreenGuard.confirmReason(label))
        }
        // 低代价高频动作不能被拦：把它们纳入会让正常流程每步一问，模型反而会去找绕过办法
        listOf("搜索", "下一页", "登录", "Wi-Fi", "关注", "返回").forEach { label ->
            assertNull("「$label」不该被要求确认", ScreenGuard.confirmReason(label))
        }
        // 无文案 = 不知道点的是什么（裸坐标、纯图标控件）
        assertTrue("无文案目标的点击必须要求确认", ScreenGuard.needsConfirm(null))
        assertTrue("空文案目标的点击必须要求确认", ScreenGuard.needsConfirm("   "))
        assertTrue("提交类文案同样要确认", ScreenGuard.needsConfirm("立即支付"))
        assertFalse("普通文案不该被要求确认", ScreenGuard.needsConfirm("搜索"))
    }

    @Test
    fun `确认话术与网页侧同一条协议`() {
        val message = ScreenGuard.message("点击「发送」", "它的文案是「发送」，属于提交类动作")
        assertTrue(
            "必须复用网页侧的「需要你确认：」前缀 —— 模型只认这一个分岔信号，" +
                "两套前缀会让它在屏幕操作上照旧执行下去",
            message.startsWith(com.rokidlab.phone.browser.BrowserGuard.NEED_CONFIRM_PREFIX),
        )
        assertTrue("话术里要让模型知道这一步没执行", message.contains("没有执行"))
        assertTrue("话术里要给出唯一出路（拿到用户同意后带 confirmed=true 重调）", message.contains("confirmed=true"))
    }

    @Test
    fun `按键名单含返回与桌面且不含锁屏`() {
        assertEquals(LabAccessibility.Key.BACK, LabAccessibility.Key.parse("back"))
        assertEquals(LabAccessibility.Key.HOME, LabAccessibility.Key.parse("home"))
        assertEquals(LabAccessibility.Key.RECENTS, LabAccessibility.Key.parse("recents"))
        assertEquals(LabAccessibility.Key.RECENTS, LabAccessibility.Key.parse("overview"))
        assertEquals(LabAccessibility.Key.NOTIFICATIONS, LabAccessibility.Key.parse("notifications"))
        assertEquals(LabAccessibility.Key.QUICK_SETTINGS, LabAccessibility.Key.parse("quick_settings"))
        // 大小写与空格容错：模型常写成 "Home " / "Back"
        assertEquals(LabAccessibility.Key.HOME, LabAccessibility.Key.parse(" Home "))

        assertNull("不认识的按键必须返回 null（由工具如实回报可用清单）", LabAccessibility.Key.parse("power"))
        assertNull(
            "锁屏绝不能支持：锁上之后本 App 自己解不开，只会造出「把用户手机锁住」这个后果",
            LabAccessibility.Key.parse("lock"),
        )
        assertTrue(
            "可用清单必须含 back（模型靠它做「返回上一页」，各 App 的返回箭头位置并不统一）",
            LabAccessibility.Key.supported.contains("back"),
        )
    }
}
