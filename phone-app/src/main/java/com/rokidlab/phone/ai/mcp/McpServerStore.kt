package com.rokidlab.phone.ai.mcp

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 一个外部 MCP server 的用户配置。
 *
 * @param id        本地生成的稳定标识（工具名前缀 `mcp__<id>__…` 用它），一旦创建不再变
 * @param name      用户看得见的名字（工具在设置页显示为「名字 · 工具名」）
 * @param url       Streamable HTTP 端点：**https，或回环 http**（`http://127.0.0.1` / `localhost` / `::1`）。
 *                  release 包默认禁止明文，但回环在 `res/xml/network_security_config.xml` 的白名单里、
 *                  且两份变体行为一致 —— 所以"自建本地 MCP"这条调试路径是通的，
 *                  判定**只看** [McpRegistry.isUrlAllowed]，别在这里另写一份 https 检查（会把它堵死）。
 * @param headers   附加请求头（鉴权 token 等）。⚠️ 明文存盘、不得写进日志，见 [McpServerStore]
 * @param enabled   server 级总开关（关掉 ⇒ 它的工具全部不下发）
 * @param trusted   信任标记：true ⇒ 工具风险档降为 `LOCAL_SIDE_EFFECT`（免确认）；
 *                  false ⇒ `EXTERNAL_SIDE_EFFECT`
 * @param addedAt   添加时间（用户排序/展示用）
 */
internal data class McpServerConfig(
    val id: String,
    val name: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val enabled: Boolean = true,
    val trusted: Boolean = false,
    val addedAt: Long = 0L,
)

/**
 * MCP server 配置的持久化（`files/mcp_servers.json`）。
 *
 * 照 `store/ChatSessionStore` 的既有做法：写在 App 内部 `filesDir`。
 *
 * ⚠️ **不要**用 `Environment.getExternalStoragePublicDirectory` —— 那条路在 Android 10+
 * 需要分区存储适配，项目里已明确踩过并放弃（见 memory 的记录）。
 *
 * ⚠️ **headers 是明文存盘**（可能含第三方 server 的 token）。第一版接受这一点，
 * 因为：① 它存在 App 私有目录，非 root 不可读；② 加密需要引入 keystore 方案、
 * 且用户仍要手输一次。但**必须**保证它不被写进日志 —— [McpRegistry] 侧打错误日志时
 * 只打 URL 不打 headers。
 */
internal object McpServerStore {

    private const val TAG = "McpServerStore"
    private const val FILE = "mcp_servers.json"

    private fun fileOf(ctx: Context): File = File(ctx.filesDir, FILE)

    /** 读全部配置；文件缺失或损坏一律返回空表（**不抛异常**，配置坏掉不该让 App 起不来） */
    fun load(ctx: Context): List<McpServerConfig> {
        val f = fileOf(ctx)
        if (!f.isFile) return emptyList()
        return runCatching {
            val root = JSONObject(f.readText())
            val arr = root.optJSONArray("servers") ?: JSONArray()
            (0 until arr.length()).mapNotNull { i -> parse(arr.optJSONObject(i)) }
        }.onFailure { Log.w(TAG, "load failed, treating as empty: ${it.message}") }
            .getOrDefault(emptyList())
    }

    /** 覆盖写全部配置 */
    fun save(ctx: Context, list: List<McpServerConfig>) {
        runCatching {
            val arr = JSONArray()
            list.forEach { arr.put(toJson(it)) }
            fileOf(ctx).writeText(JSONObject().put("servers", arr).toString())
        }.onFailure { Log.w(TAG, "save failed: ${it.message}") }
    }

    /** 新增或按 [id] 覆盖一条，返回更新后的全表 */
    fun upsert(ctx: Context, cfg: McpServerConfig): List<McpServerConfig> {
        val cur = load(ctx)
        val next = cur.filterNot { it.id == cfg.id } + cfg
        save(ctx, next)
        return next
    }

    /** 删除一条，返回更新后的全表 */
    fun remove(ctx: Context, id: String): List<McpServerConfig> {
        val next = load(ctx).filterNot { it.id == id }
        save(ctx, next)
        return next
    }

    private fun parse(o: JSONObject?): McpServerConfig? {
        if (o == null) return null
        val id = o.optString("id")
        val url = o.optString("url")
        if (id.isBlank() || url.isBlank()) return null
        val headersJson = o.optJSONObject("headers")
        val headers = buildMap {
            headersJson?.keys()?.forEach { k ->
                val v = headersJson.optString(k)
                if (k.isNotBlank()) put(k, v)
            }
        }
        return McpServerConfig(
            id = id,
            name = o.optString("name").ifBlank { url },
            url = url,
            headers = headers,
            enabled = o.optBoolean("enabled", true),
            trusted = o.optBoolean("trusted", false),
            addedAt = o.optLong("addedAt", 0L),
        )
    }

    private fun toJson(c: McpServerConfig): JSONObject {
        // headers 显式逐个 put（而不是 $JSONObject(Map)）：后者遇到 null 值会抛异常，
        // 而配置来自用户输入，将来加字段很容易带进 null —— 那时是"保存静默失败"，最难查。
        val headersJson = JSONObject()
        c.headers.forEach { (k, v) -> headersJson.put(k, v) }
        return JSONObject()
            .put("id", c.id)
            .put("name", c.name)
            .put("url", c.url)
            .put("headers", headersJson)
            .put("enabled", c.enabled)
            .put("trusted", c.trusted)
            .put("addedAt", c.addedAt)
    }
}
