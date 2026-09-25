package com.rokidlab.phone.access

import com.rokidlab.phone.browser.BrowserGuard

/**
 * 屏幕操作的**动作风险判定**（纯函数，可单测）。
 *
 * ## 为什么屏幕点击不能按 EXTERNAL 档走确认闸门
 * 与 `BrowserGuard` 同一个理由：把 `tap_screen` 标成 [com.rokidlab.phone.ai.ToolRisk.EXTERNAL_SIDE_EFFECT]
 * 意味着**每一次点击**都要用户在眼镜/手机上点头。「打开设置 → 找到 Wi-Fi → 点进去」这种任务
 * 会有十几次点击，变成十几次打断 —— 用户要的是"替我把这件事做完"。
 *
 * 所以 `tap_screen` 按本机副作用登记（不逐次过闸门），把"这一下要不要人命"收在这里：
 * 命中**提交类关键词**（发送/支付/删除/确认…）或**不给文案的裸坐标点击**时，
 * 工具**不执行**，返回一句「需要你确认」，由模型如实复述、拿到明确同意后带 `confirmed=true` 重调。
 *
 * ## 关键词表不在这里，在 [BrowserGuard]
 * 那张表是网页点击与屏幕点击**共用**的（见 [BrowserGuard.labelConfirmReason]）。
 * 本对象只负责"屏幕语境"的两件事：把文案送去判定、以及说一句屏幕侧的人话。
 *
 * ## 明确的代价（不要当成 bug）
 * 关键词是**启发式**，会漏：按钮叫"Go""下一步""确认无误"都可能绕过。
 * 兜底不在这一层 —— ① 用户就在手机前面，**屏幕上的每一下他都看得见**；
 * ② 裸坐标点击（模型说不出点的是什么）恒定要求确认，没有"盲点"这条路；
 * ③ 读屏回来的文字对模型是 `UNTRUSTED_EXTERNAL`（界面里夹带的注入话术不会被当指令）。
 */
internal object ScreenGuard {

    /**
     * 文案命中提交类关键词时的原因（null = 可以直接点）。
     *
     * @param label 目标控件的文案（`read_screen` 给的编号文案，或 `tap_screen` 传的 text）
     */
    fun confirmReason(label: String): String? = BrowserGuard.labelConfirmReason(label)

    /**
     * 「不知道点的是什么」——裸坐标点击的固定理由。
     *
     * 这条不是启发式而是**恒定规则**：没有文案就没有任何判断依据，而模型愿意用裸坐标，
     * 通常是它没先 `read_screen`（也就是它自己也不确定那里有什么）。
     * 要求它先读屏、或者拿用户的一句同意换这一次盲点，两个出口都比"默默点下去"好。
     */
    const val BLIND_TAP_REASON = "这是不给文案的坐标点击，不知道那个位置是什么控件"

    /** 是否必须**先**问用户（无文案 / 文案命中关键词 / 裸坐标点击） */
    fun needsConfirm(label: String?): Boolean =
        label.isNullOrBlank() || confirmReason(label) != null

    /**
     * 需要确认时给模型的**唯一话术**（工具返回文本的第一句）。
     *
     * ⚠️ 与网页侧同一条协议（[BrowserGuard.NEED_CONFIRM_PREFIX]）：模型见到这个前缀就知道
     * 「动作没执行、要去问用户、拿到同意后带 confirmed=true 重调」。改这句必须同步改
     * `ScreenOpToolProvider` 里两个工具的 description —— 那是模型唯一能读到这条规则的地方。
     */
    fun message(action: String, why: String): String =
        BrowserGuard.NEED_CONFIRM_PREFIX + action + "（" + why + "），我没有执行。" +
            "请把这一步如实告诉用户并等他说同意，之后才能带 confirmed=true 重新调用"
}
