package com.rokidlab.phone.hid

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HID 跨品牌兼容层（[BtHidCompat]）与描述符构造器（[BluetoothHidManager]）的纯逻辑回归测试。
 *
 * 为什么值得锁：这些字节是「某些品牌能用、某些品牌完全没反应」的直接根因 ——
 *  - 描述符里没声明的 Report ID 必须**丢弃**，硬发会被严格蓝牙栈整包拒绝（[BtHidCompat.normalize]）；
 *  - 报告长度必须与描述符声明一致（多则截断、少则补零）；
 *  - 描述符里的 Report ID 集合必须与 [BtHidCompat.declaredReportIds] 完全对应
 *    （两侧漂移 = 一整类报告静默丢失，真机上只表现为「手柄鼠标页没反应」）。
 *
 * 不含真机依赖：不调 `detectStack()`（走系统属性），只测纯策略与字节拼接。
 */
class HidReportTest {

    @After
    fun tearDown() {
        BtHidCompat.setManualMode(null)
    }

    // ── 描述符声明的报告长度 ──

    @Test
    fun `declaredLength 覆盖全部描述符种类`() {
        // FULL / QTI_FULL：键盘 2、Consumer 2、鼠标 4
        for (kind in listOf(BtHidCompat.DescriptorKind.FULL, BtHidCompat.DescriptorKind.QTI_FULL)) {
            assertEquals("$kind 键盘", 2, BtHidCompat.declaredLength(kind, 1))
            assertEquals("$kind Consumer", 2, BtHidCompat.declaredLength(kind, 2))
            assertEquals("$kind 鼠标", 4, BtHidCompat.declaredLength(kind, 3))
            assertEquals("$kind 未声明的 4 号", -1, BtHidCompat.declaredLength(kind, 4))
        }
        // QTI 精简描述符：无鼠标，Report ID 3 必须判为未声明
        assertEquals(2, BtHidCompat.declaredLength(BtHidCompat.DescriptorKind.QTI, 1))
        assertEquals(2, BtHidCompat.declaredLength(BtHidCompat.DescriptorKind.QTI, 2))
        assertEquals("QTI 精简描述符不含鼠标", -1, BtHidCompat.declaredLength(BtHidCompat.DescriptorKind.QTI, 3))
        // 手柄：只有 4 号，4 字节
        for (kind in listOf(BtHidCompat.DescriptorKind.GAMEPAD, BtHidCompat.DescriptorKind.QTI_GAMEPAD)) {
            assertEquals(4, BtHidCompat.declaredLength(kind, 4))
            assertEquals(-1, BtHidCompat.declaredLength(kind, 1))
        }
    }

    @Test
    fun `declaredReportIds 与描述符种类对应`() {
        assertEquals(listOf(1, 2, 3), BtHidCompat.declaredReportIds(BtHidCompat.DescriptorKind.FULL))
        assertEquals(listOf(1, 2), BtHidCompat.declaredReportIds(BtHidCompat.DescriptorKind.QTI))
        assertEquals(listOf(1, 2, 3), BtHidCompat.declaredReportIds(BtHidCompat.DescriptorKind.QTI_FULL))
        assertEquals(listOf(4), BtHidCompat.declaredReportIds(BtHidCompat.DescriptorKind.GAMEPAD))
        assertEquals(listOf(4), BtHidCompat.declaredReportIds(BtHidCompat.DescriptorKind.QTI_GAMEPAD))
    }

    // ── 报告归一化（「某些品牌完全没反应」的直接根因）──

    @Test
    fun `normalize 超长截断`() {
        val out = BtHidCompat.normalize(1, byteArrayOf(0x02, 0x1D, 0x99.toByte(), 0x88.toByte()), BtHidCompat.DescriptorKind.FULL)
        assertArrayEquals(byteArrayOf(0x02, 0x1D), out)
    }

    @Test
    fun `normalize 不足补零`() {
        val out = BtHidCompat.normalize(3, byteArrayOf(0x01), BtHidCompat.DescriptorKind.FULL)
        assertArrayEquals(byteArrayOf(0x01, 0x00, 0x00, 0x00), out)
    }

    @Test
    fun `normalize 长度一致时原样返回`() {
        val data = byteArrayOf(0x00, 0x04)
        assertArrayEquals(data, BtHidCompat.normalize(2, data, BtHidCompat.DescriptorKind.FULL))
    }

    @Test
    fun `normalize 未声明的 Report ID 返回 null 由调用方丢弃`() {
        assertNull(
            "QTI 精简描述符没有鼠标报告：硬发 Report ID 3 会被严格栈整包拒绝",
            BtHidCompat.normalize(3, byteArrayOf(1, 2, 3, 4), BtHidCompat.DescriptorKind.QTI),
        )
        assertNull(BtHidCompat.normalize(1, byteArrayOf(1, 2), BtHidCompat.DescriptorKind.GAMEPAD))
        assertNull(BtHidCompat.normalize(9, byteArrayOf(1, 2), BtHidCompat.DescriptorKind.FULL))
    }

    // ── 发送方式候选顺序 ──

    @Test
    fun `modeOrder 默认顺序：QTI 优先手动拼 Report ID，其余优先标准方式`() {
        assertEquals(
            listOf(
                BtHidCompat.SendMode.PREFIXED,
                BtHidCompat.SendMode.SET_REPORT,
                BtHidCompat.SendMode.STANDARD,
            ),
            BtHidCompat.modeOrder(BtHidCompat.Stack.QTI),
        )
        for (stack in listOf(
            BtHidCompat.Stack.MEDIATEK,
            BtHidCompat.Stack.BROADCOM,
            BtHidCompat.Stack.GOOGLE,
            BtHidCompat.Stack.SAMSUNG,
            BtHidCompat.Stack.UNKNOWN,
        )) {
            assertEquals(
                "$stack 应先试标准 sendReport",
                listOf(
                    BtHidCompat.SendMode.STANDARD,
                    BtHidCompat.SendMode.PREFIXED,
                    BtHidCompat.SendMode.SET_REPORT,
                ),
                BtHidCompat.modeOrder(stack),
            )
        }
    }

    @Test
    fun `modeOrder 人工强制后只剩该方式`() {
        BtHidCompat.setManualMode(BtHidCompat.SendMode.SET_REPORT)
        assertEquals(BtHidCompat.SendMode.SET_REPORT, BtHidCompat.currentManualMode())
        assertEquals(listOf(BtHidCompat.SendMode.SET_REPORT), BtHidCompat.modeOrder(BtHidCompat.Stack.QTI))
        assertEquals(listOf(BtHidCompat.SendMode.SET_REPORT), BtHidCompat.modeOrder(BtHidCompat.Stack.UNKNOWN))

        BtHidCompat.setManualMode(null)
        assertNull(BtHidCompat.currentManualMode())
        assertEquals(3, BtHidCompat.modeOrder(BtHidCompat.Stack.UNKNOWN).size)
    }

    // ── 时序与体积参数 ──

    @Test
    fun `时序与描述符体积参数按栈取值`() {
        assertEquals("QTI 连续写入易丢包，需要节流", 8L, BtHidCompat.minReportIntervalMs(BtHidCompat.Stack.QTI))
        assertEquals(4L, BtHidCompat.minReportIntervalMs(BtHidCompat.Stack.MEDIATEK))
        assertEquals(0L, BtHidCompat.minReportIntervalMs(BtHidCompat.Stack.UNKNOWN))

        assertEquals(500L, BtHidCompat.channelWarmupMs(BtHidCompat.Stack.QTI))
        assertEquals(400L, BtHidCompat.channelWarmupMs(BtHidCompat.Stack.SAMSUNG))
        assertEquals(400L, BtHidCompat.channelWarmupMs(BtHidCompat.Stack.MEDIATEK))
        assertEquals(300L, BtHidCompat.channelWarmupMs(BtHidCompat.Stack.BROADCOM))

        assertEquals("QTI 的 HID_DEV_MTU_SIZE 固定 64", 64, BtHidCompat.maxDescriptorBytes(BtHidCompat.Stack.QTI))
        assertEquals(Int.MAX_VALUE, BtHidCompat.maxDescriptorBytes(BtHidCompat.Stack.MEDIATEK))
    }

    // ── 描述符字节 ──

    @Test
    fun `buildHidDescriptor 键码数量写入 Report Count 并收敛到 1-6`() {
        assertTrue(contains(build(1), byteArrayOf(0x75, 0x08, 0x95.toByte(), 0x01)))
        assertTrue(contains(build(3), byteArrayOf(0x75, 0x08, 0x95.toByte(), 0x03)))
        assertTrue(contains(build(6), byteArrayOf(0x75, 0x08, 0x95.toByte(), 0x06)))
        assertArrayEquals("0 收敛为 1，避免 Report Count(0)", build(1), build(0))
        assertArrayEquals("超过 6 收敛为 6（USB HID 键盘最多 6 键无冲）", build(6), build(9))
    }

    @Test
    fun `buildQtiCompatibleDescriptor 含 Mouse 时追加 Mouse 段且长度稳定`() {
        val withoutMouse = BluetoothHidManager.buildQtiCompatibleDescriptor(includeMouse = false)
        val withMouse = BluetoothHidManager.buildQtiCompatibleDescriptor(includeMouse = true)

        // 实测长度（67 / 121）。注意：曾有注释声称「≤ 64 字节（QTI HID_DEV_MTU_SIZE）」，
        // 与实测不符 —— 真机按此长度注册即可成功，容错靠「注册被拒 → 回退无 Mouse 版」重试链。
        // 锁定长度是为了让任何描述符改动都必须是刻意为之（改完要真机回归 + 同步本测试）。
        assertEquals("无 Mouse 精简描述符长度", 67, withoutMouse.size)
        assertEquals("含 Mouse 描述符长度", 121, withMouse.size)
        assertEquals("Mouse 段（Report ID 3）长度固定 54 字节", 54, withMouse.size - withoutMouse.size)
        assertArrayEquals(
            "含 Mouse 版本必须是无 Mouse 版本 + 追加 Mouse 段（保证回退不回归）",
            withoutMouse,
            withMouse.copyOf(withoutMouse.size),
        )
    }

    @Test
    fun `所有描述符声明的 Report ID 与 declaredReportIds 完全一致`() {
        assertEquals(
            "FULL 描述符",
            BtHidCompat.declaredReportIds(BtHidCompat.DescriptorKind.FULL).toSet(),
            reportIdsOf(build(1)),
        )
        assertEquals(
            "QTI 描述符",
            BtHidCompat.declaredReportIds(BtHidCompat.DescriptorKind.QTI).toSet(),
            reportIdsOf(BluetoothHidManager.buildQtiCompatibleDescriptor(includeMouse = false)),
        )
        assertEquals(
            "QTI + Mouse 描述符",
            BtHidCompat.declaredReportIds(BtHidCompat.DescriptorKind.QTI_FULL).toSet(),
            reportIdsOf(BluetoothHidManager.buildQtiCompatibleDescriptor(includeMouse = true)),
        )
        assertEquals(
            "QTI 手柄描述符",
            BtHidCompat.declaredReportIds(BtHidCompat.DescriptorKind.QTI_GAMEPAD).toSet(),
            reportIdsOf(BluetoothHidManager.buildQtiGamepadDescriptor()),
        )
        assertEquals(
            "手柄描述符",
            BtHidCompat.declaredReportIds(BtHidCompat.DescriptorKind.GAMEPAD).toSet(),
            reportIdsOf(BluetoothHidManager.buildGamepadDescriptor()),
        )
    }

    // ── 工具 ──

    private fun build(kbdKeycodeCount: Int) = BluetoothHidManager.buildHidDescriptor(kbdKeycodeCount)

    private fun contains(haystack: ByteArray, needle: ByteArray): Boolean {
        if (needle.isEmpty() || haystack.size < needle.size) return false
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return true
        }
        return false
    }

    /** 从描述符里抽出所有 `0x85, <id>`（Report ID 项）—— 与 declaredReportIds 做交叉校验 */
    private fun reportIdsOf(descriptor: ByteArray): Set<Int> {
        val ids = linkedSetOf<Int>()
        var i = 0
        while (i + 1 < descriptor.size) {
            if (descriptor[i] == 0x85.toByte()) {
                ids += descriptor[i + 1].toInt() and 0xFF
                i += 2
            } else {
                i++
            }
        }
        return ids
    }
}
