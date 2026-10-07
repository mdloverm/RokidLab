package com.rokidlab.phone.ai

import android.content.Context
import android.util.Log
import com.rokidlab.phone.ai.approval.ApprovalGate
import com.rokidlab.phone.ai.approval.PageScope
import com.rokidlab.phone.ai.approval.ToolDecision
import com.rokidlab.phone.ai.approval.ToolSource
import com.rokidlab.phone.util.LogCollector
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * AIUI 页面统一工具网关（"整个工具出一个口"）。
 *
 * 背景：AIUI 页面（模型生成的 .ink）运行在眼镜端 WebView 里，除了渲染什么都做不了。
 * 本网关把手机端已有的工具（见 ToolRegistry.toolList，随版本增删）通过一个固定入口暴露给页面：
 *
 *   页面 Lab.callTool('play_song', { songName: '西厢' })
 *     → RFCOMM 上行 __LAB_TOOL__
 *     → [ToolGateway.call]
 *     → ToolRegistry.execute
 *     → 结果 JSON 下行回页面
 *
 * 设计约束（改代码前请先读）：
 * 1. 域白名单按 DOMAIN 继承而非逐工具配置 —— 后期加工具只改 ToolRegistry 一行注册，
 *    网关/协议/JS/skill 全不动。逐工具配会破坏这个性质。
 * 2. 域白名单**基线是全开放**（用户 2026-09-09 拍板），当前仅摘掉 `shell` 域
 *    （本机执行 = 任意命令执行，不给第三方页面）。收窄时只改 [PageScope.ALLOWED_DOMAINS] 一处；
 *    **准入判定的逻辑与文案也只在 [PageScope]**（本类的 [precheckToolCall] 与审批链共用它）。
 * 3. 黑名单只放会造成技术故障的工具（自指递归），不放"危险"工具 ——
 *    安全边界由 [isEnabled] 总开关负责，不做逐工具安全判断；
 *    真要按"危险"收窄，落点是**域**（见第 2 条），不是黑名单。
 * 4. 本类是同步阻塞 API，调用方必须在非主线程调用。
 * 5. 带回调号（cbId）的调用按 cbId 幂等：同一 cbId 在窗口内重复到达时，
 *    进行中则挂到同一个 worker 上等结果、已完成则直接回缓存结果，绝不第二次执行工具 ——
 *    超时后页面重试是常态，重复执行会造成重复拨号/重复安装等真实副作用。
 * 6. 限流与"要不要用户确认"不在这里判断，统一走 [ApprovalGate]（唯一审批入口）。
 */
object ToolGateway {

    private const val TAG = "ToolGateway"
    private const val PREFS = "aiui_tool_gateway"
    private const val KEY_ENABLED = "enabled"

        /** 能力发现：不是 ToolRegistry 里的真实工具，页面用它拿到"我现在能调什么" */
        const val LIST_TOOLS = "list_tools"

        /** 结果返回页面前的截断上限：RFCOMM 单帧上限 64KB，且页面也渲染不下超长文本 */
        private const val MAX_RESULT_CHARS = 8000

        /**
         * `list_tools` 能力发现清单的字符上限。
         *
         * ⚠️ 不能复用 [MAX_RESULT_CHARS]：注册表 80+ 工具，每条带完整中文描述约 150~300 字符，
         * 8000 只装得下前 ~30 条 —— 清单按声明顺序静默丢尾（files/phone/browser 域整段消失），
         * 生成方模型据此写页面会大量调「清单里没有」的工具。这个结果走 CXR Caps 下行
         * （同 `get_cover_image` 的 40K 通道），不受 RFCOMM 8000 约束。
         */
        private const val MAX_LIST_TOOLS_CHARS = 40_000

        /**
         * 按工具放宽的结果上限（默认 [MAX_RESULT_CHARS]）。
         *
         * 目前只有 `get_cover_image`：它返回封面图的 data URL，base64 后动辄 2~4 万字符，
         * 按 8000 截断会把图片数据砍成非法串（页面 `JSON.parse`/图片解码都失败）。
         * 上限受 RFCOMM 单帧 64KB 约束，取 40000 给 JSON 信封留足余量。
         */
        private val PER_TOOL_MAX_RESULT_CHARS = mapOf(
            "get_cover_image" to 40_000,
        )

    /** 单次调用超时。工具可能走外网（搜索/天气 1~3s），留足余量。
     *  眼镜端同步等待窗口（25s）必须比它大，否则本类给出的真实错误回不到页面。 */
    private const val CALL_TIMEOUT_MS = 15_000L

    /**
     * AIUI 页面路径的总预算（闭环审计 R7）。
     *
     * 页面确认等待已由 [com.rokidlab.phone.ai.approval.ApprovalGate] 压到 18s，但那只是
     * 一半 —— 若执行 join 仍按 [CALL_TIMEOUT_MS] 给满，确认 18s + 执行 15s = 33s 会
     * 超过眼镜端同步窗口（25s）：页面早已超时，工具却还在跑。这里把「确认+执行」的
     * **总时长**压进 23s（页窗 25s − 下行余量 2s）：确认耗掉多少，执行就只剩多少
     * （最少 [PAGE_MIN_JOIN_MS]）。超预算返回的「tool timed out」是手机侧的真实错误，
     * 能在页窗内回到页面，不再是眼镜端笼统的本地超时。
     */
    private const val PAGE_TOTAL_BUDGET_MS = 23_000L

    /** 页面路径执行 join 的保底下限：确认耗时接近上限时，至少给工具 2s 执行机会 */
    private const val PAGE_MIN_JOIN_MS = 2_000L

    /** 同一 cbId 的去重窗口：页面超时重试落在窗口内即命中，不再起第二个 worker */
    private const val DEDUPE_TTL_MS = 60_000L

    /** 去重表上限，超过后清扫已结束且过期的项，防止长期驻留 */
    private const val DEDUPE_MAX_ENTRIES = 64

    /**
     * **未结束**条目的强制淘汰阈值（2026-10-07 安全修复）。
     *
     * 旧实现在清扫时只删 `finished && 超 TTL` 的条目 ⇒ 工具 worker 永久挂住时，
     * 那条entry 永远不会被淘汰，而每个 [InFlight] 都持一个 Thread 引用 +
     * `AtomicReference<String>`（`get_cover_image` 的结果串可达 4 万字符）
     * ⇒ 页面只要对同一 cbId 反复调一个挂死的工具，就能让这个 map 无界增长。
     *
     * ⚠️ `DEDUPE_MAX_ENTRIES` 只是**触发清扫的阈值**，本身不是内存上限 ——
     * 「有个常量叫 MAX」很容易被误读成「最多 64 条」，所以这里必须显式兜底。
     * 取 3×TTL：正常工具执行远小于 1 分钟，3 倍 TTL 仍未结束基本等于卡死。
     */
    private const val DEDUPE_STUCK_MS = DEDUPE_TTL_MS * 3

    /**
     * 一次带 cbId 的工具执行：worker 与它的结果槽。
     * [finished] 由 worker 在 finally 置位，用于区分「尚未启动/正在跑」（join 等待）与「已完成」（回缓存）。
     */
    private class InFlight(
        val worker: Thread,
        val resultRef: AtomicReference<String?>,
        val errorRef: AtomicReference<String?>,
        val finished: AtomicBoolean = AtomicBoolean(false),
    ) {
        val createdAt: Long = System.currentTimeMillis()
    }

    private val dedupeMap = ConcurrentHashMap<String, InFlight>()

    private fun evictExpiredDedupe(now: Long) {
        if (dedupeMap.size <= DEDUPE_MAX_ENTRIES) return
        for ((k, v) in dedupeMap) {
            val age = now - v.createdAt
            // 已完成且过窗口：正常淘汰。未完成但远超 3×TTL：worker 卡死，强制淘汰（否则永驻）。
            // 用 remove(k, v) 做值匹配删除，不会误删并发者刚换上的新 entry。
            if ((v.finished.get() && age > DEDUPE_TTL_MS) || age > DEDUPE_STUCK_MS) {
                dedupeMap.remove(k, v)
            }
        }
    }

    /**
     * 页面可调用的工具域：全部开放。
     * 想收窄时改 [PageScope.ALLOWED_DOMAINS] 即可（这里是别名），不用动调用逻辑。
     */
    val ALLOWED_DOMAINS: Set<String> = PageScope.ALLOWED_DOMAINS

    /**
     * 例外：不开放给页面的工具（别名，唯一产地在 [PageScope.DENY_TOOLS]）。
     *
     * open_aiui_app 会让页面去打开一个 AIUI 应用 —— 页面很可能打开它自己，
     * 形成「启动 → 页面又启动」的自指递归，属于技术故障，不是安全问题。
     * 其余 AIUI 管理工具（install/stop/list）保留开放。
     */
    private val DENY_TOOLS: Set<String> = PageScope.DENY_TOOLS

    /** 工具摘要：下发给页面，也用于让生成方模型知道当前有什么能力 */
    data class ToolBrief(
        val name: String,
        val description: String,
        val domain: String,
    )

    /** 调用结果，直接序列化成 JSON 回传页面 */
    data class CallResult(
        val ok: Boolean,
        val name: String,
        val result: String? = null,
        val error: String? = null,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("ok", ok)
            put("name", name)
            if (result != null) put("result", result)
            if (error != null) put("error", error)
        }
    }

    /**
     * 总开关。全部域开放的前提下，这是唯一的安全兜底：
     * 出问题时可一键断开页面侧的全部工具能力，默认开启。
     */
    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, 0).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, 0).edit().putBoolean(KEY_ENABLED, enabled).apply()
        Log.i(TAG, "tool gateway enabled=$enabled")
    }

    /** 当前页面可调用的工具清单（描述取当前语言，供页面与生成方模型感知） */
    fun listAllowed(context: Context): List<ToolBrief> =
        ToolRegistry.toolList
            .filter { it.group in ALLOWED_DOMAINS && it.name !in DENY_TOOLS }
            // hidden 口径与设置页（visibleTools）对齐（R10）：系统性工具（自诊断/日志/会话读）
            // 是给**对话模型**用的自我认知工具，页面（第三方 .ink）没有资格看见与调用
            .filterNot { it.hidden }
            .map { ToolBrief(it.name, it.description(context), it.group) }

    /**
     * 清单的紧凑 JSON（页面 Lab.listTools() 用），避免页面自己拼装。
     *
     * 上限是 [MAX_LIST_TOOLS_CHARS]（远大于工具结果的 8000）：清单是**能力发现**通道，
     * 截尾会让生成方模型拿着残缺清单写页面。超长时仍**按条丢弃尾部工具**、并置
     * `"truncated": true`，绝不截断字符串：截断 + …(truncated) 会把 JSON 变成非法串，
     * 页面 `JSON.parse` 直接失败 —— 宁可少列，也必须保持合法 JSON。
     */
    fun listAllowedJson(context: Context): String {
        val arr = org.json.JSONArray()
        var used = "{\"tools\":[]}".length
        var truncated = false
        for (t in listAllowed(context)) {
            val obj = JSONObject().put("name", t.name).put("description", t.description)
            val cost = obj.toString().length + 1
            if (used + cost > MAX_LIST_TOOLS_CHARS) {
                truncated = true
                break
            }
            used += cost
            arr.put(obj)
        }
        val payload = JSONObject().put("tools", arr)
        if (truncated) payload.put("truncated", true)
        return payload.toString()
    }

    /**
     * 调用前置校验结果（v3.9 抽出为可测纯逻辑）。
     * [Reject.error] 与 [call] 原有错误消息逐字一致，勿改动（页面侧有按文案分支的逻辑）。
     */
    internal sealed class Precheck {
        /** 校验通过，[normalizedArgs] 为归一化后的 JSON 对象字符串 */
        data class Ok(val normalizedArgs: String) : Precheck()
        /** 校验拒绝，[error] 为回给页面的错误消息 */
        data class Reject(val error: String) : Precheck()
    }

    /**
     * 工具调用前置校验（纯函数，无 Android 依赖，可直接单测）。
     *
     * 依次校验：空名 → 黑名单 → 未知工具 → 域白名单 → 参数 JSON 合法性。
     * 注意 [LIST_TOOLS] 能力发现不在此处（它在 [call] 里更早分流）。
     */
    internal fun precheckToolCall(
        toolName: String,
        arguments: String,
        allowedDomains: Set<String> = ALLOWED_DOMAINS,
        denyTools: Set<String> = DENY_TOOLS,
        knownTools: Set<String> = ToolRegistry.toolList.map { it.name }.toSet(),
        toolDomain: (String) -> String? = { n -> ToolRegistry.toolList.firstOrNull { it.name == n }?.group },
    ): Precheck {
        if (toolName.isEmpty()) {
            return Precheck.Reject("empty tool name")
        }
        // 准入域判定（黑名单 → 未知工具 → 域白名单）：逻辑与文案的唯一产地是 [PageScope]，
        // 审批链里的 PageScopeGuard 用的是同一份 —— 「页面安全边界」只有一处可改。
        // 这里保留调用是为了让网关入口仍能挡住非法请求（避免白白跑一次 dedupe/审批）；
        // 不传 allowedDomains/denyTools 的默认参数时会与审批链完全一致。
        PageScope.rejectReason(
            toolName = toolName,
            knownTools = knownTools,
            toolDomain = toolDomain,
            allowedDomains = allowedDomains,
            denyTools = denyTools,
        )?.let { return Precheck.Reject(it) }
        // 参数必须是合法 JSON 对象：ToolRegistry.execute 内部直接 JSONObject(arguments)，
        // 传入非法串会在那里抛异常，页面侧只会看到一个无信息的超时。
        val normalizedArgs = try {
            if (arguments.isBlank()) "{}" else JSONObject(arguments).toString()
        } catch (e: Exception) {
            return Precheck.Reject("arguments is not valid JSON: ${e.message}")
        }
        return Precheck.Ok(normalizedArgs)
    }

    /**
     * 执行一个工具。同步返回，内部带超时保护。
     *
     * 必须在非主线程调用：[ToolRegistry.execute] 内部有网络/文件 IO。
     *
     * @param arguments JSON 对象字符串，可为空（按 {} 处理）
     * @param cbId 页面侧回调号。非空时本方法按 cbId 幂等：同一 cbId 的重复调用
     *   （典型来源：页面超时后重试）进行中则挂到同一个 worker 等结果、
     *   已完成则直接回缓存结果，不会第二次执行工具。留空则不做去重（一次性调用场景）。
     */
    fun call(context: Context, name: String, arguments: String, cbId: String = ""): CallResult {
        val startedAt = System.currentTimeMillis()
        val toolName = name.trim()
        if (!isEnabled(context)) {
            return CallResult(false, toolName, error = "tool gateway is disabled")
        }
        // 能力发现走同一条通道，页面不必额外学一套协议：
        // 结果就是 listAllowedJson，页面 JSON.parse 后得到 {tools:[{name,description}]}
        // 超长由 listAllowedJson 自己按条截断（保证仍是合法 JSON），**不要**再过 truncateResult ——
        // 那会在 JSON 中间切断并追加 "…(truncated)"，页面解析必然失败。
        if (toolName == LIST_TOOLS) {
            return CallResult(true, toolName, result = listAllowedJson(context))
        }

        val normalizedArgs: String = when (val pre = precheckToolCall(toolName, arguments)) {
            is Precheck.Reject -> return CallResult(false, toolName, error = pre.error)
            is Precheck.Ok -> pre.normalizedArgs
        }

        // 幂等闸门必须在风险闸门之前：页面超时重试只是同一次调用的重放，
        // 若先过 ApprovalGate 会白白再消耗一次 30/min 限流额度（后续正常调用被误拒）。
        val dedupeKey = cbId.trim()
        if (dedupeKey.isNotEmpty()) {
            val now = System.currentTimeMillis()
            dedupeMap[dedupeKey]?.let { prev ->
                if (now - prev.createdAt <= DEDUPE_TTL_MS) {
                    Log.i(TAG, "tool $toolName: cbId=$dedupeKey seen before (finished=${prev.finished.get()}), reuse")
                    return awaitResult(prev, toolName, duplicate = true, timeoutMs = joinBudgetMs(startedAt))
                }
                dedupeMap.remove(dedupeKey, prev)
            }
        }

        val appContext = context.applicationContext
        val resultRef = AtomicReference<String?>(null)
        val errorRef = AtomicReference<String?>(null)
        val finished = AtomicBoolean(false)
        val worker = Thread({
            try {
                resultRef.set(ToolRegistry.execute(appContext, toolName, normalizedArgs))
            } catch (e: Throwable) {
                errorRef.set(e.message ?: e.javaClass.simpleName)
                // 回给页面的只有 message，堆栈必须留在 App 内日志面板，否则「工具失败」无真因可查
                LogCollector.e(TAG, "AIUI 页面工具执行异常: $toolName", e)
            } finally {
                // 必须 finally 置位：异常路径也要让等待方知道「跑完了」
                finished.set(true)
            }
        }, "aiui-tool-$toolName")
        val entry = InFlight(worker, resultRef, errorRef, finished)

        // 先占幂等坑、再过审批：审批可能阻塞最长 ~40s（眼镜确认 35s / 手机 40s），
        // 期间页面用同一 cbId 重试必须命中占位 entry（等待/回「进行中」），
        // 而不是再跑一遍 preExecute —— 那会重复扣限流额度、并对用户弹第二次确认框
        //（两个线程各建 InFlight 只靠 putIfAbsent 才没双重执行，语义靠竞态运气）。
        if (dedupeKey.isNotEmpty()) {
            // putIfAbsent 而非「先查后写」：两个同 cbId 请求并发到达时，
            // 后者会拿到先到者的 entry 并挂上去等结果，绝不退化成本地第二次执行工具。
            val raced = dedupeMap.putIfAbsent(dedupeKey, entry)
            if (raced != null) {
                Log.i(TAG, "tool $toolName: cbId=$dedupeKey concurrent duplicate, join in-flight call")
                // 本 worker 从未 start，直接丢弃，避免重复副作用
                return awaitResult(raced, toolName, duplicate = true, timeoutMs = joinBudgetMs(startedAt))
            }
            evictExpiredDedupe(System.currentTimeMillis())
        }

        // 审批闸门（唯一判定点，见 ApprovalGate）：per-source 限流 + EXTERNAL_SIDE_EFFECT 眼镜端确认。
        // fail-open：无确认通道 / 眼镜端旧版 / 用户超时未响应 → 降级放行；
        // 只有用户在眼镜上显式取消才拒绝。
        //
        // 页面域/黑名单在上面的 precheck 已经拦过一遍，这里的 PageScopeGuard 会再判一次 ——
        // 是幂等的纯判定（读内存表），保留它是为了"绕过网关直接调闸门"时策略仍然生效。
        val decision = try {
            ApprovalGate.preExecute(
                ToolSource.AIUI_PAGE,
                toolName,
                JSONObject(normalizedArgs),
                // 读工具开关：页面调用的工具同样受设置页开关约束（否则关掉的工具仍能从页面被调起）
                context = appContext,
            )
        } catch (e: Throwable) {
            // 审批本身炸了必须撤占位：否则 TTL 内该 cbId 的重试全都挂在一个永不启动的 worker 上
            if (dedupeKey.isNotEmpty()) dedupeMap.remove(dedupeKey, entry)
            throw e
        }
        if (decision is ToolDecision.Deny) {
            // 撤占位：被拒的调用不占坑，后续重试重新走完整判定（而不是等到 TTL 过期）
            if (dedupeKey.isNotEmpty()) dedupeMap.remove(dedupeKey, entry)
            return CallResult(false, toolName, error = decision.reason)
        }

        worker.isDaemon = true
        worker.start()
        return awaitResult(entry, toolName, duplicate = false, timeoutMs = joinBudgetMs(startedAt))
    }

    /**
     * 本次调用的 join 预算：本网关只服务页面路径（[com.rokidlab.phone.ai.approval.ToolSource.AIUI_PAGE]），
     * 预算 = [PAGE_TOTAL_BUDGET_MS] − 已耗时（审批等待占掉的部分），下限 [PAGE_MIN_JOIN_MS]、
     * 上限 [CALL_TIMEOUT_MS]。对话路径不走本网关。
     */
    private fun joinBudgetMs(startedAt: Long): Long =
        (PAGE_TOTAL_BUDGET_MS - (System.currentTimeMillis() - startedAt))
            .coerceIn(PAGE_MIN_JOIN_MS, CALL_TIMEOUT_MS)

    /**
     * 等待一次已登记的调用结束，并按统一口径转成 [CallResult]。
     *
     * [duplicate] 区分首次调用与重复调用：两者都在超时后返回错误，
     * 但错误语义不同 —— 首次是「超时」，重复是「上一次仍在跑」（提示页面别再造并发）。
     *
     * [timeoutMs] 由调用方按来源预算传入（页面路径 = 动态预算，见 [joinBudgetMs]）。
     */
    private fun awaitResult(entry: InFlight, toolName: String, duplicate: Boolean, timeoutMs: Long): CallResult {
        entry.worker.join(timeoutMs)
        if (!entry.finished.get()) {
            // 超时后不 interrupt：工具可能持有文件/网络资源，强中断会留下半写状态，
            // 让线程自己跑完（daemon 线程不阻塞进程退出）。
            return if (duplicate) {
                Log.w(TAG, "tool $toolName duplicate call, previous still running")
                LogCollector.w(TAG, "工具 $toolName 重复调用，上一次仍在执行（页面 15s 重试触发）")
                CallResult(false, toolName, error = "tool '$toolName' is already in progress")
            } else {
                Log.w(TAG, "tool $toolName timed out after ${timeoutMs}ms")
                LogCollector.w(TAG, "工具 $toolName 执行超时 ${timeoutMs}ms（线程继续跑完，不中断）")
                CallResult(false, toolName, error = "tool '$toolName' timed out")
            }
        }

        entry.errorRef.get()?.let {
            Log.w(TAG, "tool $toolName failed: $it")
            LogCollector.e(TAG, "工具 $toolName 失败: $it")
            return CallResult(false, toolName, error = it)
        }

        var result = entry.resultRef.get() ?: ""
        // 大结果通道（R13）：超限的截断产物语义已坏 —— 封面图的 base64 被砍半张、
        // 页面 JSON.parse/图片解码双双失败且无真因可查。与其下行一个必炸的 payload，
        // 不如显式失败并留下证据（普通工具仍走 truncateResult 的语义截断，不变）。
        val toolLimit = PER_TOOL_MAX_RESULT_CHARS[toolName]
        if (toolLimit != null && result.length > toolLimit) {
            LogCollector.w(
                TAG,
                "工具 $toolName 结果 ${result.length} 字符超过大结果通道上限 $toolLimit，已拒绝下行（R13）",
            )
            return CallResult(false, toolName, error = "result of '$toolName' is too large for the glasses downlink")
        }
        result = truncateResult(toolName, result)
        Log.i(TAG, "tool $toolName ok, ${result.length} chars")
        return CallResult(true, toolName, result = result)
    }

    /**
     * 结果截断：RFCOMM 单帧上限 64KB，且页面也渲染不下超长文本。
     *
     * 不能直接 `take(n)` —— 结果可能含 emoji/生僻字（UTF-16 代理对），
     * 从代理对中间切断会留下孤立代理项，下行 JSON 里的结果字符串就变非法，
     * 页面 `JSON.parse` 直接失败，表现为 "bad result: …" 而非工具错误，极难排查。
     * 这里检测切点若落在高代理项之后，就少截一个字符让代理对完整落在截断之外。
     * （internal 供单测直测；错误文案/截断标记"…(truncated)"被页面按字面感知，勿改动。）
     */
    internal fun truncateResult(toolName: String, result: String): String {
        val limit = PER_TOOL_MAX_RESULT_CHARS[toolName] ?: MAX_RESULT_CHARS
        if (result.length <= limit) return result
        var end = limit
        if (Character.isHighSurrogate(result[end - 1])) end -= 1
        return result.substring(0, end) + "…(truncated)"
    }
}
