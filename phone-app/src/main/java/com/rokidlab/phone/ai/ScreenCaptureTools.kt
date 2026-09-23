package com.rokidlab.phone.ai

import android.content.Context
import android.util.Log
import com.rokidlab.phone.mirror.PhoneMirrorService
import com.rokidlab.phone.mirror.ScreenCaptureBroker
import com.rokidlab.phone.mirror.ScreenCaptureService

/**
 * 「截屏」工具的实现：抓一张**手机屏幕**画面交给模型。
 *
 * ## 两条取图路径（按代价从低到高）
 *
 * | 路径 | 条件 | 代价 | 画质 |
 * |---|---|---|---|
 * | 复用投屏会话 | 手机投屏正在跑 | ~100ms，零弹窗 | 投屏档位（WiFi 480x640 / 蓝牙 160x213）|
 * | 独立授权截屏 | 没在投屏 | 弹一次系统授权框，用户点同意 | 最长边 1440（接近原生）|
 *
 * 为什么不always走高清那条：截别人的屏幕画面是这个 App 里**隐私级别最高**的一次读取，
 * 用户已经在投屏里授权过一遍的事，再弹一次框纯属打扰；而没授权过时又必须弹
 * —— 系统也是这么设计的（MediaProjection 授权不可持久化）。
 *
 * ## 失败必须如实说
 *
 * 每一条失败分支都给出**具体原因与下一步**（没同意 / 投屏在但没画面 / 界面不在前台 /
 * 系统不允许后台起前台服务），而不是一句"截屏失败"让模型自己编。
 */
internal object ScreenCaptureTools {
    private const val TAG = "ScreenCaptureTools"

    /** 工具名（与 `PhoneToolProvider` 登记的一处必须一致） */
    const val TOOL_CAPTURE = "capture_screen"

    /** 复用它投屏画面时的等待上限（投屏 30fps，正常情况下下一帧几十毫秒就到） */
    private const val MIRROR_WAIT_MS = 2_500L

    /** 等用户在系统授权框上点按的上限 */
    private const val CONSENT_TIMEOUT_MS = 25_000L

    /** 取图结果 */
    sealed interface Shot {
        /** @param source 来源说明，会写进给模型的回报里（含画质提醒） */
        data class Ok(val jpeg: ByteArray, val source: String) : Shot

        /** @param reason 给模型的**如实**说明（含下一步该怎么做），不要再说别的 */
        data class Fail(val reason: String) : Shot
    }

    fun capture(context: Context): Shot {
        // ── 1. 投屏在跑：直接取现成画面（零弹窗）──
        if (PhoneMirrorService.isRunning()) {
            val jpeg = PhoneMirrorService.requestSnapshot(MIRROR_WAIT_MS)
            if (jpeg != null) {
                return Shot.Ok(jpeg, "手机投屏的画面（分辨率是投屏档位，小字可能看不清）")
            }
            // 服务活着但出不了帧：多半是眼镜已断。此时**不要**再去开第二条 MediaProjection
            // （同一 App 两条会话行为不确定），如实回报即可
            return Shot.Fail(
                "投屏会话开着但取不到画面（可能眼镜已断开或投屏卡住）。" +
                    "请如实告诉用户这次没截到，可以稍后再试，或先停止投屏让我重新授权截屏。",
            )
        }

        // ── 2. 独立授权：需要界面在前台才能弹系统框 ──
        if (!ScreenCaptureBroker.canRequestConsent()) {
            return Shot.Fail(
                "现在拿不到屏幕：系统截屏授权框只能由 App 界面弹出，而当前界面不在前台。" +
                    "请如实告诉用户「请先打开乐奇实验室再让我看屏幕」，不要猜屏幕内容。",
            )
        }
        val consent = ScreenCaptureBroker.requestConsent(CONSENT_TIMEOUT_MS)
            ?: return Shot.Fail(
                "用户没有同意截屏（或授权框始终没被确认）。" +
                    "请如实告诉用户「你没有同意截屏，我这次看不到屏幕」，**绝对不要**编造屏幕上的内容。",
            )
        val shot = ScreenCaptureService.capture(
            context, consent.first, consent.second, ScreenCaptureService.FRAME_TIMEOUT_MS,
        ) ?: return Shot.Fail(
            "授权拿到了但没能截到画面（系统限制后台启动截屏服务、或本机拿不到画面）。" +
                "请如实告诉用户这次没截到，不要猜屏幕内容。",
        )
        Log.i(TAG, "captured via consent: ${shot.width}x${shot.height}, ${shot.jpeg.size}B")
        return Shot.Ok(shot.jpeg, "手机屏幕（${shot.width}x${shot.height}）")
    }
}
