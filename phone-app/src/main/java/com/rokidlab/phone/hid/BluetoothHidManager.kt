package com.rokidlab.phone.hid

import com.rokidlab.phone.util.AppConfig
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
        const val STATE_RETRY_FAILED = 3  // Smart retry exceeded max attempts, manual Bluetooth reset required

        const val CONN_HID = 0  // 蓝牙 HID 模式 (BluetoothHidDevice API)

        private const val KEYBOARD_REPORT_ID = 1
        private const val CONSUMER_REPORT_ID = 2
        private const val MOUSE_REPORT_ID   = 3
        // GAMEPAD_REPORT_ID 已移除 — 眼镜内核不支持 Game Pad HID Usage
        // 游戏手柄模式复用 Keyboard Report ID 1
    }

    // ===== HID 描述符（多 Report ID） =====
    // 参考 BTREMOTE — Consumer Control (导航) + Keyboard (按键) + Mouse
    private val HID_DESCRIPTOR = byteArrayOf(
        // ── Consumer Control (Report ID 2) ──
        0x05.toByte(), 0x0C.toByte(),                    // Usage Page (Consumer Devices)
        0x09.toByte(), 0x01.toByte(),                    // Usage (Consumer Control)
        0xA1.toByte(), 0x01.toByte(),                    // Collection (Application)
        0x85.toByte(), CONSUMER_REPORT_ID.toByte(),      //   Report ID (2)
        0x19.toByte(), 0x00.toByte(),                    //   Usage Minimum (Unassigned)
        0x2A.toByte(), 0xFF.toByte(), 0x03.toByte(),     //   Usage Maximum (1023)
        0x75.toByte(), 0x10.toByte(),                    //   Report Size (16)
        0x95.toByte(), 0x01.toByte(),                    //   Report Count (1)
        0x15.toByte(), 0x00.toByte(),                    //   Logical Minimum (0)
        0x26.toByte(), 0xFF.toByte(), 0x03.toByte(),     //   Logical Maximum (1023)
        0x81.toByte(), 0x00.toByte(),                    //   Input (Data,Array,Abs)
        0xC0.toByte(),                                   // End Collection

        // ── Keyboard (Report ID 1) ──
        0x05.toByte(), 0x01.toByte(),                    // Usage Page (Generic Desktop)
        0x09.toByte(), 0x06.toByte(),                    // Usage (Keyboard)
        0xA1.toByte(), 0x01.toByte(),                    // Collection (Application)
        0x85.toByte(), KEYBOARD_REPORT_ID.toByte(),      //   Report ID (1)
        0x05.toByte(), 0x07.toByte(),                    //   Usage Page (Keyboard/Keypad)
        0x19.toByte(), 0xE0.toByte(),                    //   Usage Minimum (KB Left Ctrl)
        0x29.toByte(), 0xE7.toByte(),                    //   Usage Maximum (KB Right GUI)
        0x15.toByte(), 0x00.toByte(),                    //   Logical Minimum (0)
        0x25.toByte(), 0x01.toByte(),                    //   Logical Maximum (1)
        0x75.toByte(), 0x01.toByte(),                    //   Report Size (1)
        0x95.toByte(), 0x08.toByte(),                    //   Report Count (8)
        0x81.toByte(), 0x02.toByte(),                    //   Input (Data,Var,Abs) — modifier byte
        0x75.toByte(), 0x08.toByte(),                    //   Report Size (8)
        0x95.toByte(), 0x01.toByte(),                    //   Report Count (1)
        0x15.toByte(), 0x00.toByte(),                    //   Logical Minimum (0)
        0x26.toByte(), 0xFF.toByte(), 0x00.toByte(),     //   Logical Maximum (255)
        0x05.toByte(), 0x07.toByte(),                    //   Usage Page (Keyboard/Keypad)
        0x19.toByte(), 0x00.toByte(),                    //   Usage Minimum (0)
        0x29.toByte(), 0xFF.toByte(),                    //   Usage Maximum (255)
        0x81.toByte(), 0x00.toByte(),                    //   Input (Data,Array,Abs) — keycode byte
        0xC0.toByte(),                                   // End Collection

        // ── Mouse (Report ID 3) — 保留 ──
        0x05.toByte(), 0x01.toByte(),                    // Usage Page (Generic Desktop)
        0x09.toByte(), 0x02.toByte(),                    // Usage (Mouse)
        0xA1.toByte(), 0x01.toByte(),                    // Collection (Application)
        0x85.toByte(), MOUSE_REPORT_ID.toByte(),         //   Report ID (3)
        0x09.toByte(), 0x01.toByte(),                    //   Usage (Pointer)
        0xA1.toByte(), 0x00.toByte(),                    //   Collection (Physical)
        0x05.toByte(), 0x09.toByte(),                    //     Usage Page (Button)
        0x19.toByte(), 0x01.toByte(),                    //     Usage Minimum (1)
        0x29.toByte(), 0x03.toByte(),                    //     Usage Maximum (3)
        0x15.toByte(), 0x00.toByte(),                    //     Logical Minimum (0)
        0x25.toByte(), 0x01.toByte(),                    //     Logical Maximum (1)
        0x75.toByte(), 0x01.toByte(),                    //     Report Size (1)
        0x95.toByte(), 0x03.toByte(),                    //     Report Count (3)
        0x81.toByte(), 0x02.toByte(),                    //     Input (Data,Var,Abs)
        0x75.toByte(), 0x05.toByte(),                    //     Report Size (5)
        0x95.toByte(), 0x01.toByte(),                    //     Report Count (1)
        0x81.toByte(), 0x01.toByte(),                    //     Input (Const)
        0x05.toByte(), 0x01.toByte(),                    //     Usage Page (Generic Desktop)
        0x09.toByte(), 0x30.toByte(),                    //     Usage (X)
        0x09.toByte(), 0x31.toByte(),                    //     Usage (Y)
        0x09.toByte(), 0x38.toByte(),                    //     Usage (Wheel)
        0x15.toByte(), 0x81.toByte(),                    //     Logical Minimum (-127)
        0x25.toByte(), 0x7F.toByte(),                    //     Logical Maximum (127)
        0x75.toByte(), 0x08.toByte(),                    //     Report Size (8)
        0x95.toByte(), 0x03.toByte(),                    //     Report Count (3)
        0x81.toByte(), 0x06.toByte(),                    //     Input (Data,Var,Rel)
        0xC0.toByte(),                                   //   End Collection
        0xC0.toByte(),                                   // End Collection
    )

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

    private val _connectionEvents = Channel<Int>(Channel.CONFLATED)
    val connectionEvents: Flow<Int> = _connectionEvents.receiveAsFlow()

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var hidDevice: BluetoothHidDevice? = null
    private var isRegistered = false
    private var pendingConnectDevice: BluetoothDevice? = null
    
    // ── 快速断连检测 + 智能重试 ──
    private var lastConnectTime = 0L
    private var lastDisconnectTime = 0L
    private var quickDisconnectCount = 0
    private val mainHandler = Handler(Looper.getMainLooper())
    private var retryRunnable: Runnable? = null

    // ===== 蓝牙扫描 (Discovery) =====
    interface ScanCallback {
        fun onDeviceFound(device: BluetoothDevice)
        fun onScanFinished()
        fun onScanStarted()
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
            // 注册完成，检查是否有用户手动待连接的设备
            if (registered && pluggedDevice == null) {
                val pending = pendingConnectDevice
                pendingConnectDevice = null
                if (pending != null) {
                    Log.i(TAG, "onAppStatusChanged: connecting pending device")
                    connect(pending)
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
                            retryRunnable?.let { mainHandler.removeCallbacks(it) }
                            retryRunnable = Runnable {
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
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        if (hidDevice != null) return
        val manager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothAdapter = manager?.adapter ?: return
        bluetoothAdapter!!.getProfileProxy(appContext, profileListener, BluetoothProfile.HID_DEVICE)
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
        val sdp = BluetoothHidDeviceAppSdpSettings(
            "RokidLab Keyboard",
            "RokidLab",
            "RokidLab Keyboard",
            BluetoothHidDevice.SUBCLASS2_UNCATEGORIZED,
            HID_DESCRIPTOR,
        )
        val ok = hid.registerApp(sdp, null, null, Runnable::run, hidCallback)
        Log.i(TAG, "registerApp result: $ok")
        // 注册成功后立即连接 pending 设备，不依赖 onAppStatusChanged 回调
        // （因为回调可能带回旧缓存设备导致 pending 连接被跳过）
        if (ok) {
            val pending = pendingConnectDevice
            if (pending != null) {
                pendingConnectDevice = null
                Log.i(TAG, "registerApp success, connecting pending device immediately")
                hidDevice?.connect(pending)
            }
        }
    }

    // ========================================================================
    //  HID 连接
    // ========================================================================

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        // 记录连接发起时间，用于快速断连检测（即使没到 CONNECTED 状态）
        lastConnectTime = System.currentTimeMillis()
        // 用户手动重连时，重置快速断连计数
        quickDisconnectCount = 0
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

    // ========================================================================
    //  发送按键
    // ========================================================================

    /** 发送按键按下
     *  方向键/SELECT/START → Consumer Control; A/B/X/Y/Z/L/R → 键盘 */
    @SuppressLint("MissingPermission")
    fun sendButtons(device: BluetoothDevice?, keys: Set<Int>) {
        val dev = device ?: connectedDeviceInternal ?: return
        val hid = hidDevice ?: return
        if (!isRegistered) return

        // 导航键 → Consumer Control (Report ID 2)
        val key = keys.firstOrNull() ?: return
        val consumerReport = KEY_CONSUMER[key]
        if (consumerReport != null) {
            val ok = hid.sendReport(dev, CONSUMER_REPORT_ID, consumerReport)
            Log.i(TAG, "CONS key=$key hex=${consumerReport.joinToString(""){"%02x".format(it)}} ok=$ok")
            return
        }

        // 功能键 → Keyboard (Report ID 1)
        val kbdReport = KEY_HID[key]
        if (kbdReport != null) {
            val ok = hid.sendReport(dev, KEYBOARD_REPORT_ID, kbdReport)
            Log.i(TAG, "KBD key=$key hex=${kbdReport.joinToString(""){"%02x".format(it)}} ok=$ok")
        }
    }

    /** 发送按键释放 */
    @SuppressLint("MissingPermission")
    fun sendRelease(device: BluetoothDevice?) {
        val dev = device ?: connectedDeviceInternal ?: return
        val hid = hidDevice ?: return
        if (!isRegistered) return
        val release = byteArrayOf(0x00, 0x00)
        hid.sendReport(dev, CONSUMER_REPORT_ID, release)
        hid.sendReport(dev, KEYBOARD_REPORT_ID, release)
        Log.i(TAG, "release all ok=true")
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
        hid.sendReport(dev, MOUSE_REPORT_ID, report)
    }

    /** 发送鼠标点击
     *  @param button 1=左键, 2=右键, 3=中键 */
    @SuppressLint("MissingPermission")
    fun sendMouseClick(device: BluetoothDevice?, button: Int = 1) {
        val dev = device ?: connectedDeviceInternal ?: return
        val hid = hidDevice ?: return
        if (!isRegistered) return
        // 按下
        hid.sendReport(dev, MOUSE_REPORT_ID, byteArrayOf(button.toByte(), 0, 0, 0))
        // 释放
        hid.sendReport(dev, MOUSE_REPORT_ID, byteArrayOf(0, 0, 0, 0))
    }

    fun getPairedDevices(): List<BluetoothDevice> =
        bluetoothAdapter?.bondedDevices?.toList() ?: emptyList()

    private fun updateConnectionState(state: Int, device: BluetoothDevice?) {
        connectionState = state
        connectedDeviceInternal = device
        _connectionEvents.trySend(state)
    }
}
