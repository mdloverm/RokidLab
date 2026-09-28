package com.rokidlab.phone.proactive

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import kotlin.math.sqrt

/**
 * 手机加速度计兜底移动采样。
 *
 * 背景：走走拍拍/关怀提醒此前只吃眼镜 IMU（MotionBuffer），而眼镜端 IMU 流依赖
 * HeadImuService（默认关、断连自停）——流没开时 MotionBuffer 永远为空，
 * 表现为「走半小时也没动静，日志永远 skip: not moving / no imu data」。
 *
 * 本采样器持续监听手机加速度计，产出 |a| 幅值序列，与眼镜数据同构
 * （ProactiveGatePolicy.isMoving 直接复用）；兜底优先级：眼镜 IMU > 手机加速度计。
 *
 * 功耗：TYPE_ACCELEROMETER 常规监听约 0.5mA 级，缓冲仅保留 65 秒。
 */
object MobileMotionTracker {
    private const val TAG = "MobileMotion"
    private const val BUF_WINDOW_MS = 65_000L

    private val buf = ArrayDeque<Pair<Long, Float>>() // (t, |a|)
    private val lock = Any()
    private var listenerRef: SensorEventListener? = null
    private var registered = false

    /** 幂等启动：注册加速度计监听（传感器不存在时静默失败，调用方按「无数据」处理） */
    fun ensureStarted(context: Context) {
        if (registered) return
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return
        val sensor = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
                val v = event.values
                val mag = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
                val now = System.currentTimeMillis()
                synchronized(lock) {
                    buf.addLast(now to mag)
                    while (buf.isNotEmpty() && now - buf.first().first > BUF_WINDOW_MS) buf.removeFirst()
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        registered = sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
        if (registered) listenerRef = listener
        Log.i(TAG, "accelerometer fallback ${if (registered) "started" else "unavailable"}")
    }

    /** 停止监听并清空缓冲（仅在对应功能全部关闭时调用） */
    fun stop(context: Context) {
        val l = listenerRef ?: return
        (context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager)?.unregisterListener(l)
        listenerRef = null
        registered = false
        synchronized(lock) { buf.clear() }
        Log.i(TAG, "accelerometer fallback stopped")
    }

    /** 最近 windowMs 内的 |a| 幅值序列（与眼镜 IMU 同构，供 isMoving 消费） */
    fun recent(windowMs: Long): List<Float> {
        val now = System.currentTimeMillis()
        return synchronized(lock) {
            buf.filter { it.first >= now - windowMs }.map { it.second }
        }
    }
}
