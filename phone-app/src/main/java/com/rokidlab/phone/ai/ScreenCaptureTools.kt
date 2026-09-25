package com.rokidlab.phone.ai

import android.content.Context
import android.util.Log
import com.rokidlab.phone.access.LabAccessibility
import com.rokidlab.phone.mirror.PhoneMirrorService
import com.rokidlab.phone.mirror.ScreenCaptureBroker
import com.rokidlab.phone.mirror.ScreenCaptureService

/**
 * 「截屏」工具的实现：抓一张**手机屏幕**画面交给模型。
 *
 * ## 三条取图路径（按"代价从低到高"排）
 *
 * | 路径 | 条件 | 代价 | 画质 |
 * |---|---|---|---|
 * | 无障碍直读 | 用户开过无障碍服务（Android 11+） | 零弹窗、可连续（系统限 ~0.33s/张） | 原生分辨率 |
 * | 复用投屏会话 | 投屏正在跑、且无障碍不可用 | ~100ms，零弹窗 | 投屏档位（WiFi 480x640 / 蓝牙 160x213）|
 * | 独立授权截屏 | 上述都不可用 | 弹一次系统授权框，用户点同意 | 最长边 1440 |
 *
 * **为什么无障碍排第一**：它是唯一"零弹窗且可连续"的通道 —— MediaProjection 的授权不可持久化，
 * 抓完必须 `stop()`（否则状态栏常驻截屏标记），于是每抓一帧都要用户点一次系统框，
 * 「截屏 → 判断 → 点击 → 再截屏」这种闭环根本走不起来（这也是无障碍操作域存在的前提）。
 * 唯一的代价是用户要在系统设置里手动开一次这个服务，而它可随时关掉。
 *
 * 后两条**保留**而不是删掉：无障碍服务没开、或系统版本低于 Android 11 时，
 * 它们是仅剩的取图手段 —— 那时候"弹一次授权框"也好过"看不了屏幕"。
 *
 * ## 失败必须如实说
 *
 * 每一条失败分支都给出**具体原因与下一步**（没开无障碍 / 投屏在但没画面 / 界面不在前台 /
 * 系统不允许后台起前台服务 / 用户没同意），并尽量带上"上一条路为什么没成"，
 * 而不是一句"截屏失败"让模型自己编。
 */
internal object ScreenCaptureTools {
    private const val TAG = "ScreenCaptureTools"

    /** 工具名（与 `PhoneToolProvider` 登记的一处必须一致） */
    const val TOOL_CAPTURE = "capture_screen"

    /** 复用它投屏画面时的等待上限（投屏 30fps，正常情况下下一帧几十毫秒就到） */
    private const val MIRROR_WAIT_MS = 2_500L

    /** 等无障碍服务交出一帧的上限（正常 <300ms，含节流等待） */
    private const val ACCESS_TIMEOUT_MS = 6_000L

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
        // ── 1. 无障碍直读：零弹窗、原生分辨率（首选）──
        val accessFailure: String? = if (LabAccessibility.isConnected()) {
            when (val shot = LabAccessibility.takeScreenshot(ACCESS_TIMEOUT_MS)) {
                is LabAccessibility.Shot.Ok ->
                    return Shot.Ok(shot.jpeg, "手机屏幕（无障碍直读，原生 ${shot.width}x${shot.height}）")

                is LabAccessibility.Shot.Fail -> shot.reason
            }
        } else {
            null
        }
        // 已经失败过的那条路要写进后面的回报里：否则用户看到的是"怎么又要我点一次授权框"，
        // 而真正的原因（无障碍被关掉/服务没连上）没有任何地方体现
        val whyNotAccess = accessFailure?.let { "（无障碍取图没成：$it）" }.orEmpty()

        // ── 2. 投屏在跑：直接取现成画面（零弹窗）──
        if (PhoneMirrorService.isRunning()) {
            val jpeg = PhoneMirrorService.requestSnapshot(MIRROR_WAIT_MS)
            if (jpeg != null) {
                return Shot.Ok(jpeg, "手机投屏的画面（分辨率是投屏档位，小字可能看不清）")
            }
            // 服务活着但出不了帧：多半是眼镜已断。此时**不要**再去开第二条 MediaProjection
            // （同一 App 两条会话行为不确定），如实回报即可
            return Shot.Fail(
                "投屏会话开着但取不到画面（可能眼镜已断开或投屏卡住）$whyNotAccess。" +
                    "请如实告诉用户这次没截到，可以稍后再试，或先停止投屏让我重新授权截屏。",
            )
        }

        // ── 3. 独立授权：需要界面在前台才能弹系统框 ──
        if (!ScreenCaptureBroker.canRequestConsent()) {
            return Shot.Fail(
                "现在拿不到屏幕：系统截屏授权框只能由 App 界面弹出，而当前界面不在前台 $whyNotAccess。" +
                    "请如实告诉用户「请先打开乐奇实验室再让我看屏幕」，不要猜屏幕内容。",
            )
        }
        val consent = ScreenCaptureBroker.requestConsent(CONSENT_TIMEOUT_MS)
            ?: return Shot.Fail(
                "用户没有同意截屏（或授权框始终没被确认）$whyNotAccess。" +
                    "请如实告诉用户「你没有同意截屏，我这次看不到屏幕」，**绝对不要**编造屏幕上的内容。",
            )
        val shot = ScreenCaptureService.capture(
            context, consent.first, consent.second, ScreenCaptureService.FRAME_TIMEOUT_MS,
        ) ?: return Shot.Fail(
            "授权拿到了但没能截到画面（系统限制后台启动截屏服务、或本机拿不到画面）$whyNotAccess。" +
                "请如实告诉用户这次没截到，不要猜屏幕内容。",
        )
        Log.i(TAG, "captured via consent: ${shot.width}x${shot.height}, ${shot.jpeg.size}B")
        return Shot.Ok(
            shot.jpeg,
            "手机屏幕（${shot.width}x${shot.height}）$ACCESSIBILITY_HINT",
        )
    }

    /**
     * 走到"弹授权框"这条路时**顺带**告诉模型还有一条零弹窗的路。
     *
     * 为什么必须在这里说：无障碍服务的开关只能用户在系统设置里手动打开，App 无法代开。
     * 用户真正的抱怨往往就是"每次都要点一次授权框"——如果只在屏幕操作工具里引导，
     * 只用 `capture_screen` 的人永远不知道有这条路。这条提示只在**本次真的弹了框**时出现，
     * 也就是只在"用户确实被这一步打扰到"的时刻出现，不会变成日常噪音。
     */
    private const val ACCESSIBILITY_HINT =
        "。若想以后截屏不再弹这个授权框，可让用户在系统「设置 → 无障碍」里开启「乐奇实验室·屏幕操作」"
}
