package com.rokidlab.phone.ai

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * AIUI 应用（.aix 智能体卡片）记录注册表 —— 手机端唯一事实源。
 *
 * 每条记录对应一个可在眼镜上渲染的 AIUI 应用，来源有三类：
 *  - [ORIGIN_GENERATED]：对话中 save_code_file 生成 + install_aiui_project 打包直传（关联项目源码目录，可再编辑/更新重装）；
 *  - [ORIGIN_UPLOADED]：管理页本地 .aix 上传（无源码目录，不可再编辑，只能重新上传）；
 *  - [ORIGIN_LEGACY]：从旧 aiui_registry.json / 内置兜底迁移而来（如官方“我是黑客”）。
 *
 * 持久化在 filesDir/aiui_apps.json，App 重启不丢；供 AIUI 管理页、list_my_aiui_apps
 * 语音工具、open/stop 匹配共同使用。open_aiui_app 所需的 AgentDef 由 [toAgentDef] 派生。
 */
object AiuiAppRegistry {
    private const val TAG = "AiuiAppRegistry"
    private const val STORE_FILE = "aiui_apps.json"
    private const val LEGACY_FILE = "aiui_registry.json"

    const val ORIGIN_GENERATED = "generated"
    const val ORIGIN_UPLOADED = "uploaded"
    const val ORIGIN_LEGACY = "legacy"

    /** 内置兜底 agent（官方 AgentStore 已安装，走旧 Ai_RenderPayload 链路，aixOnGlasses=false） */
    private val BUILTIN = listOf(
        AiuiAppRecord(
            appName = "我是黑客",
            agentId = "5aac922daa854dcd9ae77d9556c764f2",
            origin = ORIGIN_LEGACY,
            aixOnGlasses = false,
        ),
    )

    @Volatile
    private var cache: List<AiuiAppRecord>? = null

    /** 一条 AIUI 应用记录 */
    data class AiuiAppRecord(
        val appName: String,
        val agentId: String,
        /** save_code_file 的 project 名（再编辑时复用）；本地上传/旧记录为 null */
        val project: String? = null,
        val pageName: String = "pages/index/index",
        val nativeVersion: String = "0.0.74",
        /** 手机端源码目录绝对路径（generated）；其余为 null */
        val sourceProjectDir: String? = null,
        /** generated / uploaded / legacy */
        val origin: String = ORIGIN_GENERATED,
        /** .aix 是否已直传眼镜 cxr 目录（true 走 Sys_AIUI_Start 直启） */
        val aixOnGlasses: Boolean = false,
        val createdAt: Long = 0L,
        val updatedAt: Long = 0L,
    )

    /** 全部记录（按更新时间倒序：最新在前） */
    fun list(context: Context): List<AiuiAppRecord> = load(context).sortedByDescending { it.updatedAt }

    fun getByAgentId(context: Context, agentId: String): AiuiAppRecord? =
        load(context).firstOrNull { it.agentId == agentId }

    fun getByProject(context: Context, project: String): AiuiAppRecord? =
        load(context).firstOrNull { it.project == project }

    /**
     * 按 agentId 幂等 upsert：已存在则保留 createdAt、刷新 updatedAt 并合并可变字段；
     * 不存在则新增（补 createdAt/updatedAt）。返回落盘后的记录。
     */
    fun upsert(context: Context, record: AiuiAppRecord): AiuiAppRecord {
        val now = System.currentTimeMillis()
        val current = load(context)
        val idx = current.indexOfFirst { it.agentId == record.agentId }
        val merged = if (idx >= 0) {
            val old = current[idx]
            record.copy(
                createdAt = if (old.createdAt > 0) old.createdAt else now,
                updatedAt = now,
                // 项目名/源码目录：新记录非空才覆盖，避免上传/旧记录把 generated 的关联冲掉
                project = record.project ?: old.project,
                sourceProjectDir = record.sourceProjectDir ?: old.sourceProjectDir,
            )
        } else {
            record.copy(
                createdAt = if (record.createdAt > 0) record.createdAt else now,
                updatedAt = now,
            )
        }
        val next = if (idx >= 0) current.toMutableList().also { it[idx] = merged } else current + merged
        persist(context, next)
        Log.i(TAG, "upsert: ${merged.appName} agentId=${merged.agentId} origin=${merged.origin} project=${merged.project}")
        return merged
    }

    /** 删除一条记录（仅删注册表项；文件清理由调用方负责）。返回是否删除成功 */
    fun remove(context: Context, agentId: String): Boolean {
        val current = load(context)
        val next = current.filterNot { it.agentId == agentId }
        if (next.size == current.size) return false
        persist(context, next)
        Log.i(TAG, "remove: agentId=$agentId")
        return true
    }

    // ---- 持久化 ----

    private fun load(context: Context): List<AiuiAppRecord> {
        cache?.let { return it }
        synchronized(this) {
            cache?.let { return it }
            val f = File(context.filesDir, STORE_FILE)
            val records = if (!f.isFile) {
                migrate(context).also { persist(context, it) }
            } else {
                runCatching { parse(f.readText()) }.getOrElse {
                    Log.w(TAG, "store file corrupted, reset: ${it.message}")
                    emptyList()
                }
            }
            cache = records
            return records
        }
    }

    private fun persist(context: Context, records: List<AiuiAppRecord>) {
        runCatching {
            val arr = JSONArray()
            records.forEach { r ->
                arr.put(JSONObject().apply {
                    put("appName", r.appName)
                    put("agentId", r.agentId)
                    put("project", r.project ?: JSONObject.NULL)
                    put("pageName", r.pageName)
                    put("nativeVersion", r.nativeVersion)
                    put("sourceProjectDir", r.sourceProjectDir ?: JSONObject.NULL)
                    put("origin", r.origin)
                    put("aixOnGlasses", r.aixOnGlasses)
                    put("createdAt", r.createdAt)
                    put("updatedAt", r.updatedAt)
                })
            }
            File(context.filesDir, STORE_FILE).writeText(JSONObject().put("apps", arr).toString(), Charsets.UTF_8)
            cache = records
        }.onFailure { Log.e(TAG, "persist failed", it) }
    }

    private fun parse(text: String): List<AiuiAppRecord> {
        val arr = JSONObject(text).optJSONArray("apps") ?: return emptyList()
        val out = ArrayList<AiuiAppRecord>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val agentId = o.optString("agentId").takeIf { it.isNotEmpty() } ?: continue
            out.add(
                AiuiAppRecord(
                    appName = o.optString("appName", agentId),
                    agentId = agentId,
                    project = if (o.isNull("project")) null else o.optString("project").takeIf { it.isNotEmpty() },
                    pageName = o.optString("pageName", "pages/index/index"),
                    nativeVersion = o.optString("nativeVersion", "0.0.74"),
                    sourceProjectDir = if (o.isNull("sourceProjectDir")) null else o.optString("sourceProjectDir").takeIf { it.isNotEmpty() },
                    origin = o.optString("origin", ORIGIN_LEGACY),
                    aixOnGlasses = o.optBoolean("aixOnGlasses", false),
                    createdAt = o.optLong("createdAt", 0L),
                    updatedAt = o.optLong("updatedAt", 0L),
                ),
            )
        }
        return out
    }

    /**
     * 首次迁移：旧 aiui_registry.json 的 agents[] + 内置兜底 → 新记录。
     * 旧结构只有 name/agentId/nativeVersion/pageName/aixOnGlasses，无 project/时间/来源，统一标 legacy。
     */
    private fun migrate(context: Context): List<AiuiAppRecord> {
        val out = LinkedHashMap<String, AiuiAppRecord>()
        // 内置兜底
        BUILTIN.forEach { out[it.agentId] = it.copy(createdAt = 0L, updatedAt = 0L) }
        // 旧注册表
        runCatching {
            val f = File(context.filesDir, LEGACY_FILE)
            if (f.isFile) {
                val arr = JSONObject(f.readText()).optJSONArray("agents")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val agentId = o.optString("agentId").takeIf { it.isNotEmpty() } ?: continue
                        out[agentId] = AiuiAppRecord(
                            appName = o.optString("name", agentId),
                            agentId = agentId,
                            pageName = o.optString("pageName", "pages/index/index"),
                            nativeVersion = o.optString("nativeVersion", "0.0.74"),
                            origin = ORIGIN_LEGACY,
                            aixOnGlasses = o.optBoolean("aixOnGlasses", false),
                        )
                    }
                }
            }
        }.onFailure { Log.w(TAG, "migrate read legacy failed: ${it.message}") }
        Log.i(TAG, "migrate: ${out.size} records (legacy + builtin)")
        return out.values.toList()
    }
}
