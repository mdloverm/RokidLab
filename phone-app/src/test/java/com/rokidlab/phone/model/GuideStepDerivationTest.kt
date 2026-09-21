package com.rokidlab.phone.model

import com.rokidlab.phone.design.RokidHostApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 引导步骤推导 —— 锁住「权限」步（v3.9 新增）的三条不变式。
 *
 * 这一步插在「安装眼镜端应用」之前，属于**会挡住老用户**的位置，因此推导规则必须钉死：
 *  1. [PrerequisitesState.permissionsReady] 默认 true —— 没显式实测到缺项时这一步不出现；
 *  2. 权限步排在 [GuideStep.INSTALL_LINK] 之前（需求：装机前先把权限开齐）；
 *  3. 权限步排在 [GuideStep.AUTHORIZE] 之后（没授权时链路都没通，先谈权限没意义）。
 */
class GuideStepDerivationTest {

    private fun authorized(permissionsReady: Boolean, rokidLinkInstalled: Boolean) = PrerequisitesState(
        hostApp = RokidHostApp.DEFAULT,
        mirrorSourceSelected = true,
        authorized = true,
        permissionsReady = permissionsReady,
        rokidLinkInstalled = rokidLinkInstalled,
    )

    @Test
    fun `默认 permissionsReady 为 true —— 老用户不会被权限步拦住`() {
        assertEquals(true, PrerequisitesState().permissionsReady)
        // 走到「安装眼镜端」这一步时，说明前面几步都过了；此时若权限状态未实测，不应凭空多出一个权限步
        assertEquals(GuideStep.INSTALL_LINK, authorized(permissionsReady = true, rokidLinkInstalled = false).currentGuideStep)
    }

    @Test
    fun `有缺项时权限步排在安装眼镜端之前`() {
        val state = authorized(permissionsReady = false, rokidLinkInstalled = false)
        assertEquals(GuideStep.PERMISSIONS, state.currentGuideStep)
        assertTrue(GuideStep.PERMISSIONS.ordinal < GuideStep.INSTALL_LINK.ordinal)
    }

    @Test
    fun `未授权时先授权再谈权限`() {
        val state = authorized(permissionsReady = false, rokidLinkInstalled = false).copy(authorized = false)
        assertEquals(GuideStep.AUTHORIZE, state.currentGuideStep)
    }

    @Test
    fun `权限已齐时直接跳到安装步`() {
        val state = authorized(permissionsReady = true, rokidLinkInstalled = false)
        assertEquals(GuideStep.INSTALL_LINK, state.currentGuideStep)
    }

    @Test
    fun `权限步未过时商店不开放`() {
        val blocked = authorized(permissionsReady = false, rokidLinkInstalled = false)
        assertFalse(blocked.canInstallApps)
        val ready = blocked.copy(permissionsReady = true, rokidLinkInstalled = true, wifiConfigured = true)
        assertEquals(GuideStep.READY, ready.currentGuideStep)
        assertTrue(ready.canInstallApps)
    }

    @Test
    fun `逐个推进顺序与枚举顺序一致`() {
        val steps = listOf(
            PrerequisitesState(),
            PrerequisitesState(hostApp = RokidHostApp.DEFAULT),
            authorized(permissionsReady = true, rokidLinkInstalled = false).copy(authorized = false),
            authorized(permissionsReady = false, rokidLinkInstalled = false),
            authorized(permissionsReady = true, rokidLinkInstalled = false),
            authorized(permissionsReady = true, rokidLinkInstalled = true),
            authorized(permissionsReady = true, rokidLinkInstalled = true).copy(wifiConfigured = true),
        ).map { it.currentGuideStep }
        assertEquals(
            listOf(
                GuideStep.SELECT_HOST_APP,
                GuideStep.SELECT_MIRROR_SOURCE,
                GuideStep.AUTHORIZE,
                GuideStep.PERMISSIONS,
                GuideStep.INSTALL_LINK,
                GuideStep.CONFIGURE_WIFI,
                GuideStep.READY,
            ),
            steps,
        )
    }
}
