package com.rokidlab.phone.ai.tools

import android.content.Context
import com.rokidlab.phone.ai.ToolContentTrust
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
 * @param dynamicName        动态显示名；非空则优先于 [displayNameRes]（动态工具没有编译期资源 ID）
 * @param dynamicDescription 动态描述；非空则优先于 [descriptionRes]，⚠️ 同样会经 ToolGateway 下发 AIUI 页面
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
    /**
     * 动态显示名：非空则**优先于** [displayNameRes]。
     *
     * 为什么需要这两个字段：MCP 这类工具是**运行时**从 server 拿到的，没有编译期资源 ID，
     * 而 [displayNameRes] / [descriptionRes] 的类型是 `Int`（资源 ID），表达不了动态文案。
     * 静态工具两个字段保持 null，行为与改造前**完全一致**（取值逻辑里资源 ID 是兜底）。
     */
    val dynamicName: String? = null,
    /**
     * 动态描述：非空则优先于 [descriptionRes]。
     *
     * ⚠️ 与 [descriptionRes] 一样会经 `ToolGateway.listAllowedJson()` **下发到 AIUI 页面** ——
     * 改这里不算纯 UI 改动，AIUI 页面侧会一起看到新文案。
     */
    val dynamicDescription: String? = null,
    val category: ToolCategory? = null,
    val hidden: Boolean = false,
    val risk: ToolRisk,
    val sideEffect: Boolean = false,
    val requiresGlasses: Boolean = false,
    /**
     * 结果内容的信任级别（**防提示词注入**）。
     *
     * 默认 [ToolContentTrust.TRUSTED]（设备本地确定性数据）；凡是把**外部作者写的内容**
     * 带回模型的工具（网页/第三方 MCP/拍照 OCR/子助手调研/知识库文档原文）必须声明为
     * [ToolContentTrust.UNTRUSTED_EXTERNAL]，结果回填时由
     * [com.rokidlab.phone.ai.UntrustedContent] 统一包进 `<untrusted_source>` 隔离段。
     *
     * 与 [risk] 正交：risk 管「这个动作能不能做」，contentTrust 管「这份输出能不能当指令信」。
     */
    val contentTrust: ToolContentTrust = ToolContentTrust.TRUSTED,
    val statusText: String? = null,
    val summarize: ((JSONObject) -> String)? = null,
    val schema: JSONObject,
) {
    /**
     * 面向用户的显示名：**动态优先、资源兜底**（null 的 [dynamicName] ⇒ 走 [displayNameRes]）。
     *
     * 与设置页的 `ToolMeta.displayName` 是**同一份数据的两种载体** —— [ToolMeta] 由本类经
     * `ToolRegistry.toMeta()` 派生，两个字段逐一同值、公式也相同，所以"用户在设置页里见过的名字"
     * 与"聊天过程卡片里显示的名字"必然一致，不会分叉。
     *
     * 被 `ToolRegistry.displayNameOf()` 消费（把模型发来的 wire name 换成这个名字）；要用同样的
     * 名字请走那个入口，别在调用点自己拼 `dynamicName ?: getString(...)`。
     */
    fun displayName(ctx: Context): String = dynamicName ?: ctx.getString(displayNameRes)

    /**
     * 面向用户的描述：**动态优先、资源兜底**（[dynamicDescription] 非空则无视 [descriptionRes]）。
     *
     * 与 [displayName] 同一套取值公式。消费方：`ToolGateway.listAllowedJson()` 下发给 AIUI 页面 /
     * 生成方模型的能力清单 —— 动态工具（MCP）没有编译期资源 ID，若这里回落到 [descriptionRes]，
     * 页面与模型只会看到占位文案「MCP」（而真实工具描述其实已经在 [dynamicDescription] 里）。
     */
    fun description(ctx: Context): String = dynamicDescription ?: ctx.getString(descriptionRes)
}
