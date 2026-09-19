package com.rokidlab.phone.ai.tools

import com.rokidlab.phone.ai.ToolRegistry
import com.rokidlab.phone.ai.ToolRegistry.ToolCategory
import com.rokidlab.phone.ai.ToolRisk
import org.json.JSONObject

/**
 * 一个工具的**完整自声明**（工具接缝的核心类型）。
 *
 * 背景：改造前，新增/修改一个工具要在 **6 张表**里分别登记 ——
 * `ToolRegistry.toolList`（元数据）/ `ToolSchemas.kt`（给模型的 JSON Schema）/
 * `ToolRiskMap`（风险档）/ `SIDE_EFFECT_TOOLS`（失败是否可重试）/
 * `GLASSES_REQUIRED_TOOLS`（本机模式是否摘除）/ `statusText` + `GlassToolConfirmChannel.summarize`
 * （两处文案）。任何一处漏登记都**不编译错、不抛异常**，只会让功能静默失效
 * （`list_glasses_apps` 曾被正则区间替换静默删掉，编译通过、226 条单测全绿）。
 *
 * 现在这 6 张表全部由 [ToolEntry] **派生**：工具由所属的 [ToolProvider] 自己声明，
 * `ToolRegistry` 只做聚合与分发。新增工具 = 在对应 provider 的 `tools()` 里加一条，不再有"漏登记"这回事。
 *
 * 设计取舍：字段全部内联在这一个 data class 里，而不是拆成多个注册面 ——
 * 因为**单一结构体**才能保证"要么全填、要么编译错"；分散登记正是旧方案的病根。
 *
 * @param name           工具名（下发给模型的 function name，全局唯一）
 * @param group          装配域（[ToolRegistry.DOMAIN_*]）：会话按场景声明要装配的域集合
 * @param displayNameRes 设置页显示名（多语言资源）
 * @param descriptionRes 设置页描述（多语言资源，会经 ToolGateway 下发给 AIUI 页面）
 * @param category       设置页分类（用户视角）；null = 由 [group] 推导
 * @param hidden         true = 设置页不展示，**但仍会下发给模型**（系统性/内部工具的惯例）
 * @param risk           风险档，驱动 [com.rokidlab.phone.ai.approval.ApprovalGate] 的确认闸门与无人值守准入
 * @param sideEffect     true = 有不可逆副作用（拨号/装机/建日程…），失败**不重试**
 * @param requiresGlasses true = 没有眼镜就做不成，乐奇聊天「本机模式」下整条摘除
 * @param statusText     过程时间线文案（眼镜端显示 + 手机端状态栏共用）
 * @param summarize      副作用工具的眼镜端确认摘要；null = 用兜底文案「执行操作 <name>」
 * @param schema         直接传给 OpenAI 兼容协议 `tools` 参数的声明（`{"type":"function",…}`）
 */
internal data class ToolEntry(
    val name: String,
    val group: String,
    val displayNameRes: Int,
    val descriptionRes: Int,
    val category: ToolCategory? = null,
    val hidden: Boolean = false,
    val risk: ToolRisk,
    val sideEffect: Boolean = false,
    val requiresGlasses: Boolean = false,
    val statusText: String? = null,
    val summarize: ((JSONObject) -> String)? = null,
    val schema: JSONObject,
)
