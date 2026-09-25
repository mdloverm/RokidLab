package com.rokidlab.phone.ai

import android.util.Log
import com.rokidlab.phone.util.HttpClient
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URLEncoder

/**
 * URL 技能包解析下载层（「填写下载链接」入口）。
 *
 * 支持四类链接（大厂技能生态常见形态）：
 *   1. `*.zip` 直链                 → 下载 zip 交给 [SkillRegistry.installFromZip]
 *   2. `*.md` / raw 单文件直链      → 下载文本交给 [SkillRegistry.installFromMarkdown]
 *   3. GitHub 仓库页/目录页          → API 列出 skills 目录下的 SKILL.md → 逐个拉 raw
 *      - https://github.com/{owner}/{repo}/(tree|blob)/{branch}/skills 或仓库根
 *      - https://raw.githubusercontent.com/{owner}/{repo}/{branch}/skills/xxx/SKILL.md
 *   4. Gitee 仓库页/目录页（国内优先）
 *      - https://gitee.com/{owner}/{repo}/(tree|blob)/{branch}/skills
 *      - https://gitee.com/{owner}/{repo}/raw/{branch}/skills/xxx/SKILL.md
 *
 * 解析安全：仓库页仅返回候选 SKILL.md 的 raw 下载地址，不信任路径命名；
 * 技能内容下载后统一走 [SkillRegistry.installFromMarkdown] 的 frontmatter 校验收尾，
 * 技能名取自 frontmatter name 而非路径。
 */
object SkillFetcher {
    private const val TAG = "SkillFetcher"

    /** 仓库页最多采集的技能文件数（防仓库过大拉取过多） */
    private const val MAX_CANDIDATES = 20

    /** 仓库页解析出的单个技能候选（raw 下载地址） */
    data class RepoCandidate(
        val rawUrl: String,
        val filePath: String,
    )

    /** 链接分类 */
    enum class Kind { ZIP, MD_FILE, REPO_PAGE, UNKNOWN }

    /**
     * 纯函数：根据 URL 判断链接类型（可单测）。
     * ZIP 直链：以 .zip 结尾；MD：以 .md 结尾或 host 为 raw/gitee raw；
     * 仓库页：github.com / gitee.com 域名下的 tree/blob/仓库根路径。
     */
    fun classify(url: String): Kind {
        val clean = url.trim()
        if (clean.isEmpty()) return Kind.UNKNOWN
        val lower = clean.lowercase()
        // GitHub/Gitee 网页 blob/tree 页（含指向 .md 文件页）→ 仓库页解析；
        // gitee 的 /raw/ 直链与 raw.githubusercontent.com 由下方 .md/.zip 分支接管
        val host = runCatching { java.net.URI(clean).host?.lowercase() }.getOrNull()
        if (host == "github.com" || host == "gitee.com") {
            val segs = runCatching { java.net.URI(clean).path.orEmpty() }
                .getOrNull()?.split('/')?.filter { it.isNotBlank() }.orEmpty()
            if (segs.size > 3 && (segs[2] == "tree" || segs[2] == "blob")) return Kind.REPO_PAGE
        }
        return when {
            lower.contains(".zip") -> Kind.ZIP
            lower.contains(".md") -> Kind.MD_FILE
            isRepoHost(clean) -> Kind.REPO_PAGE
            else -> Kind.UNKNOWN
        }
    }

    /** 是否为 GitHub/Gitee 仓库页域名（github.com / gitee.com，排除 raw 子域由文件分支接管） */
    private fun isRepoHost(url: String): Boolean {
        val host = runCatching { java.net.URI(url.trim()).host?.lowercase() }.getOrNull()
        return host == "github.com" || host == "gitee.com"
    }

    /**
     * 解析仓库页 URL → 提取候选 SKILL.md 文件列表（需要网络，IO 线程调用）。
     * 候选选择按 URL 指向精确化：
     *   - 指向某 .md 文件（blob）→ 只取该文件；
     *   - 指向某目录 → 优先取目录内 SKILL.md（技能入口约定），否则取目录全部 md；
     *   - 指向仓库根 → 扫描 skills/ 规范目录下的技能。
     * 避免「URL 给了子目录却扫描整个仓库 skills/」装到无关技能。
     *
     * @return 空列表表示未找到；异常向上抛出由调用方提示
     */
    fun resolveRepoPage(url: String): List<RepoCandidate> {
        val parsed = parseRepoUrl(url) ?: return emptyList()
        val apiBase = if (parsed.gitee) "https://gitee.com/api/v5" else "https://api.github.com"
        // 无 branch 时先查默认分支
        val branch = parsed.branch
            ?: queryDefaultBranch(apiBase, parsed.owner, parsed.repo, parsed.gitee)
        if (branch.isNullOrBlank()) return emptyList()

        // tree?recursive=1 一次拉全仓库文件清单
        val treeUrl = "$apiBase/repos/${parsed.owner}/${parsed.repo}/git/trees/" +
            URLEncoder.encode(branch, "UTF-8") + "?recursive=1"
        val headers = if (parsed.gitee) emptyMap() else mapOf(
            "Accept" to "application/vnd.github+json",
            "User-Agent" to "RokidLab/1.0",
        )
        val resp = runCatching { HttpClient.getString(treeUrl, headers = headers) }
            .getOrElse { e ->
                Log.w(TAG, "tree api failed for $url: ${e.message}")
                return emptyList()
            }
        val json = runCatching { JSONObject(resp) }.getOrNull() ?: return emptyList()
        val tree = json.optJSONArray("tree") ?: return emptyList()

        // 收集所有 .md 文件路径
        val mdPaths = mutableListOf<String>()
        for (i in 0 until tree.length()) {
            val node = tree.optJSONObject(i) ?: continue
            val path = node.optString("path")
            if (node.optString("type") != "blob") continue
            if (!path.endsWith(".md", ignoreCase = true)) continue
            if (isTestOrVendorPath(path)) continue
            mdPaths.add(path)
        }

        // 候选选择策略（按 URL 指向精确化，避免装到仓库其它无关技能）：
        // 1. URL 直指某个 .md 文件（blob 页）→ 只取该文件；
        // 2. URL 指向目录 → 优先取该目录下的 SKILL.md（技能入口约定），没有则取该目录全部 md；
        // 3. URL 指向仓库根/分支 → 走 skills/ 规范目录扫描。
        val sub = parsed.subPath?.trim('/')
        val candidates = when {
            sub != null && sub.endsWith(".md", ignoreCase = true) ->
                if (sub in mdPaths) listOf(sub) else emptyList()
            !sub.isNullOrBlank() -> {
                val entry = "$sub/SKILL.md"
                if (entry in mdPaths) listOf(entry)
                else mdPaths.filter { it.startsWith("$sub/") }.take(MAX_CANDIDATES)
            }
            else -> mdPaths
                .filter { it.contains("/skills/") || it.startsWith("skills/") || it.contains("/skill/") }
                .take(MAX_CANDIDATES)
        }
        return candidates.map { path ->
            RepoCandidate(
                rawUrl = buildRawUrl(parsed, branch, path),
                filePath = path,
            )
        }
    }

    private fun isTestOrVendorPath(path: String): Boolean {
        val lower = path.lowercase()
        return lower.contains("/test/") || lower.contains("/tests/") ||
            lower.contains("/node_modules/") || lower.contains("/.git/") ||
            lower.contains("/vendor/") || lower.contains("/example/")
    }

    /** 组 raw 下载地址（GitHub 用 raw.githubusercontent.com，Gitee 用 /raw/） */
    private fun buildRawUrl(repo: RepoInfo, branch: String, path: String): String {
        val seg = path.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
        return if (repo.gitee) {
            "https://gitee.com/${repo.owner}/${repo.repo}/raw/$branch/$seg"
        } else {
            "https://raw.githubusercontent.com/${repo.owner}/${repo.repo}/$branch/$seg"
        }
    }

    /** 查询仓库默认分支（github /gitee repos API） */
    private fun queryDefaultBranch(
        apiBase: String,
        owner: String,
        repo: String,
        gitee: Boolean,
    ): String? {
        val headers = if (gitee) emptyMap() else mapOf("User-Agent" to "RokidLab/1.0")
        val resp = runCatching {
            HttpClient.getString("$apiBase/repos/$owner/$repo", headers = headers)
        }.getOrNull() ?: return null
        return runCatching { JSONObject(resp).optString("default_branch").takeIf { it.isNotBlank() } }
            .getOrNull()
    }

    internal data class RepoInfo(
        val gitee: Boolean,
        val owner: String,
        val repo: String,
        val branch: String?,   // 网页 URL 中 tree/blob 后的分支（可空=默认分支）
        val subPath: String?,  // tree/blob 分支后的子目录
    )

    /**
     * 解析 GitHub/Gitee 仓库页 URL（纯函数，可单测）。
     * 支持：
     *   {host}/{owner}/{repo}                          （仓库根）
     *   {host}/{owner}/{repo}/tree/{branch}[/{sub}]    （目录/文件页）
     *   {host}/{owner}/{repo}/blob/{branch}/{sub}      （文件页）
     */
    internal fun parseRepoUrl(url: String): RepoInfo? {
        val clean = url.trim()
        val uri = runCatching { java.net.URI(clean) }.getOrNull() ?: return null
        val host = uri.host?.lowercase() ?: return null
        val gitee = host == "gitee.com"
        if (!gitee && host != "github.com") return null
        val segs = uri.path?.split('/')?.filter { it.isNotBlank() } ?: return null
        if (segs.size < 2) return null
        val owner = segs[0]
        val repo = segs[1].removeSuffix(".git")
        if (segs.size == 2) return RepoInfo(gitee, owner, repo, null, null)
        val verb = segs[2]
        return when (verb) {
            "tree", "blob" -> {
                if (segs.size < 4) RepoInfo(gitee, owner, repo, null, null)
                else RepoInfo(gitee, owner, repo, segs[3], segs.drop(4).joinToString("/"))
            }
            else -> RepoInfo(gitee, owner, repo, null, null)  // 其它路径按仓库根处理
        }
    }

    /**
     * 下载单个技能文件（raw / md 直链 / zip 直链）。
     * 按后缀分流：.zip → 字节（交给 installFromZip）；其它按 md 文本返回。
     * @return 下载结果封装：kind + bytes 或 text
     */
    fun download(url: String): DownloadResult {
        val bytes = downloadBytes(url)
        return if (classify(url) == Kind.ZIP) {
            DownloadResult.Zip(bytes)
        } else {
            DownloadResult.Markdown(String(bytes, Charsets.UTF_8))
        }
    }

    /** 下载字节并限流（仓库 raw 单文件最大 512KB，纯文本说明书足够） */
    private fun downloadBytes(
        url: String,
        connectTimeoutMs: Int = 10000,
        readTimeoutMs: Int = 30000,
    ): ByteArray {
        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        try {
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "RokidLab/1.0")
            val code = conn.responseCode
            if (code !in 200..299) throw java.io.IOException("HTTP $code")
            val input = conn.inputStream
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            var total = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > 512L * 1024) throw java.io.IOException("文件超过 512KB 限制")
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        } finally {
            conn.disconnect()
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 官方 aiui-dev 技能同步（yodaos-project/AIUI）
    // 说明：官方 skill = SKILL.md + 参考手册的多文件结构；拉取仅覆盖官方自有文件，
    // 本地 lab-runtime.md 等配套文件由调用方决定不写，天然保留。
    // ═══════════════════════════════════════════════════════════

    /**
     * 官方 aiui-dev 文件清单：本地平铺文件名 → 上游仓库相对路径（当前 main 分支结构）。
     *
     * ⚠️ 上游 2026-09 起把参考文档拆进 references/ 子目录（apis/ 按域分册），而本端
     * seedBundledSkills / load_skill 只认一层平铺 .md —— 故以映射表拍平：
     * 拉取用上游路径，落盘/展示用本地名。本地新增配套文件（lab-runtime.md）不在此列，
     * 由 SkillRegistry.LOCAL_COMPANION_SKILL_FILES 管辖。
     */
    private val OFFICIAL_AIUI_DEV_FILES: Map<String, String> = mapOf(
        "SKILL.md" to "SKILL.md",
        "framework.md" to "references/framework.md",
        "events.md" to "references/events.md",
        "checklist.md" to "references/checklist.md",
        "components.md" to "references/components.md",
        "wxss.md" to "references/wxss.md",
        "design-system-green.md" to "references/design/monochrome-green.md",
        // ── APIs（apis.md = 官方 references/apis/index.md 索引；其余按域分册）──
        "apis.md" to "references/apis/index.md",
        "apis-framework.md" to "references/apis/framework.md",
        "apis-ai.md" to "references/apis/ai.md",
        "apis-canvas.md" to "references/apis/canvas.md",
        "apis-device.md" to "references/apis/device.md",
        "apis-media.md" to "references/apis/media.md",
        "apis-web.md" to "references/apis/web.md",
        "apis-wx.md" to "references/apis/wx.md",
        "apis-navigator.md" to "references/apis/navigator.md",
        "apis-window.md" to "references/apis/window.md",
        "apis-widget.md" to "references/apis/widget.md",
        "apis-agent-worker.md" to "references/apis/agent-worker.md",
    )

    /** 官方 aiui-dev 技能仓库信息 */
    private const val OFFICIAL_OWNER = "yodaos-project"
    private const val OFFICIAL_REPO = "AIUI"
    private const val OFFICIAL_BRANCH = "main"
    private const val OFFICIAL_DIR = "skills/aiui-dev"

    /**
     * 拉取官方 aiui-dev 技能目录全部 .md 文件（IO 线程调用）。
     * 每个文件先走 jsdelivr CDN（国内可达性好），失败切 raw.githubusercontent 兜底；
     * 单个文件两源都失败则跳过（不影响其它文件）。
     *
     * @return 文件名 → 原始文本（不含本地配套文件，如 lab-runtime.md）
     */
    fun fetchOfficialAiuiDevFiles(): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for ((localName, upstreamPath) in OFFICIAL_AIUI_DEV_FILES) {
            val sources = listOf(
                "https://cdn.jsdelivr.net/gh/$OFFICIAL_OWNER/$OFFICIAL_REPO@$OFFICIAL_BRANCH/$OFFICIAL_DIR/$upstreamPath",
                "https://raw.githubusercontent.com/$OFFICIAL_OWNER/$OFFICIAL_REPO/$OFFICIAL_BRANCH/$OFFICIAL_DIR/$upstreamPath",
            )
            var ok = false
            for (src in sources) {
                try {
                    result[localName] = String(downloadBytes(src, 8000, 12000), Charsets.UTF_8)
                    ok = true
                    break
                } catch (e: Exception) {
                    Log.w(TAG, "fetch official $localName failed via $src: ${e.message}")
                }
            }
            if (!ok) Log.w(TAG, "fetchOfficialAiuiDevFiles: 官方文件 $localName 两个源均下载失败")
        }
        return result
    }

    sealed class DownloadResult {
        data class Zip(val bytes: ByteArray) : DownloadResult()
        data class Markdown(val text: String) : DownloadResult()
    }
}
