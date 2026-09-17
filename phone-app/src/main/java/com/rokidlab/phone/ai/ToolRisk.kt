package com.rokidlab.phone.ai

import android.util.Log

/**
 * L4 agent/ToolRisk —— 工具风险分级（架构文档 §3.5，对应 ARCHITECTURE_REVIEW 的 P0 建议）。
 *
 * 全部工具按副作用分三档；新增工具时必须在 [ToolRiskMap] 登记。
 *
 * ⚠️ 历史事故（务必读）：`save_code_file` / `read_code_file` 曾漏登记，
 * 而当时的兜底策略是「未登记 → 最保守档 [EXTERNAL_SIDE_EFFECT]」，
 * 于是 AIUI 代码生成工具被要求「眼镜端用户确认」→ 确认通道不可用即被拒绝 →
 * **AIUI 生成整条链路直接失败**，用户侧表现为「提示眼镜没权限 / 没反应」。
 * 现在兜底改为「真实工具漏登记 → 按 LOCAL_SIDE_EFFECT 放行 + 高声告警」，
 * 让漏登记表现为可观测的告警，而不是静默瘫痪功能。
 */
enum class ToolRisk {
    /** 纯读取：查询 / 搜索类，无副作用 */
    READ_ONLY,

    /** 本机副作用：改手机/眼镜本地状态（设置音量、定时器、安装 AIUI 等），影响可控或可撤销 */
    LOCAL_SIDE_EFFECT,

    /** 外部副作用：触达第三方、不可撤销的动作 —— 必须经过 [ToolPolicy] 确认闸门 */
    EXTERNAL_SIDE_EFFECT,
}

/** 工具名 → 风险档位的唯一登记处（与 ToolRegistry.toolList 的名字保持一致） */
object ToolRiskMap {

    private val map = mapOf(
        // ── READ_ONLY ──
        "search_knowledge_base" to ToolRisk.READ_ONLY,
        "read_code_file" to ToolRisk.READ_ONLY,
        "get_current_time" to ToolRisk.READ_ONLY,
        "get_glasses_battery" to ToolRisk.READ_ONLY,
        "get_glasses_device_info" to ToolRisk.READ_ONLY,
        "get_glasses_storage" to ToolRisk.READ_ONLY,
        "list_glasses_apps" to ToolRisk.READ_ONLY,
        "list_timers" to ToolRisk.READ_ONLY,
        "search_web" to ToolRisk.READ_ONLY,
        "fetch_webpage" to ToolRisk.READ_ONLY,
        "list_my_aiui_apps" to ToolRisk.READ_ONLY,
        "get_weather" to ToolRisk.READ_ONLY,
        "calculate" to ToolRisk.READ_ONLY,
        "search_contacts" to ToolRisk.READ_ONLY,
        "get_phone_status" to ToolRisk.READ_ONLY,
        "get_location" to ToolRisk.READ_ONLY,
        "query_calendar" to ToolRisk.READ_ONLY,
        // 只读当前播放信息（歌名/封面/歌词），不改任何状态；AIUI 播放器页面靠它取素材
        "get_now_playing" to ToolRisk.READ_ONLY,
        // 只读：在手机侧下载当前歌曲封面并压缩成 data URL 回传页面，不改任何状态
        "get_cover_image" to ToolRisk.READ_ONLY,
        // 自我认知域：自检状态与读日志都是纯读取，不应触发任何确认闸门
        "get_agent_status" to ToolRisk.READ_ONLY,
        "read_recent_logs" to ToolRisk.READ_ONLY,
        // 跨会话检索：只读落盘聊天历史并打分排序，不改任何状态
        "search_past_conversations" to ToolRisk.READ_ONLY,

        // ── LOCAL_SIDE_EFFECT ──
        "launch_glasses_app" to ToolRisk.LOCAL_SIDE_EFFECT,
        "set_timer" to ToolRisk.LOCAL_SIDE_EFFECT,
        "cancel_timer" to ToolRisk.LOCAL_SIDE_EFFECT,
        // 自主定时任务：在本机注册一条「到点让 Agent 自己跑一轮推理」的调度（可取消），
        // 属本机可控副作用；它触发的**那一轮**推理另由 ToolRegistry.schemasReadOnly 限死在只读工具
        "schedule_agent_task" to ToolRisk.LOCAL_SIDE_EFFECT,
        "play_song" to ToolRisk.LOCAL_SIDE_EFFECT,
        "stop_music" to ToolRisk.LOCAL_SIDE_EFFECT,
        "show_lyrics" to ToolRisk.LOCAL_SIDE_EFFECT,
        // 屏幕展示域：把图片渲染到手机对话气泡并下推眼镜悬浮层（本机/本链路副作用，可撤销）
        "show_image" to ToolRisk.LOCAL_SIDE_EFFECT,
        "save_summary_txt" to ToolRisk.LOCAL_SIDE_EFFECT,
        "open_aiui_app" to ToolRisk.LOCAL_SIDE_EFFECT,   // 同时在 ToolGateway.DENY_TOOLS（自指递归，技术故障）
        "install_aiui_project" to ToolRisk.LOCAL_SIDE_EFFECT,
        // AIUI 代码生成：只在手机私有目录写项目文件，属本机可撤销副作用（事故补登记）
        "save_code_file" to ToolRisk.LOCAL_SIDE_EFFECT,
        "stop_aiui_app" to ToolRisk.LOCAL_SIDE_EFFECT,
        "set_phone_alarm" to ToolRisk.LOCAL_SIDE_EFFECT,
        "open_phone_app" to ToolRisk.LOCAL_SIDE_EFFECT,
        "set_phone_volume" to ToolRisk.LOCAL_SIDE_EFFECT,
        "add_calendar_event" to ToolRisk.LOCAL_SIDE_EFFECT,
        // 放弃未完成任务：只清本机的任务续做记录（不删任何已生成文件），可撤销且无外部影响
        "clear_agent_task" to ToolRisk.LOCAL_SIDE_EFFECT,

        // ── 拨号：LOCAL_SIDE_EFFECT ──
        // 2026-09-11 用户明确要求：说「给 10086 拨打电话」要**手机直接拨打出去**，
        // **不要**眼镜端确认（确认层既不可靠、也让功能看起来"被安全策略挡住"）。
        // 降级依据：① 拨出动作作用在用户自己的手机上，不触达第三方平台（不同于推送/上传类）；
        // ② 用户的口头指令「给 X 打电话」本身就是明确授权；③ 仍需已授 CALL_PHONE 才直拨，
        //    未授权/被 ROM 限制时自动退回打开拨号盘（人工按拨出）。
        "call_phone" to ToolRisk.LOCAL_SIDE_EFFECT,
    )

    private const val TAG = "ToolRiskMap"

    /**
     * 查询风险档位。
     *
     * 未登记时按名字是否真实存在于 [ToolRegistry.toolList] 分流：
     *  - **真实工具漏登记** → [ToolRisk.LOCAL_SIDE_EFFECT] + 高声告警。
     *    宁可让用户用不了也就不该用不了：漏登记是**开发疏漏**，不该瘫痪功能
     *    （历史上 `save_code_file` 漏登记就是这样把整条 AIUI 生成链路拦死的）。
     *  - **完全未知的名字**（模型幻觉/攻击构造）→ [ToolRisk.EXTERNAL_SIDE_EFFECT]，
     *    保持最保守：必须经过确认闸门，且随后也会因 ToolRegistry 查不到而失败。
     */
    fun riskOf(name: String): ToolRisk {
        map[name]?.let { return it }
        val isRealTool = runCatching { ToolRegistry.toolList.any { it.name == name } }.getOrDefault(false)
        return if (isRealTool) {
            Log.e(TAG, "未登记风险档位的真实工具 '$name' → 暂按 LOCAL_SIDE_EFFECT 放行；请补登记 ToolRiskMap")
            ToolRisk.LOCAL_SIDE_EFFECT
        } else {
            ToolRisk.EXTERNAL_SIDE_EFFECT
        }
    }

    /** 供诊断/自检：列出「真实工具但未登记风险」的名字（应为空） */
    fun unregisteredTools(): List<String> =
        runCatching { ToolRegistry.toolList.map { it.name }.filter { it !in map }.sorted() }
            .getOrDefault(emptyList())
}
