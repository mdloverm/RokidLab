package com.rokidlab.phone.access

import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 读 UI 树 → 给模型一份「当前界面上有什么、能点什么」的清单（纯数据层，不做格式化）。
 *
 * ## 为什么读屏要排在截屏前面
 * 一张截图进模型要几千 token，而且模型还得**猜**按钮在哪；UI 树给出的是
 * 「『发送』按钮 @ 900,2100」这种确定性事实，token 少一个量级、还不会看错。
 * 两者的关系是互补：读屏拿不到的自绘界面（WebView / 游戏 / Flutter），才需要截图。
 *
 * ## 编号（`[1] [2] …`）与失效判定
 * 编号只对**最近一次 [read] 的结果**有效（与 `browser_snapshot` 同一套约定）：
 * 界面随时可能被用户或后台刷新改掉。所以 [clickRef] 不直接相信记账，
 * 而是拿记录里的文案回当前树里**重新找一遍**，找不到就如实说"页面已变化，请重新读取"
 * —— 而不是照着旧坐标点下去。这是"点错东西"和"告诉你需要重读"的分界线。
 */
internal object ScreenTree {

    private const val TAG = "ScreenTree"

    /** 遍历上限：防某些界面（长列表/WebView）把节点树撑到几万个导致读屏卡住 */
    private const val MAX_NODES = 1_500
    private const val MAX_DEPTH = 40

    /** 编号上限：太多编号会淹掉模型注意力，而真正可点的通常就十来个（工具侧回报也读它） */
    const val MAX_REFS = 40

    /** 文本清单上限 */
    private const val MAX_TEXT_LINES = 120

    /** 单个控件文案的展示长度上限（容器节点可能挂着整段正文） */
    private const val MAX_LABEL_CHARS = 40

    /** 一个可交互控件的**值快照**（不持有 AccessibilityNodeInfo：那种对象会失效、也不该跨线程留存） */
    data class NodeRef(
        val ref: Int,
        val label: String,
        val className: String,
        val editable: Boolean,
        val password: Boolean,
        val scrollable: Boolean,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
    ) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
        val centerX: Int get() = (left + right) / 2
        val centerY: Int get() = (top + bottom) / 2

        /** 给模型看的短描述：`[1] 发送 (Button) @ 900,2100` */
        fun describe(): String {
            val kind = when {
                password -> "密码框"
                editable -> "输入框"
                scrollable -> "可滚动区域"
                else -> className.substringAfterLast('.').ifBlank { "控件" }
            }
            return "[$ref] ${label.ifBlank { "（无文字）" }} ($kind) @ $centerX,$centerY"
        }
    }

    /** 一次读屏的结果 */
    data class Screen(
        val packageName: String,
        val refs: List<NodeRef>,
        val texts: List<String>,
        val nodeCount: Int,
        val truncated: Boolean,
    )

    sealed interface Read {
        data class Ok(val screen: Screen) : Read
        data class Fail(val reason: String) : Read
    }

    sealed interface Click {
        /** @param via 这次是**怎么**点中的（控件 / 坐标），会写进给模型的回报 */
        data class Ok(val via: String) : Click
        data class Fail(val reason: String) : Click
    }

    /** 当前聚焦输入框的信息（供 `type_text` 在做动作前判定，见 ScreenOpToolProvider） */
    data class FieldInfo(val label: String, val className: String, val editable: Boolean, val password: Boolean)

    private const val NO_SERVICE = "无障碍服务没有连上（可能没开，或刚被关掉）"

    /** 最近一次 [read] 的编号表（进程内唯一：单设备，不需要按会话隔离） */
    @Volatile
    private var lastRefs: List<NodeRef> = emptyList()

    // ═══════════════════ 读屏 ═══════════════════

    fun read(): Read {
        if (!LabAccessibilityService.isConnected()) return Read.Fail(NO_SERVICE)
        val root = rootNode() ?: return Read.Fail(
            "读不到当前界面（可能正在切页/锁屏，或这个界面不允许无障碍读取）",
        )

        val candidates = mutableListOf<NodeRef>()
        // 文本条目带位置，最后按"先上后下、先左后右"排 —— BFS 顺序对正文来说是乱的
        val textEntries = mutableListOf<Triple<Int, Int, String>>()
        var count = 0
        var truncated = false

        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.addLast(root to 0)
        while (queue.isNotEmpty()) {
            if (count >= MAX_NODES) {
                truncated = true
                break
            }
            val (node, depth) = queue.removeFirst()
            count++

            val bounds = Rect().also { node.getBoundsInScreen(it) }
            val visible = node.isVisibleToUser && !bounds.isEmpty
            if (visible) {
                // 文本清单用「自身文本」；可点控件的名字才需要向下找（见 labelOf）
                val own = ownTextOf(node)
                if (own.isNotEmpty()) textEntries.add(Triple(bounds.top, bounds.left, own))
                if (isInteractive(node)) {
                    candidates.add(
                        NodeRef(
                            ref = 0,
                            label = labelOf(node).take(MAX_LABEL_CHARS),
                            className = node.className?.toString().orEmpty(),
                            editable = node.isEditable,
                            password = node.isPassword,
                            scrollable = node.isScrollable,
                            left = bounds.left,
                            top = bounds.top,
                            right = bounds.right,
                            bottom = bounds.bottom,
                        ),
                    )
                }
            }

            if (depth < MAX_DEPTH) {
                for (i in 0 until node.childCount) {
                    val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
                    queue.addLast(child to depth + 1)
                }
            }
        }

        // 阅读顺序编号；并去掉「文案 + 位置」完全相同的重复项 ——
        // 可点容器与它内部那个同尺寸的可点控件会成对出现，不去重就会看到两个一样的编号
        val ordered = candidates
            .distinctBy { "${it.label}|${it.left},${it.top},${it.right},${it.bottom}" }
            .sortedWith(compareBy({ it.top }, { it.left }))
            .take(MAX_REFS)
            .mapIndexed { index, ref -> ref.copy(ref = index + 1) }
        if (candidates.size > MAX_REFS) truncated = true
        lastRefs = ordered

        val texts = textEntries
            .sortedWith(compareBy({ it.first }, { it.second }))
            .map { it.third }
            // 只压相邻重复：同一个容器与它的子控件常常给出同一句话
            .fold(mutableListOf<String>()) { acc, line ->
                if (acc.lastOrNull() != line) acc.add(line)
                acc
            }
        if (texts.size > MAX_TEXT_LINES) truncated = true

        Log.i(TAG, "read screen: pkg=${root.packageName} nodes=$count refs=${ordered.size} truncated=$truncated")
        return Read.Ok(
            Screen(
                packageName = root.packageName?.toString().orEmpty(),
                refs = ordered,
                texts = texts.take(MAX_TEXT_LINES),
                nodeCount = count,
                truncated = truncated,
            ),
        )
    }

    // ═══════════════════ 点击 ═══════════════════

    /** 按 [read] 给出的编号点击（失效判定见类注释） */
    fun clickRef(ref: Int): Click {
        val record = lastRefs.firstOrNull { it.ref == ref }
            ?: return Click.Fail("编号 $ref 不在最近一次 read_screen 的结果里，请先重新 read_screen")
        if (record.label.isNotEmpty()) {
            val root = rootNode() ?: return Click.Fail(NO_SERVICE)
            val node = findNode(root) { labelOf(it).take(MAX_LABEL_CHARS) == record.label }
                ?: return Click.Fail(
                    "界面已经变了：编号 $ref（「${record.label}」）在当前界面上找不到了。" +
                        "请重新 read_screen 再决定点哪里，**不要**凭记忆点。",
                )
            return if (LabAccessibility.clickNode(node)) {
                Click.Ok("控件点击「${record.label}」")
            } else {
                Click.Fail("「${record.label}」这个控件没接受点击（可能被遮挡或已禁用）")
            }
        }
        // 纯图标控件没有文案可比对：只能按记录到的中心坐标点，并如实说明这次是坐标点击
        if (record.width <= 0 || record.height <= 0) {
            return Click.Fail("编号 $ref 的记录里没有有效区域，请重新 read_screen")
        }
        return if (LabAccessibility.tap(record.centerX, record.centerY)) {
            Click.Ok("按坐标点击（该控件没有文字，位置来自最近一次 read_screen）")
        } else {
            Click.Fail("坐标点击被系统拒绝（通常是有另一次手势还在执行），请稍后再试")
        }
    }

    /** 按文案找控件并点击（比坐标稳：文案不变就找得到） */
    fun clickByLabel(target: String, exact: Boolean): Click {
        val query = target.trim()
        if (query.isEmpty()) return Click.Fail("没有给出要点击的文案")
        val root = rootNode() ?: return Click.Fail(NO_SERVICE)
        val node = findNode(root) { labelMatches(it, query, exact) }
            ?: return Click.Fail("当前界面上找不到「$query」。请先 read_screen 看看到底有哪些可点的东西。")
        return if (LabAccessibility.clickNode(node)) {
            Click.Ok("控件点击「$query」")
        } else {
            Click.Fail("「$query」这个控件没接受点击（可能被遮挡或已禁用）")
        }
    }

    /** 最近一次读屏里该编号控件的文案（风险判定用；空串 = 无文案） */
    fun labelOfRef(ref: Int): String = lastRefs.firstOrNull { it.ref == ref }?.label.orEmpty()

    /** 该编号是否来自最近一次读屏（先判存在再判风险，避免对不存在的编号问用户） */
    fun refExists(ref: Int): Boolean = lastRefs.any { it.ref == ref }

    /** 当前聚焦的输入框（没有则 null） */
    fun focusedField(): FieldInfo? {
        val root = rootNode() ?: return null
        val focused = runCatching { root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull() ?: return null
        return FieldInfo(
            label = labelOf(focused).take(MAX_LABEL_CHARS),
            className = focused.className?.toString().orEmpty(),
            editable = focused.isEditable,
            password = focused.isPassword,
        )
    }

    /** 当前活动窗口的根节点（拿不到 = 服务没连上或界面不可读） */
    private fun rootNode(): AccessibilityNodeInfo? =
        LabAccessibilityService.instance?.let { runCatching { it.rootInActiveWindow }.getOrNull() }

    // ═══════════════════ 内部：节点访问 ═══════════════════

    /** 控件是否算「能点/能填」的目标 */
    private fun isInteractive(node: AccessibilityNodeInfo): Boolean =
        node.isClickable || node.isEditable || node.isCheckable

    /** 自身文本 / 无障碍描述（**不**递归子节点） */
    private fun ownTextOf(node: AccessibilityNodeInfo): String {
        val own = node.text?.toString()?.trim().orEmpty()
        if (own.isNotEmpty()) return own
        return node.contentDescription?.toString()?.trim().orEmpty()
    }

    /**
     * 控件文案：自身文本 → 无障碍描述 → **向下找 3 层内的第一个非空文本**。
     *
     * 最后一条很关键：Android 上「可点的容器 + 里面一个 TextView」是绝大多数列表项的写法，
     * 只看自身文本会得到一堆「（无文字）」的编号，模型就没法按文案点了。
     */
    private fun labelOf(node: AccessibilityNodeInfo, depth: Int = 0): String {
        val own = ownTextOf(node)
        if (own.isNotEmpty()) return own
        if (depth >= 3) return ""
        for (i in 0 until node.childCount) {
            val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
            val childLabel = labelOf(child, depth + 1)
            if (childLabel.isNotEmpty()) return childLabel
        }
        return ""
    }

    private fun labelMatches(node: AccessibilityNodeInfo, target: String, exact: Boolean): Boolean {
        val label = labelOf(node)
        if (label.isEmpty()) return false
        return if (exact) {
            label == target
        } else {
            label.contains(target, ignoreCase = true)
        }
    }

    /**
     * 广度优先找第一个匹配的节点。
     *
     * 同一次匹配可能同时命中"可点容器"和它内部的文本控件，因此**优先返回可点的那个**：
     * 拿容器去点才会真的触发动作，拿内部 TextView 点通常什么也不发生。
     */
    private fun findNode(root: AccessibilityNodeInfo, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        var fallback: AccessibilityNodeInfo? = null
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.addLast(root to 0)
        var count = 0
        while (queue.isNotEmpty() && count < MAX_NODES) {
            val (node, depth) = queue.removeFirst()
            count++
            if (predicate(node)) {
                if (isInteractive(node)) return node
                if (fallback == null) fallback = node
            }
            if (depth < MAX_DEPTH) {
                for (i in 0 until node.childCount) {
                    val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
                    queue.addLast(child to depth + 1)
                }
            }
        }
        return fallback
    }
}
