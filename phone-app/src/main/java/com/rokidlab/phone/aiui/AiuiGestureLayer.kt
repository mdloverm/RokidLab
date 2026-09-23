package com.rokidlab.phone.aiui

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs

/**
 * AIUI 页面的输入键名（与官方 AiuiKeyMapper / ink 的键码一致）。
 *
 * 为什么输入层全是**键**而不是触摸事件：AIUI 的目标设备是眼镜，眼镜上**没有触摸屏**，
 * 页面级输入回调只有 `onKeyDown` / `onKeyUp`（见 aiui-dev 技能 lab-runtime）。
 * 所以手机端的「滑动 / 点击」不是把触摸转发给页面，而是**翻译成眼镜上等价的那一下按键** ——
 * 这样手机上演示的操作序列与眼镜上完全一致（也才叫「演示」）。
 */
internal object AiuiKeys {
    const val UP = "ArrowUp"
    const val DOWN = "ArrowDown"
    const val LEFT = "ArrowLeft"
    const val RIGHT = "ArrowRight"
    const val ENTER = "Enter"
    const val BACK = "Backspace"
}

/**
 * 手势 → 按键的透明输入层，盖在 [AiuiWebHost] 的 WebView 之上。
 *
 * 映射（对应眼镜上的触摸板手势与物理键）：
 *  - 上滑 / 下滑 / 左滑 / 右滑 → `Arrow*`（焦点移动，对应眼镜镜腿触摸板滑动）
 *  - 单击 → `Enter`（激活焦点元素）
 *  - 双击 → **两次单击序列**（两次 `Enter`）—— 语义由用户拍板：AIUI 页面没有「双击」
 *    这种输入，与其造一个页面不认识的键，不如如实发两次单击，页面看到的就是连按两下。
 *    实现上是天然的：每次抬手各发一次 `Enter`，不需要额外识别双击。
 *
 * ⚠️ 本层刻意**吞掉**触摸（不转发 pointer 事件给 WebView）：若两路都发，一次点击会既触发
 * 页面的 `bindtap` 又触发焦点元素的 `Enter`，变成两个动作。
 */
internal class AiuiGestureLayer(
    context: Context,
    private val onKey: (code: String, action: String) -> Unit,
) : View(context) {

    /** 滑动判定阈值：取触摸 slop 的 3 倍，滤掉手指抖动（否则每次点击都会先被当成微小滑动） */
    private val swipeSlop = ViewConfiguration.get(context).scaledTouchSlop * 3f

    private var downX = 0f
    private var downY = 0f

    /** 本次手势已产出过一次按键（滑动），抬手时不再补一次单击 */
    private var fired = false

    init {
        // 透明层：不画任何东西，只接管触摸
        setBackgroundColor(0)
        isClickable = false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                fired = false
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!fired) {
                    val dx = event.x - downX
                    val dy = event.y - downY
                    if (abs(dx) >= swipeSlop || abs(dy) >= swipeSlop) {
                        fired = true
                        press(
                            if (abs(dx) > abs(dy)) {
                                if (dx > 0) AiuiKeys.RIGHT else AiuiKeys.LEFT
                            } else {
                                if (dy > 0) AiuiKeys.DOWN else AiuiKeys.UP
                            },
                        )
                    }
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                // 没滑过 = 单击（双击就是抬手两次 → 两次 Enter，见类注释）
                if (!fired) press(AiuiKeys.ENTER)
                return true
            }

            MotionEvent.ACTION_CANCEL -> return true
        }
        return super.onTouchEvent(event)
    }

    private fun press(code: String) {
        onKey(code, "down")
        onKey(code, "up")
    }
}
