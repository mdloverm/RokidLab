package com.rokidlab.phone.ai

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import io.github.hzkitty.RapidOCR

/**
 * 本地 OCR（PP-OCRv4 模型，ONNX Runtime 推理）。
 *
 * 完全离线、无 GMS 依赖，用于「拍照问 AI」：
 *   眼镜拍照 → 本地识别题目文字 → 知识库检索 → DeepSeek 生成答案。
 */
object LocalOcr {
    private const val TAG = "LocalOcr"

    @Volatile
    private var engine: RapidOCR? = null

    /** native 库加载失败标记（如 16KB 页面设备上 onnxruntime/opencv 无法 dlopen），置位后不再重试 */
    @Volatile
    private var nativeUnavailable = false

    /** 初始化 OCR 引擎（首次调用会加载模型，约 1-3 秒，需在 IO 线程） */
    fun ensureInit(context: Context) {
        if (engine == null && !nativeUnavailable) {
            synchronized(this) {
                if (engine == null && !nativeUnavailable) {
                    try {
                        val t = System.currentTimeMillis()
                        engine = RapidOCR.create(context.applicationContext)
                        Log.i(TAG, "RapidOCR engine initialized in ${System.currentTimeMillis() - t}ms")
                    } catch (e: UnsatisfiedLinkError) {
                        // 16KB 页面大小设备（Android 16）上旧版 onnxruntime/opencv 无法加载
                        nativeUnavailable = true
                        Log.e(TAG, "OCR native 库无法加载（可能为 16KB 页面设备），本地 OCR 不可用", e)
                    } catch (e: Throwable) {
                        nativeUnavailable = true
                        Log.e(TAG, "OCR 引擎初始化失败", e)
                    }
                }
            }
        }
    }

    /** 识别 Bitmap 中的文字，返回拼接文本（需在 IO 线程调用） */
    fun recognize(context: Context, bitmap: Bitmap): String {
        ensureInit(context)
        val engine = engine ?: return ""
        val result = engine.run(bitmap)
        val text = result.strRes?.trim().orEmpty()
        Log.i(TAG, "recognize: ${text.length} chars in ${result.elapseTime}ms: ${text.take(80)}")
        return text
    }
}
