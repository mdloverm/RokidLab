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
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import java.io.OutputStream
import java.util.UUID
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

        const val MODE_UI   = 0  // 菜单导航模式 — Consumer Control 方向键 + 键盘按钮
        const val MODE_GAME = 1  // 游戏模式 — 全部用键盘

        const val CONN_HID     = 0  // 蓝牙 HID 模式 (BluetoothHidDevice API)
        const val CONN_RFCOMM  = 1  // 蓝牙 RFCOMM 模式 (SPP Socket，配合 RetroArch)

        private const val KEYBOARD_REPORT_ID = 1
        private const val CONSUMER_REPORT_ID = 2
        private const val MOUSE_REPORT_ID   = 3

        // 蓝牙 SPP UUID (与 RetroArch 服务端一致)
        private val RFCOMM_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }

    // ===== Android Keycode 映射 (供 RFCOMM 模式使用) =====
    private val KEY_TO_ANDROID = mapOf(
        KEY_A      to 96,   // AKEYCODE_BUTTON_A
        KEY_B      to 97,   // AKEYCODE_BUTTON_B
        KEY_C      to 98,   // AKEYCODE_BUTTON_C
        KEY_X      to 99,   // AKEYCODE_BUTTON_X
        KEY_Y      to 100,  // AKEYCODE_BUTTON_Y
        KEY_Z      to 54,   // AKEYCODE_Z (键盘按键)
        KEY_L      to 102,  // AKEYCODE_BUTTON_L1
        KEY_R      to 103,  // AKEYCODE_BUTTON_R1
        KEY_SELECT to 109,  // AKEYCODE_BUTTON_SELECT
        KEY_START  to 108,  // AKEYCODE_BUTTON_START
        KEY_UP     to 19,   // AKEYCODE_DPAD_UP
        KEY_DOWN   to 20,   // AKEYCODE_DPAD_DOWN
        KEY_LEFT   to 21,   // AKEYCODE_DPAD_LEFT
        KEY_RIGHT  to 22,   // AKEYCODE_DPAD_RIGHT
    )

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

    @Volatile
    var hidMode: Int = MODE_UI
        private set

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

    // ===== RFCOMM 相关字段 (配合 RetroArch 蓝牙 SPP 服务端) =====
    @Volatile
    var connectionType: Int = CONN_HID
        private set
    private var rfcommSocket: BluetoothSocket? = null
    private var rfcommOut: OutputStream? = null

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
            if (registered && pluggedDevice != null) {
                Log.i(TAG, "onAppStatusChanged: auto-reconnect")
                connect(pluggedDevice)
            }
            if (registered && pluggedDevice == null) {
                // 注册完成，检查是否有待连接的设备
                val pending = pendingConnectDevice
                pendingConnectDevice = null
                if (pending != null) {
                    Log.i(TAG, "onAppStatusChanged: auto-connect pending device")
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
                                    connect(dev)
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

    fun setHidMode(mode: Int) {
        if (mode < 0 || mode > MODE_GAME) return
        Log.i(TAG, "setHidMode: $mode")
        hidMode = mode
    }

    @SuppressLint("MissingPermission")
    private fun registerApp() {
        val hid = hidDevice ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (appContext.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
                != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) return
        }
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
    }

    // ========================================================================
    //  HID 连接
    // ========================================================================

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        // 用户手动重连时，重置快速断连计数
        quickDisconnectCount = 0
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
        disconnectRfcomm()
        unregister()
        bluetoothAdapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, hidDevice)
        hidDevice = null; isRegistered = false
        updateConnectionState(STATE_DISCONNECTED, null)
    }

    // ========================================================================
    //  RFCOMM 连接 (配合 RetroArch 蓝牙 SPP 服务端)
    // ========================================================================

    /** 通过 RFCOMM (SPP) 连接到眼镜上的 RetroArch */
    @SuppressLint("MissingPermission")
    fun connectRfcomm(device: BluetoothDevice) {
        if (connectionType == CONN_RFCOMM && rfcommSocket?.isConnected == true) {
            Log.i(TAG, "RFCOMM already connected")
            return
        }
        disconnectRfcomm()
        runCatching {
            val socket = device.createRfcommSocketToServiceRecord(RFCOMM_UUID)
            socket.connect()
            rfcommSocket = socket
            rfcommOut = socket.outputStream
            connectionType = CONN_RFCOMM
            updateConnectionState(STATE_CONNECTED, device)
            Log.i(TAG, "RFCOMM connected to ${device.address}")
        }.onFailure { e ->
            connectionType = CONN_HID
            rfcommSocket = null; rfcommOut = null
            Log.w(TAG, "RFCOMM connect failed: ${e.message}")
        }
    }

    /** 断开 RFCOMM 连接 */
    fun disconnectRfcomm() {
        try { rfcommOut?.close() } catch (_: Exception) {}
        try { rfcommSocket?.close() } catch (_: Exception) {}
        rfcommSocket = null; rfcommOut = null
        if (connectionType == CONN_RFCOMM) {
            connectionType = CONN_HID
            updateConnectionState(STATE_DISCONNECTED, null)
        }
    }

    /** 切换回 HID 连接 */
    @SuppressLint("MissingPermission")
    fun switchToHid(device: BluetoothDevice) {
        disconnectRfcomm()
        connectionType = CONN_HID
        connect(device)
    }

    // ========================================================================
    //  发送按键
    // ========================================================================

    /** 通过 RFCOMM 发送按键事件到 RetroArch
     *  协议: [keycode_byte, action_byte]
     *        action: 1 = 按下, 0 = 释放 */
    private fun rfcommSendKey(key: Int, pressed: Boolean) {
        val out = rfcommOut ?: return
        val androidKey = KEY_TO_ANDROID[key] ?: return
        try {
            out.write(byteArrayOf(androidKey.toByte(), if (pressed) 1 else 0))
            out.flush()
            Log.i(TAG, "RFCOMM key=$key androidKey=$androidKey pressed=$pressed")
        } catch (e: Exception) {
            Log.w(TAG, "RFCOMM send error: ${e.message}")
        }
    }

    /** 通过 RFCOMM 发送释放所有按键 */
    private fun rfcommSendReleaseAll() {
        val out = rfcommOut ?: return
        try {
            out.write(byteArrayOf(0xFF.toByte(), 0x00))  // 0xFF = 特殊释放全部标记
            out.flush()
            Log.i(TAG, "RFCOMM release all")
        } catch (e: Exception) {
            Log.w(TAG, "RFCOMM release error: ${e.message}")
        }
    }

    /** 发送按键按下
     *  CONN_HID:   走 BluetoothHidDevice API
     *  CONN_RFCOMM:走 RFCOMM Socket 到 RetroArch
     *
     *  HID MODE_UI: 方向键/SELECT/START → Consumer Control; A/B/X/Y/Z/L/R → 键盘
     *  HID MODE_GAME: 全部 → 键盘 */
    @SuppressLint("MissingPermission")
    fun sendButtons(device: BluetoothDevice?, keys: Set<Int>) {
        val key = keys.firstOrNull() ?: return

        // ---- RFCOMM 模式 ----
        if (connectionType == CONN_RFCOMM) {
            rfcommSendKey(key, pressed = true)
            return
        }

        // ---- HID 模式 ----
        val dev = device ?: connectedDeviceInternal ?: return
        val hid = hidDevice ?: return
        if (!isRegistered) return

        // MODE_UI + 导航键 → Consumer Control (Report ID 2)
        if (hidMode == MODE_UI) {
            val consumerReport = KEY_CONSUMER[key]
            if (consumerReport != null) {
                val ok = hid.sendReport(dev, CONSUMER_REPORT_ID, consumerReport)
                Log.i(TAG, "CONS mode=UI key=$key hex=${consumerReport.joinToString(""){"%02x".format(it)}} ok=$ok")
                return
            }
        }

        // MODE_GAME 或功能键 → Keyboard (Report ID 1)
        val kbdReport = KEY_HID[key]
        if (kbdReport != null) {
            val ok = hid.sendReport(dev, KEYBOARD_REPORT_ID, kbdReport)
            Log.i(TAG, "KBD mode=$hidMode key=$key hex=${kbdReport.joinToString(""){"%02x".format(it)}} ok=$ok")
        }
    }

    /** 发送按键释放
     *  CONN_HID:    同时释放 Consumer 和 Keyboard 报告
     *  CONN_RFCOMM: 发送释放全部标记到 RetroArch */
    @SuppressLint("MissingPermission")
    fun sendRelease(device: BluetoothDevice?) {
        // ---- RFCOMM 模式 ----
        if (connectionType == CONN_RFCOMM) {
            rfcommSendReleaseAll()
            return
        }

        // ---- HID 模式 ----
        val dev = device ?: connectedDeviceInternal ?: return
        val hid = hidDevice ?: return
        if (!isRegistered) return
        val release = byteArrayOf(0x00, 0x00)
        hid.sendReport(dev, CONSUMER_REPORT_ID, release)
        hid.sendReport(dev, KEYBOARD_REPORT_ID, release)
    }

    /** 发送鼠标移动 (仅 HID 模式)
     *  report = [buttons, dx, dy, wheel]
     *  Report ID 3 — Mouse */
    @SuppressLint("MissingPermission")
    fun sendMouseMove(device: BluetoothDevice?, dx: Int, dy: Int) {
        if (connectionType != CONN_HID) return
        val dev = device ?: connectedDeviceInternal ?: return
        val hid = hidDevice ?: return
        if (!isRegistered) return
        val clampedDx = dx.coerceIn(-127, 127).toByte()
        val clampedDy = dy.coerceIn(-127, 127).toByte()
        val report = byteArrayOf(0x00, clampedDx, clampedDy, 0x00)  // buttons=0, wheel=0
        hid.sendReport(dev, MOUSE_REPORT_ID, report)
    }

    /** 发送鼠标点击 (仅 HID 模式)
     *  @param button 1=左键, 2=右键, 3=中键 */
    @SuppressLint("MissingPermission")
    fun sendMouseClick(device: BluetoothDevice?, button: Int = 1) {
        if (connectionType != CONN_HID) return
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
