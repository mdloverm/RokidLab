package com.rokidlab.phone.browser

/**
 * 浏览器操作的**动作风险判定**（纯函数，可单测）。
 *
 * ## 这一层要解决的问题
 * `browser_click` / `browser_type` 若按默认做法标成 `EXTERNAL_SIDE_EFFECT`，每一步都要过
 * 审批闸门（眼镜端点头/手机弹窗），多步任务（登录 → 搜索 → 翻页 → 填表 → 提交）会变成
 * 一连串打断 —— 用户实际要的是"让它替我把这件事做完"。
 *
 * 所以这两个工具按**本机副作用**登记（不逐次弹确认），把"这一步到底要不要人命"的判定
 * 收在本文件里：命中「提交类」或落在支付/银行类站点时，工具**不执行**，返回一句
 * 「需要你确认」，由模型如实复述给用户、拿到明确同意后再带 `confirmed=true` 重调。
 *
 * ## 明确的代价（不要当成 bug）
 * 关键词表是**启发式**，会漏 —— 按钮文案是"Go""确认无误""下一步"这类都可能绕过。
 * 兜底不在这一层：① 用户在手机屏幕上**看得到每一步**（浏览器是可见的前台页面）；
 * ② 支付/银行站点按域名整体判定，不依赖按钮文案；③ 页面内容对模型是
 * `UNTRUSTED_EXTERNAL`（注入的话术不会被当指令）。
 */
internal object BrowserGuard {

    /**
     * 提交类动作关键词 —— 命中即要求用户确认。
     *
     * 取材于真实站点的按钮文案（发送/发布/下单/支付…）。刻意**不含**「登录」「搜索」
     * 「下一页」「关注」这类高频且低代价的动作：把它们纳入会让正常流程每步一问，
     * 反而促使模型去找绕过办法。
     */
    private val SUBMIT_KEYWORDS = listOf(
        "发送", "提交", "发布", "发表", "下单", "购买", "结算", "结账", "支付", "付款",
        "转账", "打赏", "充值", "提现", "退订", "注销", "删除", "移除", "撤回", "清空",
        "报名", "预约", "申请", "绑定", "解绑", "开通", "续费", "升级会员", "确认", "确定",
        "同意", "授权", "上传", "保存", "举报", "退款", "退货", "取消订单", "加入购物车",
        "send", "submit", "post", "publish", "pay", "buy", "checkout", "confirm", "delete",
        "subscribe", "unsubscribe", "upload", "sign up", "register",
    )

    /**
     * 支付/银行类站点的域名特征。命中即**整站**要求确认（不看按钮文案）——
     * 这类站点上"哪一下算提交"根本不可靠地判出来，只能按站点整体保守处理。
     *
     * 误判代价只是一个确认提示（用户说一声就过），所以宁可宽松。
     */
    private val SENSITIVE_HOST_MARKERS = listOf(
        // 支付
        "alipay", "tenpay", "unionpay", "paypal", "95516", "pay.",
        // 银行 / 信用卡
        "icbc", "ccb.com", "abchina", "boc.cn", "bankcomm", "cmbchina", "spdb", "cebbank",
        "cmbc.com", "hxb.com", "cib.com", "psbc", "citicbank", "cebcn", "bankofbeijing",
        "nbcb.com", "srcb.com", "cgbchina", "bankofshanghai", "hzbank", "qdccb",
        // 支付路径（不依赖域名：不少电商把收银台放在主域的 /pay 下）
        "/pay", "checkout", "cashier", "payment",
    )

    /** 网页是否属于「支付/银行类」需要整体保守处理的站点 */
    fun isSensitiveTarget(url: String?): Boolean {
        val u = url.orEmpty().lowercase()
        if (u.isEmpty()) return false
        return SENSITIVE_HOST_MARKERS.any { u.contains(it) }
    }

    /**
     * 这一次点击/输入**为什么**需要用户确认（null = 不需要确认）。
     *
     * 返回值会拼进 [confirmMessage] 给用户看，所以要是一句人话（"这是密码输入框"），
     * 不是给程序看的枚举名。
     *
     * @param url    当前页面 URL（判定是否支付/银行类站点）
     * @param tag    元素标签（a / button / input …）
     * @param type   input 的 type（submit / password / text …）
     * @param label  元素可见文案
     */
    fun confirmReason(url: String?, tag: String, type: String, label: String): String? {
        if (isSensitiveTarget(url)) return "这是支付/银行类网站，任何一步都可能涉及资金"
        // 原生提交控件：没有文案也能判出来（<input type=submit> 常常只有 value）
        if (type == "submit" || type == "image") return "这是一个提交按钮"
        // 密码输入：填密码是全流程里最该让人过目的一步
        if (type == "password") return "这是密码输入框"
        // 比对前把空格压掉：按钮文案里 "Sign Up"/"sign up"/"SIGNUP" 是同一种东西，
        // 而关键词表里带空格（"sign up"）—— 只在一边去空格会让这类词永远匹配不上。
        val text = label.lowercase().replace(" ", "")
        if (text.isEmpty()) return null
        val hit = SUBMIT_KEYWORDS.firstOrNull { text.contains(it.replace(" ", "")) } ?: return null
        return "它的文案是「$hit」，属于提交类动作"
    }

    /** 这一次点击/输入是否需要用户确认（[confirmReason] 的布尔化，供调用点按需二选一） */
    fun needsConfirm(url: String?, tag: String, type: String, label: String): Boolean =
        confirmReason(url, tag, type, label) != null

    /**
     * 需要确认时给模型的**唯一话术**（工具返回文本的第一句）。
     *
     * ⚠️ 它是给模型看的"分岔信号"，模型侧对它的处理规则写在工具 schema 的 description 里
     * （那一段是**可信指令**，而工具返回值会被包进 `untrusted_source` 当数据看）。
     * 改这句话必须同步改 `BrowserToolProvider` 里的 description。
     */
    const val NEED_CONFIRM_PREFIX = "需要你确认："

    /** 组装「需要确认」的返回文本（站点 + 动作 + 为什么） */
    fun confirmMessage(url: String?, action: String, why: String): String {
        val host = runCatching { java.net.URI(url.orEmpty()).host }.getOrNull() ?: url.orEmpty()
        return NEED_CONFIRM_PREFIX + "这一步在「" + host.ifBlank { "当前网页" } + "」上是" + action +
            "（" + why + "），我没有执行。请把这一步如实告诉用户并等他说同意，之后才能带 confirmed=true 重新调用"
    }
}
