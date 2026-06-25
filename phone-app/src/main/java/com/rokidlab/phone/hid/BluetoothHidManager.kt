package com.rokidlab.phone.hid

import com.rokidlab.phone.util.AppConfig
import com.rokidlab.phone.util.ManufacturerUtils
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDevice.Callback
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import com.rokidlab.phone.util.LogCollector
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import android.os.Handler
import android.os.Looper

class BluetoothHidManager(private val appContext: Context) {
    companion object {
        private const val TAG = "BluetoothHidManager"

        const val KEY_A      = 0
        const val KEY_B      = 1
        const val KEY_C      = 2
        const val KEY_X      = 3
        const val KEY_Y      = 4
        const val KEY_Z      = 5
        const val KEY_L      = 6
        const val KEY_R      = 7
        const val KEY_SELECT = 8
        const val KEY_START  = 9
        const val KEY_UP     = 10
        const val KEY_DOWN   = 11
        const val KEY_LEFT   = 12
        const val KEY_RIGHT  = 13

        const val STATE_DISCONNECTED = 0
        const val STATE_CONNECTING   = 1
        const val STATE_CONNECTED    = 2
        const val STATE_RETRY_FAILED = 3

        const val CONN_HID = 0

        private const val KEYBOARD_REPORT_ID = 1
        private const val CONSUMER_REPORT_ID = 2
        private const val MOUSE_REPORT_ID   = 3
        private const val GAMEPAD_REPORT_ID = 4

        /**
         * 构建 HID 描述符，指定键盘报告的键码数量。
         * 总键盘报告大小（不含 Report ID）= modifier(1) + kbdKeycodeCount
         */
        fun buildHidDescriptor(kbdKeycodeCount: Int = 1): ByteArray {
            val kc = kbdKeycodeCount.coerceIn(1, 6)
            // 便捷 byte 数组构造
            fun b(vararg ints: Int) = ints.map { it.toByte() }.toByteArray()

            // ── Consumer Control (Report ID 2) ──
            val consumer = b(
                0x05, 0x0C,                             // Usage Page (Consumer)
                0x09, 0x01,                             // Usage (Consumer Control)
                0xA1, 0x01,                             // Collection (Application)
                0x85, CONSUMER_REPORT_ID,               // Report ID (2)
                0x19, 0x00,                             // Usage Minimum (Unassigned)
                0x2A, 0xFF, 0x03,                       // Usage Maximum (1023)
                0x75, 0x10,                             // Report Size (16)
                0x95, 0x01,                             // Report Count (1)
                0x15, 0x00,                             // Logical Minimum (0)
                0x26, 0xFF, 0x03,                       // Logical Maximum (1023)
                0x81, 0x00,                             // Input (Data,Array,Abs)
                0xC0,                                   // End Collection
            )

            // ── Keyboard (Report ID 1) — 动态键码数量 ──
            val keyboard = b(
                0x05, 0x01,                             // Usage Page (Generic Desktop)
                0x09, 0x06,                             // Usage (Keyboard)
                0xA1, 0x01,                             // Collection (Application)
                0x85, KEYBOARD_REPORT_ID,               // Report ID (1)
                // modifier 字节
                0x05, 0x07,                             // Usage Page (Keyboard/Keypad)
                0x19, 0xE0,                             // Usage Minimum (KB Left Ctrl)
                0x29, 0xE7,                             // Usage Maximum (KB Right GUI)
                0x15, 0x00,                             // Logical Minimum (0)
                0x25, 0x01,                             // Logical Maximum (1)
                0x75, 0x01,                             // Report Size (1)
                0x95, 0x08,                             // Report Count (8)
                0x81, 0x02,                             // Input (Data,Var,Abs)
                // keycode(s) 字节
                0x75, 0x08,                             // Report Size (8)
                0x95, kc,                               // Report Count (N)
                0x15, 0x00,                             // Logical Minimum (0)
                0x26, 0xFF, 0x00,                       // Logical Maximum (255)
                0x05, 0x07,                             // Usage Page (Keyboard/Keypad)
                0x19, 0x00,                             // Usage Minimum (0)
                0x29, 0xFF,                             // Usage Maximum (255)
                0x81, 0x00,                             // Input (Data,Array,Abs)
                0xC0,                                   // End Collection
            )

            // ── Mouse (Report ID 3) ──
            val mouse = b(
                0x05, 0x01,
                0x09, 0x02,
                0xA1, 0x01,
                0x85, MOUSE_REPORT_ID,
                0x09, 0x01,
                0xA1, 0x00,
                0x05, 0x09,
                0x19, 0x01,
                0x29, 0x03,
                0x15, 0x00,
                0x25, 0x01,
                0x75, 0x01,
                0x95, 0x03,
                0x81, 0x02,
                0x75, 0x05,
                0x95, 0x01,
                0x81, 0x01,
                0x05, 0x01,
                0x09, 0x30,
                0x09, 0x31,
                0x09, 0x38,
                0x15, 0x81,
                0x25, 0x7F,
                0x75, 0x08,
                0x95, 0x03,
                0x81, 0x06,
                0xC0,
                0xC0,
            )

            return consumer + keyboard + mouse
        }

        /**
         * 构建适用于 QTI 蓝牙栈（MTU 64 字节限制）的轻量 HID 描述符。
         * 仅包含 Keyboard + Consumer Control 两种报告类型，总长度 ≤ 64 字节。
         *
         * QTI 蓝牙栈 HID_DEV_MTU_SIZE 固定为 64（AOSP 默认 512），
         * 超过 MTU 的描述符可能被截断导致眼镜解析失败。
         */
        fun buildQtiCompatibleDescriptor(): ByteArray {
            fun b(vararg ints: Int) = ints.map { it.toByte() }.toByteArray()

            // ── Consumer Control (Report ID 2) — 2字节 ──
            val consumer = b(
                0x05, 0x0C,                       // Usage Page (Consumer)
                0x09, 0x01,                       // Usage (Consumer Control)
                0xA1, 0x01,                       // Collection (Application)
                0x85, CONSUMER_REPORT_ID,         //   Report ID (2)
                0x19, 0x00,                       //   Usage Minimum (0)
                0x2A, 0xFF, 0x03,                 //   Usage Maximum (1023)
                0x75, 0x10,                       //   Report Size (16)
                0x95, 0x01,                       //   Report Count (1)
                0x15, 0x00,                       //   Logical Minimum (0)
                0x26, 0xFF, 0x03,                 //   Logical Maximum (1023)
                0x81, 0x00,                       //   Input (Data,Array,Abs)
                0xC0,                             // End Collection
            )

            // ── Keyboard (Report ID 1) — 2字节 [modifier, keycode] ──
            val keyboard = b(
                0x05, 0x01,                       // Usage Page (Generic Desktop)
                0x09, 0x06,                       // Usage (Keyboard)
                0xA1, 0x01,                       // Collection (Application)
                0x85, KEYBOARD_REPORT_ID,         //   Report ID (1)
                0x05, 0x07,                       //   Usage Page (Keyboard/Keypad)
                0x19, 0xE0,                       //   Usage Minimum (KB Left Ctrl)
                0x29, 0xE7,                       //   Usage Maximum (KB Right GUI)
                0x15, 0x00,                       //   Logical Minimum (0)
                0x25, 0x01,                       //   Logical Maximum (1)
                0x75, 0x01,                       //   Report Size (1)
                0x95, 0x08,                       //   Report Count (8)
                0x81, 0x02,                       //   Input (Data,Var,Abs)
                0x75, 0x08,                       //   Report Size (8)
                0x95, 0x01,                       //   Report Count (1)
                0x15, 0x00,                       //   Logical Minimum (0)
                0x26, 0xFF, 0x00,                 //   Logical Maximum (255)
                0x05, 0x07,                       //   Usage Page (Keyboard/Keypad)
                0x19, 0x00,                       //   Usage Minimum (0)
                0x29, 0xFF,                       //   Usage Maximum (255)
                0x81, 0x00,                       //   Input (Data,Array,Abs)
                0xC0,                             // End Collection
            )

            return consumer + keyboard
        }

        /**
         * 构建 QTI 兼容的 Game Pad HID 描述符（精简版，≤ 64 字节）。
         * 仅包含 Game Pad 类型，不混合其他报告。
         */
        fun buildQtiGamepadDescriptor(): ByteArray {
            fun b(vararg ints: Int) = ints.map { it.toByte() }.toByteArray()
            return b(
                0x05, 0x01,                       // Usage Page (Generic Desktop)
                0x09, 0x05,                       // Usage (Game Pad)
                0xA1, 0x01,                       // Collection (Application)
                0x85, GAMEPAD_REPORT_ID,          //   Report ID (4)
                // 8 buttons + padding → 2 bytes
                0x05, 0x09,                       //   Usage Page (Button)
                0x19, 0x01,                       //   Usage Minimum (Button 1)
                0x29, 0x08,                       //   Usage Maximum (Button 8)
                0x15, 0x00,                       //   Logical Minimum (0)
                0x25, 0x01,                       //   Logical Maximum (1)
                0x75, 0x01,                       //   Report Size (1)
                0x95, 0x08,                       //   Report Count (8)
                0x81, 0x02,                       //   Input (Data,Var,Abs)
                // Hat Switch (DPAD) → 4 bits + padding 4 → 1 byte
                0x05, 0x01,                       //   Usage Page (Generic Desktop)
                0x09, 0x39,                       //   Usage (Hat Switch)
                0x15, 0x00,                       //   Logical Minimum (0)
                0x25, 0x08,                       //   Logical Maximum (8)
                0x75, 0x04,                       //   Report Size (4)
                0x95, 0x01,                       //   Report Count (1)
                0x81, 0x42,                       //   Input (Data,Var,Abs,Null)
                0x75, 0x04,                       //   padding 4 bits
                0x95, 0x01,
                0x81, 0x01,
                // 保留 1 byte
                0x75, 0x08,
                0x95, 0x01,
                0x81, 0x01,
                0xC0,                             // End Collection
            )
        }

        /**
         * 构建 Game Pad HID 描述符。
         * 报告结构（4 字节，Report ID 4）：
         *   Byte 0-1: 14 按钮位图（bit 0=A, bit 1=B, ..., bit 13=Right, +2 填充）
         *   Byte 2: Hat Switch（低 4 位: 0↑1↗2→3↘4↓5↙6←7↖8=释放）
         *   Byte 3: 保留
         */
        fun buildGamepadDescriptor(): ByteArray {
            fun b(vararg ints: Int) = ints.map { it.toByte() }.toByteArray()
            return b(
                0x05, 0x01,                             // Usage Page (Generic Desktop)
                0x09, 0x05,                             // Usage (Game Pad)
                0xA1, 0x01,                             // Collection (Application)
                0x85, GAMEPAD_REPORT_ID,                //   Report ID (4)

                // 14 buttons bitmap → 2 bytes (16 bits)
                0x05, 0x09,                             //   Usage Page (Button)
                0x19, 0x01,                             //   Usage Minimum (Button 1)
                0x29, 0x0E,                             //   Usage Maximum (Button 14)
                0x15, 0x00,                             //   Logical Minimum (0)
                0x25, 0x01,                             //   Logical Maximum (1)
                0x75, 0x01,                             //   Report Size (1)
                0x95, 0x0E,                             //   Report Count (14)
                0x81, 0x02,                             //   Input (Data,Var,Abs)
                0x75, 0x01,                             //   padding 2 bits
                0x95, 0x02,
                0x81, 0x01,                             //   Input (Const,Var,Abs)

                // Hat Switch (DPAD) → 4 bits
                0x05, 0x01,                             //   Usage Page (Generic Desktop)
                0x09, 0x39,                             //   Usage (Hat Switch)
                0x15, 0x00,                             //   Logical Minimum (0)
                0x25, 0x08,                             //   Logical Maximum (8)
                0x75, 0x04,                             //   Report Size (4)
                0x95, 0x01,                             //   Report Count (1)
                0x81, 0x42,                             //   Input (Data,Var,Abs,Null)

                // padding 4 bits (to align byte)
                0x75, 0x04,
                0x95, 0x01,
                0x81, 0x01,

                // 保留字节
                0x75, 0x08,
                0x95, 0x01,
                0x81, 0x01,

                0xC0,                                   // End Collection
            )
        }
    }

    // ===== Consumer Control 键码 (Usage Page 0x0C) =====
    // 用于 UI 导航，2字节 = [usage_lsb, usage_msb]
    private val KEY_CONSUMER = mapOf(
        KEY_UP     to byteArrayOf(0x42, 0x00),   // MenuUp
        KEY_DOWN   to byteArrayOf(0x43, 0x00),   // MenuDown
        KEY_LEFT   to byteArrayOf(0x44, 0x00),   // MenuLeft
        KEY_RIGHT  to byteArrayOf(0x45, 0x00),   // MenuRight
        KEY_SELECT to byteArrayOf(0x41, 0x00),   // MenuPick (OK)
        KEY_START  to byteArrayOf(0x41, 0x00),   // MenuPick (OK)
    )

    // ===== 键盘键码 (Usage Page 0x07) =====
    // 2字节 = [modifier, keycode]
    // MODE_GAME 下全部用键盘；MODE_UI 下 A/B/X/Y/Z/L/R 用键盘，方向键用 Consumer
    private val KEY_HID = mapOf(
        KEY_A      to byteArrayOf(0x00, 0x1D),  // z
        KEY_B      to byteArrayOf(0x00, 0x1B),  // x
        KEY_C      to byteArrayOf(0x00, 0x06),  // c
        KEY_X      to byteArrayOf(0x00, 0x04),  // a
        KEY_Y      to byteArrayOf(0x00, 0x16),  // s
        KEY_Z      to byteArrayOf(0x00, 0x07),  // d
        KEY_L      to byteArrayOf(0x00, 0x14),  // q
        KEY_R      to byteArrayOf(0x00, 0x1A),  // w
        KEY_SELECT to byteArrayOf(0x00, 0x2C),  // space
        KEY_START  to byteArrayOf(0x00, 0x28),  // enter
        KEY_UP     to byteArrayOf(0x00, 0x52),  // up arrow
        KEY_DOWN   to byteArrayOf(0x00, 0x51),  // down arrow
        KEY_LEFT   to byteArrayOf(0x00, 0x50),  // left arrow
        KEY_RIGHT  to byteArrayOf(0x00, 0x4F),  // right arrow
    )

    // ===== 游戏手柄模式键码 =====
    // 游戏手柄模式下也使用 Keyboard Report ID 1
    // 眼镜端不支持 Game Pad HID Usage，所有按键通过键盘输入设备发送
    // 映射表与 ROKID 手柄模式一致（KEY_HID），开发者按游戏手柄键码监听：
    //   A→KEYCODE_Z, B→KEYCODE_X, C→KEYCODE_C, X→KEYCODE_A, Y→KEYCODE_S,
    //   Z→KEYCODE_D, L→KEYCODE_Q, R→KEYCODE_W, Select→SPACE, Start→ENTER,
    //   ↑↓←→→KEYCODE_DPAD_UP/DOWN/LEFT/RIGHT

    @Volatile
    var connectionState: Int = STATE_DISCONNECTED
        private set

    @Volatile
    private var connectedDeviceInternal: BluetoothDevice? = null
        set(value) {
            synchronized(deviceLock) {
                field = value
            }
        }
        get() {
            synchronized(deviceLock) {
                return field
            }
        }

    val connectedDevice: BluetoothDevice?
        get() = connectedDeviceInternal

    private val deviceLock = Any()
    
    // ── QTI 蓝牙栈兼容（VIVO/OnePlus 等） ──
    /** 是否使用 QTI（Qualcomm）蓝牙栈，需特殊 HID 兼容处理 */
    val isQtiDevice: Boolean = ManufacturerUtils.needsQtiHidWorkaround()
    /** 上次发送 HID 报告的时间戳（用于 QTI 强制间隔控制） */
    @Volatile
    private var lastReportSendTime = 0L

    private val _connectionEvents = Channel<Int>(Channel.CONFLATED)
    val connectionEvents: Flow<Int> = _connectionEvents.receiveAsFlow()

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var hidDevice: BluetoothHidDevice? = null
    private var isRegistered = false
    private var pendingConnectDevice: BluetoothDevice? = null
    
    // ── 通道激活（部分手机首次连接中断通道未就绪，需发空报告唤醒）──
    private var channelReadyTime = 0L
    private val CHANNEL_WARMUP_DELAY_MS: Long
        get() = if (isQtiDevice) AppConfig.HID_CHANNEL_WARMUP_MS else 300L
    
    // ── 斜向交替计数器 ──
    private var diagonalToggle = false
    
    // ── 快速断连检测 + 智能重试 ──
    private var lastConnectTime = 0L
    private var lastDisconnectTime = 0L
    private var quickDisconnectCount = 0
    private val mainHandler = Handler(Looper.getMainLooper())
    private var retryRunnable: Runnable? = null
    /** 连接序列号，每次 connect() 调用递增，用于防止过期的 retryRunnable 执行旧连接 */
    private var connectSequence = 0L

    // ===== 蓝牙扫描 (Discovery) =====
    interface ScanCallback {
        fun onDeviceFound(device: BluetoothDevice)
        fun onScanFinished()
        fun onScanStarted()
    }

    /** HID Profile 检测回调 — 当设备不支持 HID Device Profile 时触发 */
    interface HidProfileCheckCallback {
        /** 注册成功 */
        fun onProfileSupported()
        /**
         * 注册失败，HID Profile 可能未被系统支持
         * @param manufacturer 厂商名（如 "Xiaomi", "Huawei"）
         */
        fun onProfileNotSupported(manufacturer: String)
    }

    private var hidProfileCallback: HidProfileCheckCallback? = null

    /** 设置 HID Profile 检测回调 */
    fun setHidProfileCheckCallback(callback: HidProfileCheckCallback?) {
        hidProfileCallback = callback
    }
    private var scanCallback: ScanCallback? = null
    private var isScanning = false
    @Volatile
    private var receiverRegistered = false

    private val discoveryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    @Suppress("DEPRECATION")
                    val device = if (Build.VERSION.SDK_INT >= 33)
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    else
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    if (device != null) scanCallback?.onDeviceFound(device)
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    isScanning = false
                    scanCallback?.onScanFinished()
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun startScan(callback: ScanCallback) {
        scanCallback = callback
        val adapter = bluetoothAdapter ?: return
        if (isScanning) return
        isScanning = true
        if (receiverRegistered) {
            try { appContext.unregisterReceiver(discoveryReceiver) } catch (_: Exception) {}
            receiverRegistered = false
        }
        appContext.registerReceiver(discoveryReceiver,
            IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_FOUND)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            })
        receiverRegistered = true
        try { if (adapter.isDiscovering) adapter.cancelDiscovery() } catch (_: Exception) {}
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (appContext.checkSelfPermission(android.Manifest.permission.BLUETOOTH_SCAN)
                != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                Log.w(TAG, "Missing BLUETOOTH_SCAN permission, skipping scan")
                scanCallback?.onScanFinished()
                return
            }
        }
        adapter.startDiscovery()
        scanCallback?.onScanStarted()
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        isScanning = false
        try { bluetoothAdapter?.let { if (it.isDiscovering) it.cancelDiscovery() } } catch (_: Exception) {}
        if (receiverRegistered) {
            try { appContext.unregisterReceiver(discoveryReceiver) } catch (_: Exception) {}
            receiverRegistered = false
        }
    }

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
            if (profile == BluetoothProfile.HID_DEVICE) {
                hidDevice = proxy as? BluetoothHidDevice
                Log.i(TAG, "HID Device profile connected")
                registerApp()
            }
        }
        override fun onServiceDisconnected(profile: Int) {
            if (profile == BluetoothProfile.HID_DEVICE) {
                hidDevice = null; isRegistered = false
                updateConnectionState(STATE_DISCONNECTED, null)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private val hidCallback = object : Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            isRegistered = registered
            Log.i(TAG, "App status: registered=$registered, device=$pluggedDevice")
            if (registered) {
                hidProfileCallback?.onProfileSupported()
                // 注册完成，检查是否有用户手动待连接的设备
                if (pluggedDevice == null) {
                    val pending = pendingConnectDevice
                    pendingConnectDevice = null
                    if (pending != null) {
                        Log.i(TAG, "onAppStatusChanged: connecting pending device")
                        connect(pending)
                    }
                }
            } else {
                // 注册失败 — 检测是否因为设备不支持 HID Device Profile
                val manufacturer = ManufacturerUtils.detect()
                val manufacturerName = manufacturer?.name ?: "Unknown"
                if (manufacturer != null && manufacturer != ManufacturerUtils.Manufacturer.OTHER
                    && manufacturer != ManufacturerUtils.Manufacturer.SAMSUNG
                    && manufacturer != ManufacturerUtils.Manufacturer.GOOGLE) {
                    Log.w(TAG, "registerApp failed on $manufacturerName — HID Device Profile may not be supported")
                    hidProfileCallback?.onProfileNotSupported(manufacturerName)
                }
            }
        }
        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            Log.i(TAG, "Connection state: device=${device.address}, state=$state")
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    lastConnectTime = System.currentTimeMillis()
                    quickDisconnectCount = 0
                    retryRunnable?.let { mainHandler.removeCallbacks(it); retryRunnable = null }
                    updateConnectionState(STATE_CONNECTED, device)
                    // 通道激活：部分手机蓝牙栈首次连接中断通道（PSM 0x13）未就绪，
                    // 直接发空报告唤醒通道，比断开重连更温和、更稳定。
                    if (channelReadyTime == 0L) {
                        val seqAtConnect = connectSequence
                        val dev = device
                        val warmupDelay = if (isQtiDevice) AppConfig.HID_CHANNEL_WARMUP_MS else CHANNEL_WARMUP_DELAY_MS
                        mainHandler.postDelayed({
                            if (connectSequence != seqAtConnect) return@postDelayed
                            Log.i(TAG, "Channel priming: sending null reports to wake interrupt channel (qti=$isQtiDevice)")
                            hidDevice?.let { hid ->
                                if (isRegistered) {
                                    // 唤醒所有报告类型
                                    sendKbdReport(dev, KEYBOARD_REPORT_ID, byteArrayOf(0x00, 0x00))
                                    if (isQtiDevice) {
                                        try { Thread.sleep(AppConfig.HID_REPORT_INTERVAL_MS) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
                                    }
                                    sendKbdReport(dev, CONSUMER_REPORT_ID, byteArrayOf(0x00, 0x00))
                                    sendKbdReport(dev, MOUSE_REPORT_ID, byteArrayOf(0x00, 0x00, 0x00, 0x00))
                                    // QTI 额外：再发一组确保通道完全就绪
                                    if (isQtiDevice) {
                                        try { Thread.sleep(AppConfig.HID_REPORT_INTERVAL_MS * 2) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
                                        sendKbdReport(dev, KEYBOARD_REPORT_ID, byteArrayOf(0x00, 0x00))
                                        sendKbdReport(dev, CONSUMER_REPORT_ID, byteArrayOf(0x00, 0x00))
                                    }
                                    channelReadyTime = System.currentTimeMillis()
                                    Log.i(TAG, "Channel priming complete (qti=$isQtiDevice)")
                                }
                            }
                        }, warmupDelay)
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    lastDisconnectTime = System.currentTimeMillis()
                    // 快速断连检测：连接后 2 秒内断开 → 眼镜 HID Host 状态异常
                    if (lastConnectTime > 0 && lastDisconnectTime - lastConnectTime < 2000) {
                        quickDisconnectCount++
                        if (quickDisconnectCount >= AppConfig.BLUETOOTH_MAX_QUICK_DISCONNECT_RETRIES) {
                            Log.w(TAG, "Quick disconnects reached $quickDisconnectCount times, stopping retries, please restart glasses Bluetooth")
                            quickDisconnectCount = 0
                            retryRunnable?.let { mainHandler.removeCallbacks(it); retryRunnable = null }
                            updateConnectionState(STATE_RETRY_FAILED, null)
                        } else {
                            val waitMs = minOf(quickDisconnectCount * 3000L, 15000L)
                            Log.w(TAG, "Quick disconnect #$quickDisconnectCount, waiting ${waitMs}ms before retry")
                            val dev: BluetoothDevice? = device
                            val seqAtDisconnect = connectSequence
                            retryRunnable?.let { mainHandler.removeCallbacks(it) }
                            retryRunnable = Runnable {
                                // 如果在此期间用户手动发起过新连接，跳过此次自动重试
                                if (connectSequence != seqAtDisconnect) {
                                    Log.i(TAG, "Skipping stale retry: user initiated new connection")
                                    return@Runnable
                                }
                                if (dev != null) {
                                    Log.i(TAG, "Smart retry: connecting to $dev")
                                    performConnect(dev)
                                }
                            }
                            mainHandler.postDelayed(retryRunnable!!, waitMs)
                        }
                    }
                    updateConnectionState(STATE_DISCONNECTED, null)
                }
                BluetoothProfile.STATE_CONNECTING   -> updateConnectionState(STATE_CONNECTING, null)
            }
        }
        override fun onVirtualCableUnplug(device: BluetoothDevice?) {
            updateConnectionState(STATE_DISCONNECTED, null)
        }
    }

    @SuppressLint("MissingPermission")
    fun initialize() {
        val hogpNeeded = ManufacturerUtils.needsHogpManualEnablement()
        Log.i(TAG, "BluetoothHidManager: isQtiDevice=$isQtiDevice (VIVO/QTI compat mode: ${if (isQtiDevice) "ON" else "OFF"}), " +
                "HOGP manual enablement needed=$hogpNeeded")
        if (hogpNeeded) {
            Log.w(TAG, "This device (${ManufacturerUtils.detect()}) may need manual Bluetooth HID Host enablement in Developer Options. " +
                    "Guide: ${ManufacturerUtils.getHogpGuideText()}")
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        // 即使 hidDevice 不为 null，也允许重新获取 profile proxy
        // （防止 onServiceDisconnected 回调延迟导致引用失效）
        val manager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothAdapter = manager?.adapter ?: return
        if (hidDevice == null) {
            // Android 12+ 需要 BLUETOOTH_CONNECT 权限
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (appContext.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    Log.w(TAG, "BLUETOOTH_CONNECT permission not granted, skipping getProfileProxy")
                    return
                }
            }
            bluetoothAdapter!!.getProfileProxy(appContext, profileListener, BluetoothProfile.HID_DEVICE)
        }
    }

    fun retryRegisterApp() {
        if (hidDevice == null) {
            // Profile proxy 已断开，重新初始化
            initialize()
            return
        }
        if (!isRegistered) registerApp()
    }

    // hidMode 属性直接在外部赋值 (e.g., hidManager.hidMode = MODE_GAME)

    @SuppressLint("MissingPermission")
    private fun registerApp() {
        val hid = hidDevice ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (appContext.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
                != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) return
        }
        // 先断开已有连接和注销 App，确保眼镜 HID Host 正确清理旧状态
        connectedDeviceInternal?.let { disconnect(it) }
        runCatching { hid.unregisterApp() }

        // QTI 蓝牙栈（VIVO/OnePlus）MTU 仅 64 字节，使用精简描述符
        val descriptor = if (isQtiDevice) buildQtiCompatibleDescriptor() else buildHidDescriptor(1)
        val sdp = BluetoothHidDeviceAppSdpSettings(
            "RokidLab Keyboard",
            "RokidLab",
            "RokidLab Keyboard",
            BluetoothHidDevice.SUBCLASS2_UNCATEGORIZED,
            descriptor,
        )
        val ok = hid.registerApp(sdp, null, null, Runnable::run, hidCallback)
        Log.i(TAG, "registerApp result: $ok (descriptor size=${descriptor.size} bytes, isQti=$isQtiDevice)")
        // 不立即 connect：等待 onAppStatusChanged 回调确认注册完成后，
        // 在回调内部检查 pendingConnectDevice 并执行连接，避免竞争条件
    }

    // ========================================================================
    //  HID 连接
    // ========================================================================

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        // 记录连接发起时间，用于快速断连检测（即使没到 CONNECTED 状态）
        lastConnectTime = System.currentTimeMillis()
        // 用户手动重连时，重置快速断连计数，递增序列号
        quickDisconnectCount = 0
        connectSequence++
        channelReadyTime = 0
        performConnect(device)
    }

    /** 内部重试连接（不重置快速断连计数） */
    @SuppressLint("MissingPermission")
    private fun performConnect(device: BluetoothDevice) {
        if (hidDevice == null) {
            Log.w(TAG, "HID not ready, re-initializing...")
            pendingConnectDevice = device
            initialize()
            return
        }
        if (!isRegistered) {
            Log.w(TAG, "HID not yet registered, registering...")
            pendingConnectDevice = device
            registerApp()
            return
        }
        val ok = hidDevice!!.connect(device)
        Log.i(TAG, "connect result: $ok")
    }

    @SuppressLint("MissingPermission")
    fun disconnect(device: BluetoothDevice) {
        hidDevice?.let { if (isRegistered) runCatching { it.disconnect(device) } }
    }

    @SuppressLint("MissingPermission")
    fun unregister() {
        hidDevice?.let { if (isRegistered) { runCatching { it.unregisterApp() }; isRegistered = false } }
    }

    @SuppressLint("MissingPermission")
    fun destroy() {
        stopScan()
        // 先断开已连接的设备，再注销 HID App，确保眼镜 HID Host 正确清理状态
        connectedDeviceInternal?.let { disconnect(it) }
        unregister()
        bluetoothAdapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, hidDevice)
        hidDevice = null; isRegistered = false
        updateConnectionState(STATE_DISCONNECTED, null)
    }

    /** 发送 HID 报告（自动兼容 QTI/AOSP 蓝牙栈行为）
     *
     *  方法1（标准 AOSP）：reportId > 0，系统自动在数据前加 Report ID — 适用大多数手机
     *  方法2（QTI 兼容）：reportId = 0，数据自身已包含 Report ID — Vivo 等 QTI 设备
     *  方法3（setReport 回退）：通过控制通道发送，QTI 栈中断通道失败时备用
     *
     *  注意：
     *  - VIVO/iQOO 等使用 Qualcomm (QTI) 蓝牙栈的设备，标准 sendReport 可能
     *    返回 true 但数据未实际发送（中断通道问题）
     *  - 对 QTI 设备，还会尝试 setReport() 作为最终回退
     *  - QTI 设备需要插入报告间最小间隔（AppConfig.HID_REPORT_INTERVAL_MS）
     */
    @SuppressLint("MissingPermission")
    private fun sendKbdReport(dev: BluetoothDevice, reportId: Int, data: ByteArray): Boolean {
        val hid = hidDevice ?: return false

        // QTI 兼容：强制报告间间隔
        if (isQtiDevice && lastReportSendTime > 0) {
            val elapsed = System.currentTimeMillis() - lastReportSendTime
            if (elapsed < AppConfig.HID_REPORT_INTERVAL_MS) {
                try {
                    Thread.sleep(AppConfig.HID_REPORT_INTERVAL_MS - elapsed)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }

        if (reportId <= 0) {
            val ok = hid.sendReport(dev, 0, data)
            if (ok) lastReportSendTime = System.currentTimeMillis()
            return ok
        }

        // 方法1（标准）：reportId > 0 — 适用于 AOSP 蓝牙栈
        val standardOk = hid.sendReport(dev, reportId, data)

        // 方法2（QTI 兼容）：reportId = 0，数据前加 Report ID 字节
        val full = byteArrayOf(reportId.toByte()) + data
        val manualOk = hid.sendReport(dev, 0, full)

        if (standardOk || manualOk) {
            lastReportSendTime = System.currentTimeMillis()
            return true
        }

        // 方法3（QTI 回退）：使用 setReport() 控制通道发送
        // 部分 QTI 设备中断通道完全不可用，但控制通道（setReport）可以
        if (isQtiDevice) {
            try {
                // 使用反射调用 setReport（是隐藏 API）
                val setReportMethod = BluetoothHidDevice::class.java.getMethod(
                    "setReport", BluetoothDevice::class.java, Int::class.java, ByteArray::class.java
                )
                val outputReportId = 3  // OUTPUT_REPORT type
                val setOk = setReportMethod.invoke(hid, dev, outputReportId, full) as? Boolean ?: false
                if (setOk) {
                    Log.i(TAG, "sendKbdReport: setReport fallback succeeded for reportId=$reportId")
                    lastReportSendTime = System.currentTimeMillis()
                    return true
                }
            } catch (e: Exception) {
                Log.w(TAG, "sendKbdReport: setReport fallback not available: ${e.message}")
            }
        }

        Log.w(TAG, "sendKbdReport: all methods failed for reportId=$reportId (isQti=$isQtiDevice)")
        return false
    }

    // ========================================================================
    //  发送按键（支持多键 + 斜向）
    // ========================================================================

    /**
     * 发送单个按键
     * 方向键/Select/Start → Consumer Control; A/B/X/Y/Z/L/R → 键盘
     */
    @SuppressLint("MissingPermission")
    private fun sendSingleKey(device: BluetoothDevice?, key: Int) {
        val dev = device ?: connectedDeviceInternal ?: return
        val hid = hidDevice ?: return
        if (!isRegistered) return

        // 导航键 → Consumer Control (Report ID 2)
        val consumerReport = KEY_CONSUMER[key]
        if (consumerReport != null) {
            val ok = sendKbdReport(dev, CONSUMER_REPORT_ID, consumerReport)
            if (!ok) Log.w(TAG, "sendSingleKey: consumer report failed")
            return
        }

        // 功能键 → Keyboard (Report ID 1) — 2 字节 [modifier, keycode]
        val kbdReport = KEY_HID[key]
        if (kbdReport != null) {
            val ok = sendKbdReport(dev, KEYBOARD_REPORT_ID, kbdReport)
            if (!ok) Log.w(TAG, "sendSingleKey: keyboard report failed")
        }
    }

    /**
      * 发送按键集合（支持多键 + 斜向）
      * - 方向键 → Consumer Control（斜向时交替发送两个方向）
      * - 功能键 → Keyboard（最多选第一个发送，其他丢弃）
      *
      * QTI 兼容：方向键和功能键的报告间插入最小间隔（AppConfig.HID_REPORT_INTERVAL_MS）
      * 避免 QTI 蓝牙栈连续发送丢失第二个报告
      */
     @SuppressLint("MissingPermission")
     fun sendButtons(device: BluetoothDevice?, keys: Set<Int>) {
         val dev = device ?: connectedDeviceInternal ?: return
         val hid = hidDevice ?: return
         if (!isRegistered || keys.isEmpty()) return
 
         // 拆分为方向键和功能键
         val dirKeys = keys.filter { it in KEY_CONSUMER }
         val actKeys = keys.filter { it in KEY_HID && it !in KEY_CONSUMER }
 
         // 发送方向键（斜向时交替，Consumer Control 一次只能一个）
         if (dirKeys.isNotEmpty()) {
             val idx = if (dirKeys.size > 1 && diagonalToggle) 1 else 0
             diagonalToggle = !diagonalToggle
             val dirReport = KEY_CONSUMER[dirKeys[idx]]!!
             val ok = sendKbdReport(dev, CONSUMER_REPORT_ID, dirReport)
             if (!ok) Log.w(TAG, "sendButtons: consumer report failed")
         } else {
             sendKbdReport(dev, CONSUMER_REPORT_ID, byteArrayOf(0x00, 0x00))
         }
 
         // QTI 兼容：方向键与功能键报告间插入延迟，防止第二个报告被 QTI 栈丢弃
         if (isQtiDevice && actKeys.isNotEmpty() && (dirKeys.isNotEmpty() || diagonalToggle)) {
             try { Thread.sleep(AppConfig.HID_REPORT_INTERVAL_MS) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
         }
 
         // 发送功能键（取第一个，键盘一次只能一个）
         if (actKeys.isNotEmpty()) {
             val kbdData = KEY_HID[actKeys.first()]!!
             val ok = sendKbdReport(dev, KEYBOARD_REPORT_ID, kbdData)
             if (!ok) Log.w(TAG, "sendButtons: keyboard report failed")
         } else {
             sendKbdReport(dev, KEYBOARD_REPORT_ID, byteArrayOf(0x00, 0x00))
         }
     }

    /** 发送所有按键释放（Consumer + Keyboard） */
    @SuppressLint("MissingPermission")
    fun sendRelease(device: BluetoothDevice?) {
        val dev = device ?: connectedDeviceInternal ?: return
        if (!isRegistered) return
        val ok1 = sendKbdReport(dev, CONSUMER_REPORT_ID, byteArrayOf(0x00, 0x00))
        // QTI 兼容：两个释放报告间插入延迟
        if (isQtiDevice) {
            try { Thread.sleep(AppConfig.HID_REPORT_INTERVAL_MS) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        }
        val ok2 = sendKbdReport(dev, KEYBOARD_REPORT_ID, byteArrayOf(0x00, 0x00))
        if (!ok1 || !ok2) Log.w(TAG, "sendRelease failed (consumer=$ok1, kbd=$ok2)")
    }

    // ========================================================================
    //  HID 报告大小测试
    // ========================================================================

    /** 测试眼镜 HID 驱动支持的最大键盘报告大小（键码字节数）
     *  遍历 1~6 键码，每次重新注册并输出日志。
     *  @return 最大支持的键码数量
     */
    @SuppressLint("MissingPermission")
    fun testHidMaxKeycodeCount(): Int {
        var maxSupported = 1
        for (kc in 1..6) {
            Log.i(TAG, "========== 测试 ${kc} 键码 (报告 ${1 + kc} 字节) ==========")
            try {
                // 注销当前 App
                hidDevice?.let {
                    runCatching { it.unregisterApp() }
                }
                isRegistered = false
                Thread.sleep(200)

                // 用新的描述符重新注册
                val sdp = BluetoothHidDeviceAppSdpSettings(
                    "RokidLab Keyboard",
                    "RokidLab",
                    "RokidLab Keyboard",
                    BluetoothHidDevice.SUBCLASS2_UNCATEGORIZED,
                    buildHidDescriptor(kc),
                )
                val ok = hidDevice?.registerApp(sdp, null, null, Runnable::run, hidCallback) ?: false
                Log.i(TAG, "registerApp(kc=$kc) result: $ok")

                Thread.sleep(500) // 等待眼镜 HID Host 响应

                if (ok && isRegistered) {
                    // 尝试连接已配对的设备
                    val savedDev = connectedDeviceInternal
                    if (savedDev != null) {
                        hidDevice?.disconnect(savedDev)
                        Thread.sleep(200)
                        hidDevice?.connect(savedDev)
                        Thread.sleep(1000)
                    }

                    // 检查眼镜端是否接受（查看 EventHub 日志）
                    Log.i(TAG, "✅ ${kc} 键码注册成功，请查看眼镜 logcat 确认是否被接受")
                    maxSupported = kc
                } else {
                    Log.w(TAG, "❌ ${kc} 键码注册失败")
                    break
                }
            } catch (e: Exception) {
                Log.e(TAG, "测试 ${kc} 键码异常", e)
                break
            }
        }
        // 恢复为 1 键码
        Log.i(TAG, "========== 恢复 1 键码 ==========")
        try {
            hidDevice?.let { runCatching { it.unregisterApp() } }
            isRegistered = false
            Thread.sleep(200)
            val sdp = BluetoothHidDeviceAppSdpSettings(
                "RokidLab Keyboard",
                "RokidLab",
                "RokidLab Keyboard",
                BluetoothHidDevice.SUBCLASS2_UNCATEGORIZED,
                buildHidDescriptor(1),
            )
            hidDevice?.registerApp(sdp, null, null, Runnable::run, hidCallback)
        } catch (_: Exception) {}
        Log.i(TAG, "========== 测试结束，最大支持 ${maxSupported} 键码 ==========")
        return maxSupported
    }

    // ========================================================================
    //  Game Pad 模式
    // ========================================================================

    /** 当前模式: KEYBOARD 或 GAMEPAD */
    private var gamepadMode = false

    /** Game Pad 按钮到位图 bit 映射 index=键常量值 0~13 */
    private val GAMEPAD_BIT = intArrayOf(
        0,   // KEY_A      → bit 0
        1,   // KEY_B      → bit 1
        2,   // KEY_C      → bit 2
        3,   // KEY_X      → bit 3
        4,   // KEY_Y      → bit 4
        5,   // KEY_Z      → bit 5
        6,   // KEY_L      → bit 6
        7,   // KEY_R      → bit 7
        8,   // KEY_SELECT → bit 8
        9,   // KEY_START  → bit 9
        10,  // KEY_UP     → bit 10
        11,  // KEY_DOWN   → bit 11
        12,  // KEY_LEFT   → bit 12
        13,  // KEY_RIGHT  → bit 13
    )

    /** Hat Switch 值映射：从方向键集合推导 */
    private fun hatFromKeys(keys: Set<Int>): Int {
        val up = keys.contains(KEY_UP)
        val dn = keys.contains(KEY_DOWN)
        val lt = keys.contains(KEY_LEFT)
        val rt = keys.contains(KEY_RIGHT)
        return when {
            up && rt -> 1  // ↗
            rt && dn -> 3  // ↘
            dn && lt -> 5  // ↙
            lt && up -> 7  // ↖
            up       -> 0  // ↑
            rt       -> 2  // →
            dn       -> 4  // ↓
            lt       -> 6  // ←
            else     -> 8  // 释放
        }
    }

    /**
     * 注册 Game Pad HID 描述符，覆盖旧的键盘描述符。
     */
    @SuppressLint("MissingPermission")
    fun switchToGamepadMode() {
        val hid = hidDevice ?: return
        try {
            // 在注销前保存当前连接的设备
            val savedDev = connectedDeviceInternal
            runCatching { hid.unregisterApp() }
            isRegistered = false
            gamepadMode = false
            Thread.sleep(200)
            // QTI 设备用精简游戏手柄描述符
            val gpDescriptor = if (isQtiDevice) buildQtiGamepadDescriptor() else buildGamepadDescriptor()
            val sdp = BluetoothHidDeviceAppSdpSettings(
                "RokidLab Gamepad",
                "RokidLab",
                "RokidLab Gamepad",
                BluetoothHidDevice.SUBCLASS2_UNCATEGORIZED,
                gpDescriptor,
            )
            val ok = hid.registerApp(sdp, null, null, Runnable::run, hidCallback)
            Log.i(TAG, "switchToGamepadMode result: $ok (descriptor size=${gpDescriptor.size} bytes, isQti=$isQtiDevice)")
            if (ok) {
                gamepadMode = true
                Thread.sleep(800) // 等待注册稳定
                if (savedDev != null) {
                    hid.disconnect(savedDev)
                    Thread.sleep(200)
                    hid.connect(savedDev)
                    Log.i(TAG, "switchToGamepadMode: reconnecting to $savedDev")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "switchToGamepadMode error", e)
        }
    }

    /**
     * 恢复为 Keyboard HID 描述符。
     */
    @SuppressLint("MissingPermission")
    fun switchToKeyboardMode() {
        val hid = hidDevice ?: return
        try {
            val savedDev = connectedDeviceInternal
            runCatching { hid.unregisterApp() }
            isRegistered = false
            gamepadMode = false
            Thread.sleep(200)
            val descriptor = if (isQtiDevice) buildQtiCompatibleDescriptor() else buildHidDescriptor(1)
            val sdp = BluetoothHidDeviceAppSdpSettings(
                "RokidLab Keyboard",
                "RokidLab",
                "RokidLab Keyboard",
                BluetoothHidDevice.SUBCLASS2_UNCATEGORIZED,
                descriptor,
            )
            val ok = hid.registerApp(sdp, null, null, Runnable::run, hidCallback)
            Log.i(TAG, "switchToKeyboardMode result: $ok (descriptor size=${descriptor.size} bytes, isQti=$isQtiDevice)")
            if (ok) {
                Thread.sleep(500)
                if (savedDev != null) {
                    hid.disconnect(savedDev)
                    Thread.sleep(200)
                    hid.connect(savedDev)
                    Log.i(TAG, "switchToKeyboardMode: reconnecting to $savedDev")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "switchToKeyboardMode error", e)
        }
    }

    /**
     * 通过 Game Pad 报告发送按键。
     * 4 字节报告: [buttons_lsb, buttons_msb, hat, reserved]
     */
    @SuppressLint("MissingPermission")
    fun sendGamepadReport(device: BluetoothDevice?, keys: Set<Int>) {
        val dev = device ?: connectedDeviceInternal ?: return
        val hid = hidDevice ?: return
        if (!isRegistered || !gamepadMode) return

        var bitmap = 0
        for (k in keys) {
            if (k in 0..13) bitmap = bitmap or (1 shl GAMEPAD_BIT[k])
        }
        val hat = hatFromKeys(keys)

        val report = byteArrayOf(
            (bitmap and 0xFF).toByte(),
            ((bitmap shr 8) and 0xFF).toByte(),
            (hat and 0x0F).toByte(),   // hat in low nibble
            0x00,
        )
        sendKbdReport(dev, GAMEPAD_REPORT_ID, report)
    }

    /** Game Pad 版全释放 */
    @SuppressLint("MissingPermission")
    fun sendGamepadRelease(device: BluetoothDevice?) {
        val dev = device ?: connectedDeviceInternal ?: return
        val hid = hidDevice ?: return
        if (!isRegistered || !gamepadMode) return
        sendKbdReport(dev, GAMEPAD_REPORT_ID, byteArrayOf(0x00, 0x00, 0x08, 0x00))
    }

    /**
     * 测试 Game Pad 模式：切换为 Game Pad 描述符，引导用户重新配对，
     * 然后发送测试按键。
     */
    @SuppressLint("MissingPermission")
    fun testGamepadMode(): Boolean {
        Log.i(TAG, "========== Game Pad 测试开始 ==========")

        // 1. 先切换描述符
        switchToGamepadMode()
        Thread.sleep(500)

        if (!isRegistered || !gamepadMode) {
            Log.w(TAG, "Game Pad 注册失败，测试中止")
            return false
        }

        // 2. 断开当前连接并移除绑定，迫使眼镜重新 SDP
        val dev = connectedDeviceInternal
        if (dev == null) {
            Log.w(TAG, "没有已连接的设备")
            switchToKeyboardMode()
            return false
        }

        Log.i(TAG, "Game Pad 注册成功！请去除配对后重新配对")
        Log.i(TAG, "操作步骤：")
        Log.i(TAG, "  1. 前往眼镜设置 → 蓝牙 → 移除 'DLOVER的Xiaomi 15'")
        Log.i(TAG, "  2. 前往手机蓝牙设置 → 移除眼镜")
        Log.i(TAG, "  3. 返回此页面，点击右上角扫一扫重新配对")
        Log.i(TAG, "  重新配对后，Game Pad 将被正确识别！")

        // 保持 Game Pad 模式，等用户重新配对
        return true
    }

    // ========================================================================
    //  360° 摇杆方向映射
    // ========================================================================

    /**
     * 将角度转换为方向键 Consumer Control 报告，发送至眼镜。
     * 斜向时交替发送两个方向（在循环中调用即可实现双键交替效果）。
     * @param angle 角度，0°=右, 90°=上, 180°=左, 270°=下
     * @param magnitude 力度 0.0~1.0，低于阈值时释放所有键
     */
    @SuppressLint("MissingPermission")
    fun sendJoystickDirection(device: BluetoothDevice?, angle: Float, magnitude: Float) {
        if (magnitude < 0.15f) {
            sendRelease(device)
            return
        }
        val keys = joystickAngleToKeys(angle)
        sendButtons(device, keys)
    }

    /** 将角度映射到按键集合（支持对角线） */
    fun joystickAngleToKeys(angle: Float): Set<Int> {
        val a = ((angle % 360f) + 360f) % 360f
        return when {
            a < 22.5f || a >= 337.5f -> setOf(KEY_RIGHT)
            a < 67.5f  -> setOf(KEY_RIGHT, KEY_UP)    // ↗
            a < 112.5f -> setOf(KEY_UP)
            a < 157.5f -> setOf(KEY_UP, KEY_LEFT)     // ↖
            a < 202.5f -> setOf(KEY_LEFT)
            a < 247.5f -> setOf(KEY_LEFT, KEY_DOWN)   // ↙
            a < 292.5f -> setOf(KEY_DOWN)
            a < 337.5f -> setOf(KEY_DOWN, KEY_RIGHT)  // ↘
            else -> setOf(KEY_RIGHT)
        }
    }

    /** 发送鼠标移动
     *  report = [buttons, dx, dy, wheel]
     *  Report ID 3 — Mouse */
    @SuppressLint("MissingPermission")
    fun sendMouseMove(device: BluetoothDevice?, dx: Int, dy: Int) {
        val dev = device ?: connectedDeviceInternal ?: return
        val hid = hidDevice ?: return
        if (!isRegistered) return
        val clampedDx = dx.coerceIn(-127, 127).toByte()
        val clampedDy = dy.coerceIn(-127, 127).toByte()
        val report = byteArrayOf(0x00, clampedDx, clampedDy, 0x00)  // buttons=0, wheel=0
        val ok = sendKbdReport(dev, MOUSE_REPORT_ID, report)
        if (!ok) Log.w(TAG, "sendMouseMove: sendReport failed")
    }

    /** 发送鼠标点击
     *  @param button 1=左键, 2=右键, 3=中键 */
    @SuppressLint("MissingPermission")
    fun sendMouseClick(device: BluetoothDevice?, button: Int = 1) {
        val dev = device ?: connectedDeviceInternal ?: return
        val hid = hidDevice ?: return
        if (!isRegistered) return
        // 按下
        val ok1 = sendKbdReport(dev, MOUSE_REPORT_ID, byteArrayOf(button.toByte(), 0, 0, 0))
        // 释放
        val ok2 = sendKbdReport(dev, MOUSE_REPORT_ID, byteArrayOf(0, 0, 0, 0))
        if (!ok1 || !ok2) Log.w(TAG, "sendMouseClick: sendReport failed (down=$ok1, up=$ok2)")
    }

    /** 发送鼠标按钮状态（按下或释放，不自动释放）
     *  @param button 1=左键, 2=右键, 3=中键
     *  @param pressed true=按下, false=释放 */
    @SuppressLint("MissingPermission")
    fun sendMouseButton(device: BluetoothDevice?, button: Int = 1, pressed: Boolean) {
        val dev = device ?: connectedDeviceInternal ?: return
        val hid = hidDevice ?: return
        if (!isRegistered) return
        val data = if (pressed) byteArrayOf(button.toByte(), 0, 0, 0) else byteArrayOf(0, 0, 0, 0)
        val ok = sendKbdReport(dev, MOUSE_REPORT_ID, data)
        if (!ok) Log.w(TAG, "sendMouseButton: sendReport failed (pressed=$pressed)")
    }

    fun getPairedDevices(): List<BluetoothDevice> =
        bluetoothAdapter?.bondedDevices?.toList() ?: emptyList()

    private fun updateConnectionState(state: Int, device: BluetoothDevice?) {
        connectionState = state
        connectedDeviceInternal = device
        _connectionEvents.trySend(state)
    }
}
