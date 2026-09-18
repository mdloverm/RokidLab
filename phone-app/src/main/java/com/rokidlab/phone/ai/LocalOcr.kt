package com.rokidlab.phone.ai

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.rokidlab.phone.util.HttpClient
import io.github.hzkitty.RapidOCR
import io.github.hzkitty.entity.OcrConfig
import java.io.File

/**
 * 本地 OCR（PP-OCRv4 模型，ONNX Runtime 推理）。
 *
 * 完全离线、无 GMS 依赖，用于「拍照问 AI」：
 *   眼镜拍照 → 本地识别题目文字 → 知识库检索 → DeepSeek 生成答案。
 *
 * 模型不内置 APK（3 个 onnx 共约 15.4MB，官方 AAR 已替换为剥除 assets 的薄 AAR），
 * 首次使用时从 [MODELS] 指向的 Gitee Release 下载到 noBackupFilesDir/ocr_models，
 * 之后以绝对路径直接加载（OrtInferSession 对已存在的路径走 createSession 直载，
 * 不再回退 assets）。下载失败时 [ensureInit] 返回 false、[recognize] 返回空串，
 * 调用方通过 [nativeUnavailable] / [modelsUnavailable] 区分降级提示。
 */
object LocalOcr {
    private const val TAG = "LocalOcr"
    private const val MODEL_DIR = "ocr_models"

    /** 下载失败后的重试退避，避免弱网下每次拍照答题都卡在超时上 */
    private const val FAILURE_BACKOFF_MS = 60_000L

    private class ModelSpec(val fileName: String, val url: String, val sizeBytes: Long) {
        /** 文件存在且字节数与源模型一致才视为就绪（半成品/损坏文件会重新下载） */
        fun isReady(file: File): Boolean = file.isFile && file.length() == sizeBytes
    }

    // 模型托管于 Gitee Release ocr-models-v1 附件；sizeBytes 为精确字节数，用于完整性校验
    private val MODELS = listOf(
        ModelSpec(
            "ch_PP-OCRv4_det_infer.onnx",
            "https://gitee.com/dlover1314/RokidLab/releases/download/ocr-models-v1/ch_PP-OCRv4_det_infer.onnx",
            4_745_517L,
        ),
        ModelSpec(
            "ch_ppocr_mobile_v2.0_cls_infer.onnx",
            "https://gitee.com/dlover1314/RokidLab/releases/download/ocr-models-v1/ch_ppocr_mobile_v2.0_cls_infer.onnx",
            585_532L,
        ),
        ModelSpec(
            "ch_PP-OCRv4_rec_infer.onnx",
            "https://gitee.com/dlover1314/RokidLab/releases/download/ocr-models-v1/ch_PP-OCRv4_rec_infer.onnx",
            10_857_958L,
        ),
    )

    @Volatile
    private var engine: RapidOCR? = null

    /** native 库加载失败标记（如 16KB 页面设备上 onnxruntime/opencv 无法 dlopen），置位后不再重试 */
    @Volatile
    var nativeUnavailable = false
        private set

    /** 模型未就绪标记（下载失败进入 60s 退避，或引擎加载失败），供调用方降级提示；下次调用会自动重试 */
    @Volatile
    var modelsUnavailable = false
        private set

    @Volatile
    private var failureBackoffUntil = 0L

    /**
     * 确保模型文件就绪，缺失则逐个下载（需在 IO 线程调用）。
     * [onProgress] 收到跨文件加权的总进度 0..100。
     */
    fun ensureModels(context: Context, onProgress: ((Int) -> Unit)? = null): Boolean {
        val dir = modelsDir(context.applicationContext)
        val missing = MODELS.filter { !it.isReady(File(dir, it.fileName)) }
        if (missing.isEmpty()) {
            modelsUnavailable = false
            return true
        }
        if (System.currentTimeMillis() < failureBackoffUntil) return false
        dir.mkdirs()
        val totalMissing = missing.sumOf { it.sizeBytes }.coerceAtLeast(1L)
        var done = 0L
        return try {
            for (spec in missing) {
                val part = File(dir, spec.fileName + ".part")
                // 注意：尾随 lambda 会绑定到 isCancelled 参数，onPercent 必须具名传递
                HttpClient.downloadWithPercent(spec.url, part, onPercent = { pct ->
                    onProgress?.invoke(((done + spec.sizeBytes * pct / 100L) * 100L / totalMissing).toInt().coerceIn(0, 99))
                })
                require(part.length() == spec.sizeBytes) {
                    "OCR model size mismatch: ${spec.fileName} ${part.length()} != ${spec.sizeBytes}"
                }
                val target = File(dir, spec.fileName)
                if (!part.renameTo(target)) {
                    part.copyTo(target, overwrite = true)
                    part.delete()
                }
                done += spec.sizeBytes
            }
            modelsUnavailable = false
            onProgress?.invoke(100)
            true
        } catch (e: Exception) {
            modelsUnavailable = true
            failureBackoffUntil = System.currentTimeMillis() + FAILURE_BACKOFF_MS
            Log.e(TAG, "OCR 模型下载失败（${FAILURE_BACKOFF_MS / 1000}s 内不重试）", e)
            false
        }
    }

    /**
     * 初始化 OCR 引擎（首次调用会下载缺失模型并加载，约 1-3 秒，需在 IO 线程）。
     * @return 引擎是否可用
     */
    fun ensureInit(context: Context, onProgress: ((Int) -> Unit)? = null): Boolean {
        if (engine == null && !nativeUnavailable) {
            synchronized(this) {
                if (engine == null && !nativeUnavailable) {
                    try {
                        val appCtx = context.applicationContext
                        if (!ensureModels(appCtx, onProgress)) return false
                        val t = System.currentTimeMillis()
                        engine = RapidOCR.create(appCtx, buildConfig(appCtx))
                        Log.i(TAG, "RapidOCR engine initialized in ${System.currentTimeMillis() - t}ms")
                    } catch (e: UnsatisfiedLinkError) {
                        // 16KB 页面大小设备（Android 16）上旧版 onnxruntime/opencv 无法加载
                        nativeUnavailable = true
                        Log.e(TAG, "OCR native 库无法加载（可能为 16KB 页面设备），本地 OCR 不可用", e)
                    } catch (e: Throwable) {
                        modelsUnavailable = true
                        Log.e(TAG, "OCR 引擎初始化失败", e)
                    }
                }
            }
        }
        return engine != null
    }

    /** 识别 Bitmap 中的文字，返回拼接文本（需在 IO 线程调用；引擎不可用返回空串） */
    fun recognize(context: Context, bitmap: Bitmap): String {
        if (!ensureInit(context)) return ""
        val engine = engine ?: return ""
        val result = engine.run(bitmap)
        val text = result.strRes?.trim().orEmpty()
        Log.i(TAG, "recognize: ${text.length} chars in ${result.elapseTime}ms: ${text.take(80)}")
        return text
    }

    private fun modelsDir(context: Context): File = File(context.applicationContext.noBackupFilesDir, MODEL_DIR)

    /** 自定义配置：3 个模型均指向下载后的绝对路径；rec 字典用模型内嵌 "character" 元数据（keysPath 保持空） */
    private fun buildConfig(context: Context): OcrConfig {
        val dir = modelsDir(context)
        val config = OcrConfig()
        config.Det.modelPath = File(dir, "ch_PP-OCRv4_det_infer.onnx").absolutePath
        config.Cls.modelPath = File(dir, "ch_ppocr_mobile_v2.0_cls_infer.onnx").absolutePath
        config.Rec.modelPath = File(dir, "ch_PP-OCRv4_rec_infer.onnx").absolutePath
        return config
    }
}
