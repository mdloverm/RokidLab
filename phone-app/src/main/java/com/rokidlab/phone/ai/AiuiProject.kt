package com.rokidlab.phone.ai

import android.content.Context
import android.util.Log
import com.rokidlab.phone.app.LabApplication
import com.rokidlab.phone.connection.ConnectionRoute
import com.rokidlab.phone.util.namedThread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * AIUI agent（.aix）项目打包与本地托管。
 *
 * 背景：用户通过对话让 Agent 生成 AIUI 项目（save_code_file 逐个写文件，
 * 私有镜像在 filesDir/aiui_projects/<project>/），本模块把该项目目录打包成
 * .aix（本质是 zip：VERSION / app.json / app.js / AGENTS.md / pages 目录）。
 *
 * 下发/打开新链路（2026-09 打通）：不再让眼镜走官方下载安装（AgentStore /
 * AddNativeAgent 会被 REMOTE_SYNC purge），而是把 .aix 直传眼镜开发者
 * WebServer 落盘 device-protected files/aiui/package/cxr/<原名>.aix，
 * 再由 CXR Sys 通道 Sys_AIUI_Start 让官方 AiuiPackageManager 直接渲染。
 * [hostAix] 及其下载目录逻辑保留仅作历史/兜底参考。
 */
object AiuiProject {
    private const val TAG = "AiuiProject"

    /** 眼镜开发者 WebServer（AssistServer 内 WebServerService）固定监听端口 */
    private const val GLASSES_WEB_PORT = 8848

    /** 打包结果 */
    data class AixResult(
        val agentId: String,
        val aixFile: File,
        val fileMd5: String,
        val pageName: String,
        val projectName: String,
    )

    /** 托管结果：.aix 下载地址 + 供眼镜同步用的 agent 目录地址（agents.json） */
    data class HostedAix(
        val aixUrl: String,
        val catalogUrl: String,
    )

    /** 项目私有镜像根目录（save_code_file 每次写文件时同步镜像） */
    fun projectDir(context: Context, project: String): File =
        File(context.filesDir, "aiui_projects/${sanitizeName(project)}")

    /** .aix 输出目录 */
    private fun packageDir(context: Context): File =
        File(context.filesDir, "aiui_packages").apply { mkdirs() }

    /** 某 agentId 对应的本地 .aix 包文件（aiui_packages/<agentId>.aix） */
    fun packageFile(context: Context, agentId: String): File =
        File(packageDir(context), "$agentId.aix")

    /** 全部本机 AIUI 项目名（有 app.json 才算一个项目；含**还没打包送到眼镜**的） */
    fun localProjects(context: Context): List<String> {
        val root = File(context.filesDir, "aiui_projects")
        if (!root.isDirectory) return emptyList()
        return root.listFiles()
            ?.filter { it.isDirectory && File(it, "app.json").isFile }
            ?.map { it.name }
            ?.sorted()
            ?: emptyList()
    }

    /**
     * 项目展示名：AGENTS.md 的首个一级标题（生成时写的就是应用名），没有就用项目名。
     *
     * 为什么不读 app.json：它只有 pages/window 这类路由与样式配置，**没有名字字段**
     * （见 aiui-dev 技能 §1.2）。
     */
    fun projectDisplayName(context: Context, project: String): String {
        val md = File(projectDir(context, project), "AGENTS.md")
        val title = runCatching { md.takeIf { it.isFile }?.readText() }
            .getOrNull()
            ?.lineSequence()
            ?.firstOrNull { it.trim().startsWith("# ") }
            ?.trim()
            ?.removePrefix("#")
            ?.trim()
        return title?.takeIf { it.isNotEmpty() } ?: project
    }

    /**
     * 按名字找本机项目（供「刚生成、还没送到眼镜」时在手机上演示）。
     * 展示名 / 项目名精确 → 双向包含。
     *
     * ⚠️ 刻意**不做**"本机只有一个项目就用它"的兜底：用户说"打开天气查询"（内置官方智能体）时，
     * 那样会莫名其妙打开他刚做的那个项目。名字对不上就如实报没有。
     */
    fun matchLocalProject(context: Context, appName: String): String? {
        val q = appName.trim()
        if (q.isEmpty()) return null
        val qLower = q.lowercase()
        val projects = localProjects(context)
        projects.firstOrNull { it.equals(q, ignoreCase = true) }?.let { return it }
        projects.firstOrNull { projectDisplayName(context, it).equals(q, ignoreCase = true) }?.let { return it }
        return projects.firstOrNull {
            val d = projectDisplayName(context, it).lowercase()
            val p = it.lowercase()
            p.contains(qLower) || qLower.contains(p) || d.contains(qLower) || qLower.contains(d)
        }
    }

    /**
     * 本地 .aix 文件名 → 合法 agentId（眼镜 cxr 目录文件名 / Sys_AIUI_Start 包名）。
     * 去 .aix 后缀并清洗非法字符；为空则回退 UUID（眼镜按文件名落盘与启动）。
     */
    fun agentIdFromFileName(fileName: String): String {
        val base = fileName.trim().substringBeforeLast('.', fileName)
        return sanitizeName(base).ifEmpty { UUID.randomUUID().toString() }
    }

    private fun sanitizeName(name: String): String =
        name.trim().replace(Regex("[^\\w\\-\\u4e00-\\u9fa5]"), "_")

    /**
     * 校验单个 .ink 页面是否满足 AIUI SFC 规范，防止渲染空白/黑屏。
     *
     * 规范四块（见 aiui-dev skill）：`<script def>`（页面级 JSON 配置）、
     * `<script setup>`（export default 逻辑）、`<page>`（WXML 模板根）、`<style>`。
     * 引擎只认 `<page>` 作模板根；若模型写成 Vue 风格 `<template>`/`<script>`，
     * 渲染层无法解析页面 → 只剩 window 背景色（黑屏，真机实测 17:01 复现）。
     *
     * @return null = 合规；否则为错误原因（可直接拼进工具回复让模型重写）
     */
    fun validateInkContent(content: String): String? {
        val hasDef = Regex("<script\\s+def(\\s|>)").containsMatchIn(content)
        val hasSetup = Regex("<script\\s+setup(\\s|>)").containsMatchIn(content)
        val hasPage = Regex("<page(\\s|>)").containsMatchIn(content)
        val hasTemplate = Regex("<template(\\s|>)").containsMatchIn(content)
        // 裸 <script>（无 def/setup 属性）会被引擎当作普通脚本执行，
        // export default 无法识别为页面对象 → Exported default must be an object（真机实测）
        val hasBareScript = Regex("<script\\s*>").containsMatchIn(content) ||
            Regex("<script>").containsMatchIn(content)
        return when {
            hasTemplate && !hasPage ->
                "不能使用 <template> 作为页面根标签（这是 Vue 写法）。" +
                    "请按 AIUI .ink 四块结构重写：<script def>（页面配置 JSON）+ <script setup>（export default 逻辑）+ <page>（WXML 模板根）+ <style>（样式）"
            !hasPage ->
                "缺少 <page>…</page> 模板根块。请按 AIUI .ink 四块结构重写：" +
                    "<script def>（页面配置 JSON）+ <script setup>（export default 逻辑）+ <page>（WXML 模板根）+ <style>（样式）"
            !hasSetup ->
                "缺少 <script setup> 逻辑块（必须 export default 页面对象，data/onLoad/setData 都写在这里）。" +
                    "注意不能用裸 <script>（引擎不认，会报 Exported default must be an object），标签必须带 setup 属性"
            hasBareScript ->
                "页面里出现了不带属性的裸 <script> 块（引擎会把它当普通脚本执行，export default 无法导出页面对象）。" +
                    "请把逻辑写到 <script setup> 里（export default 页面对象），不要使用裸 <script>"
            !hasDef ->
                "缺少 <script def> 页面配置块（JSON：navigationBarTitleText 等）。请补上 <script def>…</script>"
            else -> null
        }
    }

    /**
     * 打包前整体校验项目是否满足 AIUI 规范（源文件 → .aix 的守门员）。
     *
     * 覆盖：项目镜像存在、app.json 可解析且 pages 含入口、pages 下全部 .ink
     * 均符合 SFC 规范。不合规时返回可直接给模型/用户的具体错误，让其在
     * 工具循环中重写对应文件，而不是把坏页面打进 .aix 再黑屏。
     *
     * @return null = 可打包；否则为错误原因
     */
    fun validateAiuiProject(context: Context, project: String): String? {
        val src = projectDir(context, project)
        if (!src.isDirectory) {
            return "项目“$project”还没生成完整（私有镜像缺失）。请先用“保存代码文件”工具生成完整 AIUI 项目（至少 app.json 与 pages/index/index.ink）"
        }
        val appJson = File(src, "app.json")
        if (!appJson.isFile) return "项目“$project”缺少 app.json（页面清单），无法打包。请先用“保存代码文件”工具生成"
        val json = runCatching { JSONObject(appJson.readText()) }.getOrNull()
            ?: return "项目“$project”的 app.json 不是合法 JSON，请检查后重新生成"
        val pages = json.optJSONArray("pages")
        if (pages == null || pages.length() == 0) {
            return "项目“$project”的 app.json 缺少 pages 页面清单（须含 \"pages/index/index\"），请修正"
        }
        // 逐元素匹配入口页（org.json 的 toString 存在分隔符/转义差异风险，不用 contains）
        val hasIndex = (0 until pages.length()).any { i ->
            pages.optString(i).trim().trim('"').let { it == "pages/index/index" || it.endsWith("/index") }
        }
        Log.i(TAG, "validateAiuiProject: project=$project app.json pages=${pages.toString()} hasIndex=$hasIndex")
        if (!hasIndex) {
            return "项目“$project”的 app.json 的 pages 未包含入口页 \"pages/index/index\"，请修正"
        }
        val pagesDir = File(src, "pages")
        val inkFiles = if (!pagesDir.isDirectory) emptyList() else
            pagesDir.walkTopDown().filter { it.isFile && it.name.endsWith(".ink") }.toList()
        if (inkFiles.isEmpty()) {
            return "项目“$project”缺少页面文件（pages/…/*.ink），请先用“保存代码文件”工具生成"
        }
        for (f in inkFiles) {
            val rel = f.relativeTo(src).path.replace('\\', '/')
            val err = validateInkContent(runCatching { f.readText() }.getOrDefault(""))
            if (err != null) return "项目“$project”的 $rel 不符合 AIUI 页面规范：$err"
        }
        return null
    }

    /**
     * 把项目目录打包为 .aix。
     * 补缺省：VERSION 缺失时生成 UUID；app.json 缺失时报错（页面清单是必需）；
     * 要求存在 pages/index/index.ink（agent 首页入口，打开负载默认指向它）。
     *
     * @return null = 打包失败（原因见日志）
     */
    fun buildAix(context: Context, project: String, appName: String): AixResult? {
        val src = projectDir(context, project)
        if (!src.isDirectory) {
            Log.w(TAG, "buildAix: project dir missing: ${src.absolutePath}")
            return null
        }
        // 打包前整体合规校验（含所有 .ink 的 <page> 根块检查），
        // 防止 Vue 风格 <template> 页面被打进 .aix → 眼镜渲染空白黑屏
        val projectErr = validateAiuiProject(context, project)
        if (projectErr != null) {
            Log.w(TAG, "buildAix: project invalid: $projectErr")
            return null
        }

        val agentIdFile = File(src, ".rokid_agent_id")
        val agentId: String = runCatching { agentIdFile.readText().trim() }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?: UUID.randomUUID().toString().also { fresh ->
                runCatching { agentIdFile.writeText(fresh) }
            }
        // AGENTS.md 可选，缺失时补一个默认说明，避免包不完整
        if (!File(src, "AGENTS.md").isFile) {
            runCatching {
                File(src, "AGENTS.md").writeText("# $appName\n\n对话生成的 AIUI 智能体应用。\n", Charsets.UTF_8)
            }
        }
        // app.js 可选，缺失时补最小生命周期钩子
        if (!File(src, "app.js").isFile) {
            runCatching {
                File(src, "app.js").writeText(
                    "export default {\n  onLaunch() { console.log('$appName - Launch'); },\n  onShow() { console.log('$appName - Show'); },\n  onHide() { console.log('$appName - Hide'); },\n};\n",
                    Charsets.UTF_8,
                )
            }
        }
        // VERSION 绝不能写 LLM 可能产生的任意 UUID（曾实测写入官方内置 agentId
        // cxr-card=1dc902f4-...，眼镜会把它当 REMOTE_SYNC 官方条目 purge 掉）。
        // 同时 agentId 固定不变会让同名覆盖包在眼镜端永远 “Version match,
        // skipping extraction”，修复内容从不被引擎加载。故 VERSION 改为基于
        // 打包内容（排除 VERSION/.rokid_*）的内容指纹（uuid 形状）：内容变→
        // 指纹变→引擎重新解压 runtime 缓存；内容不变→指纹幂等（idempotent）。
        runCatching { File(src, "VERSION").writeText(contentVersion(src)) }

        val aix = File(packageDir(context), "$agentId.aix")
        runCatching {
            aix.delete()
            ZipOutputStream(aix.outputStream().buffered()).use { zos ->
                src.walkTopDown().filter { it.isFile }.forEach { f ->
                    // 排除我方内部 sidecar（agentId 真相源，不打进包）
                    if (f.name.startsWith(".rokid_")) return@forEach
                    val entryName = f.relativeTo(src).path.replace('\\', '/')
                    zos.putNextEntry(ZipEntry(entryName))
                    f.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
        }.onFailure {
            Log.e(TAG, "buildAix: zip failed", it)
            return null
        }
        Log.i(TAG, "buildAix ok: agentId=$agentId size=${aix.length()} -> ${aix.absolutePath}")
        return AixResult(
            agentId = agentId,
            aixFile = aix,
            fileMd5 = md5Hex(aix),
            pageName = "pages/index/index",
            projectName = project,
        )
    }

    /**
     * 极简 HTTP server：托管 .aix 供下载（支持 Range 断点续传，供下载器 resume），
     * 并在 /agents.json 暴露「native agent 目录」供眼镜 JsaiAiuiHostProvider 同步
     * （眼镜把该 URL 当作 agentListUrl：拉到的条目即视为合法 REMOTE_SYNC 包，
     * 不会被周期 syncAgentList 取消/purge，且会自动下载列表内 url 指向的 .aix）。
     */
    private class AixHttpServer(
        private val file: File,
        private val meta: CatalogMeta,
    ) {
        /** 目录条目元数据（用于拼 agents.json；url 端口在 server 起好后可知） */
        data class CatalogMeta(
            val agentId: String,
            val agentName: String,
            val agentDesc: String,
            val fileMd5: String,
            val nativeVersion: String,
            val inkVersion: String,
        )

        private val serverSocket = ServerSocket(0, 8, InetAddress.getByName("0.0.0.0"))
        val port: Int get() = serverSocket.localPort

        private val running = AtomicReference(true)
        private val thread = namedThread("aiui-project-io") {
            val buf = ByteArray(64 * 1024)
            while (running.get()) {
                val socket = try {
                    serverSocket.accept()
                } catch (e: Exception) {
                    if (running.get()) Log.e(TAG, "server accept error", e)
                    break
                }
                try {
                    socket.use { s ->
                        s.soTimeout = 15_000
                        val req = s.getInputStream().bufferedReader().readLine()?.trim() ?: return@use
                        // 仅处理 GET，其余回 501
                        val parts = req.split(" ")
                        if (parts.size < 2 || parts[0] != "GET") {
                            writeText(s, "501 Not Implemented", "text/plain", "")
                            return@use
                        }
                        val rawPath = parts[1].substringBefore('?').substringBefore('#')
                        val basename = rawPath.trim('/').substringAfterLast('/')
                        var rangeFrom = 0L
                        var rangeTo = file.length() - 1
                        var hasRange = false
                        // 读剩余请求头，识别 Range: bytes=from-to
                        while (true) {
                            val header = s.getInputStream().bufferedReader().readLine()?.trim() ?: break
                            if (header.isEmpty()) break
                            if (header.startsWith("Range:", ignoreCase = true)) {
                                val m = Regex("[^\\d]*(\\d*)\\s*-\\s*(\\d*)").find(header.substringAfter(':'))
                                if (m != null) {
                                    hasRange = true
                                    m.groupValues[1].toLongOrNull()?.let { rangeFrom = it }
                                    m.groupValues[2].toLongOrNull()?.let { rangeTo = it }
                                }
                            }
                        }
                        when {
                            // 眼镜同步目录：JsaiAiuiHostProvider 取顶层 data 数组
                            basename == "agents.json" ->
                                writeText(s, "200 OK", "application/json; charset=utf-8", catalogJson())
                            // .aix 下载（支持 Range 续传）
                            basename == file.name || basename.endsWith(".aix") -> {
                                if (hasRange) {
                                    writeResponse(s, "206 Partial Content", "application/octet-stream", file, rangeFrom, rangeTo)
                                } else {
                                    writeResponse(s, "200 OK", "application/octet-stream", file, 0, file.length() - 1)
                                }
                            }
                            else -> writeText(s, "404 Not Found", "text/plain", "not found")
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "server request error", e)
                }
            }
        }.apply { isDaemon = true }

        fun start() {
            thread.start()
            Log.i(TAG, "AixHttpServer listening on port $port for ${file.name}")
        }

        fun stop() {
            running.set(false)
            runCatching { serverSocket.close() }
        }

        /** 眼镜同步目录 JSON：顶层 data 数组（extractAgentArrayFromResponse 优先取 data） */
        private fun catalogJson(): String {
            val base = "http://127.0.0.1:$port"
            val entry = JSONObject()
                .put("modelVendor", "native") // 必须命中眼镜 ALLOWED_MODEL_VENDORS={native,cut}
                .put("agentId", meta.agentId)
                .put("agentName", meta.agentName)
                .put("agentDesc", meta.agentDesc)
                .put("agentLogo", "")
                .put("url", "$base/${file.name}")
                .put("filePath", "")
                .put("nativeVersion", meta.nativeVersion)
                .put("inkVersion", meta.inkVersion)
                .put("fileMd5", meta.fileMd5)
                .put("permissions", JSONArray())
            return JSONObject()
                .put("code", 1)
                .put("msg", "SUCCESS")
                .put("data", JSONArray().put(entry))
                .toString()
        }

        /** 纯文本/JSON 响应（agents.json、错误页） */
        private fun writeText(socket: java.net.Socket, status: String, contentType: String, body: String) {
            val bytes = body.toByteArray(Charsets.UTF_8)
            val out = socket.getOutputStream()
            val head = StringBuilder()
                .append("HTTP/1.1 ").append(status).append("\r\n")
                .append("Content-Type: ").append(contentType).append("\r\n")
                .append("Content-Length: ").append(bytes.size).append("\r\n")
                .append("Connection: close\r\n\r\n")
            out.write(head.toString().toByteArray())
            out.write(bytes)
            out.flush()
        }

        private fun writeResponse(
            socket: java.net.Socket,
            status: String,
            contentType: String,
            bodyFile: File?,
            from: Long,
            to: Long,
        ) {
            val out = socket.getOutputStream()
            val length = if (bodyFile != null && to >= from) (to - from + 1) else 0L
            val head = StringBuilder()
                .append("HTTP/1.1 ").append(status).append("\r\n")
                .append("Content-Type: ").append(contentType).append("\r\n")
                .append("Accept-Ranges: bytes\r\n")
                .append("Content-Length: ").append(length).append("\r\n")
                .append("Connection: close\r\n\r\n")
            out.write(head.toString().toByteArray())
            if (bodyFile != null && length > 0) {
                bodyFile.inputStream().use { ins ->
                    ins.skip(from)
                    var remaining = length
                    val buf = ByteArray(64 * 1024)
                    while (remaining > 0) {
                        val n = ins.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        remaining -= n
                    }
                }
            }
            out.flush()
        }
    }

    /** 当前运行的托管 server（进程内同时只托管一个 .aix） */
    @Volatile
    private var currentServer: AixHttpServer? = null

    /** 当前托管的地址信息（供 Jsai_GetRequestInfo 回复眼镜目录配置用） */
    @Volatile
    private var currentHosted: HostedAix? = null

    /** 当前托管目录地址（agents.json），未托管时返回 null */
    fun currentCatalogUrl(): String? = currentHosted?.catalogUrl

    /**
     * 经 WiFi/蓝牙隧道把 .aix 上传到眼镜开发者 WebServer（POST /server/upload）。
     *
     * 服务端以原名落盘 device-protected files/aiui/package/cxr/<file.name>
     * （同名覆盖写入，不会生成 (1) 后缀；仅当目录 >5 个 .aix 时清理最旧）。
     * 之后手机端发 Sys_AIUI_Start(<file.name 去 .aix>) 即可让官方渲染层直启。
     *
     * @return null = 上传成功；否则为失败原因（直接可用于工具回复）
     */
    fun uploadAixToGlasses(context: Context, file: File): String? {
        if (!file.isFile) return ".aix 文件不存在：${file.absolutePath}"
        val app = context.applicationContext as? LabApplication
            ?: return "应用上下文异常"
        // 网络/隧道阻塞操作统一切 IO 线程，避免主线程调用（如广播）触发 NetworkOnMainThread
        return runBlocking(Dispatchers.IO) { doUploadAixToGlasses(context, app, file) }
    }

    /** [uploadAixToGlasses] 的实际逻辑（已在 IO 线程执行） */
    private fun doUploadAixToGlasses(context: Context, app: LabApplication, file: File): String? {
        // 眼镜开发者 WebServer（AssistServer 内 WebServerService，8848）空闲约 90s 会
        // 自动 onDestroy 释放端口；若未运行，蓝牙隧道转发目标 8848 会 ECONNREFUSED
        // （眼镜日志：BtTunnelServer Connection failed /127.0.0.1:8848 ECONNREFUSED）
        // → 隧道关闭 → 手机端 unexpected end of stream / EOFException size=0。
        // 上传前先广播拉起；失败再拉起一次重试。
        ensureGlassesWebServer(app)
        var lastErr = uploadOnce(context, app, file)
        if (lastErr != null) {
            Log.w(TAG, "uploadAixToGlasses first attempt failed: $lastErr; retry after ensuring webserver")
            ensureGlassesWebServer(app)
            lastErr = uploadOnce(context, app, file)
        }
        return lastErr
    }

    /** [deleteOnce] 结果：成功 / 文件不存在（继续试下一候选路径） */
    private const val DEL_OK = "OK"
    private const val DEL_NOT_FOUND = "NOT_FOUND"

    /**
     * 删除眼镜端 .aix 文件（管理页"删除"用）。
     *
     * 走眼镜开发者 WebServer 官方删除接口 POST /server/deleteFile（逆向自
     * WebControllerDeleteFileHandler：form 参数 filePath=绝对路径，无路径白名单，
     * WebServer 运行在 AssistServer 进程内 → 可删其私有 cxr 目录）。依次尝试：
     *  - /sdcard/Download/<agentId>.aix（/server/upload 落盘目录 SD_HTTP_TRANSFER_DIR）
     *  - AssistServer 私有 device-protected files/aiui/package/cxr/<agentId>.aix（渲染目录）
     *
     * @return null = 删除成功；否则为失败原因（直接可用于提示）
     */
    fun deleteAixOnGlasses(context: Context, agentId: String): String? {
        if (agentId.isBlank()) return "agentId 为空"
        val app = context.applicationContext as? LabApplication
            ?: return "应用上下文异常"
        return runBlocking(Dispatchers.IO) {
            ensureGlassesWebServer(app)
            val candidates = listOf(
                "/sdcard/Download/$agentId.aix",
                "/data/user_de/0/com.rokid.os.sprite.assistserver/files/aiui/package/cxr/$agentId.aix",
            )
            var notFound = 0
            var lastErr: String? = null
            for (path in candidates) {
                val r = deleteOnce(app, path)
                if (r == DEL_OK) return@runBlocking null
                if (r == DEL_NOT_FOUND) notFound++
                lastErr = r
            }
            // 显式返回：原写法 if/else 结果被丢弃，全未找到时回的却是原始错误码(B5)
            return@runBlocking if (notFound == candidates.size) "眼镜上未找到该应用的 .aix 文件"
            else lastErr ?: "删除失败"
        }
    }

    /**
     * 单次删除请求；DEL_OK=成功，DEL_NOT_FOUND=文件不存在，其余为失败原因
     */
    private fun deleteOnce(app: LabApplication, path: String): String {
        return try {
            // 与 uploadOnce 同款线路探测：先试用户配置的眼镜 WiFi IP，失败回落蓝牙隧道
            val wifiIp = app.glassesIp
            val route = runBlocking { app.routeManager.resolve(wifiIp, GLASSES_WEB_PORT) }
            when (route) {
                is ConnectionRoute.Wifi -> try {
                    postAixDelete(path, route.ip, route.port)
                } catch (e: IOException) {
                    // 同 uploadOnce：WiFi 首选失败（含明文被 NSC 拒、8848 未监听 ECONNREFUSED）一律降级
                    Log.w(
                        TAG,
                        "delete via WiFi ${route.ip}:${route.port} failed " +
                            "(${e.javaClass.simpleName}: ${e.message}), 降级蓝牙隧道重试",
                    )
                    app.routeManager.noteWifiFailure()
                    val localPort = app.routeManager.tunnelTo(GLASSES_WEB_PORT) ?: throw e
                    postAixDelete(path, "127.0.0.1", localPort)
                }
                is ConnectionRoute.Bluetooth -> postAixDelete(path, route.ip, route.localPort)
                is ConnectionRoute.None -> return "无法连接到眼镜（WiFi 与蓝牙隧道均不可用）"
            }
        } catch (e: Exception) {
            Log.e(TAG, "deleteAixOnGlasses failed: $path", e)
            "删除请求失败：${e.message ?: e.javaClass.simpleName}"
        }
    }

    /** 单次 HTTP 删除请求（不换线路）；DEL_OK=成功，DEL_NOT_FOUND=文件不存在，其余为失败原因 */
    private fun postAixDelete(path: String, ip: String, port: Int): String {
        val url = URL("http://$ip:$port/server/deleteFile")
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 10_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            DataOutputStream(conn.outputStream).use { out ->
                out.writeBytes("filePath=${URLEncoder.encode(path, "UTF-8")}")
            }
            val code = conn.responseCode
            val body = if (code in 200..299) {
                conn.inputStream.bufferedReader().readText()
            } else {
                conn.errorStream?.bufferedReader()?.readText().orEmpty()
            }
            if (code !in 200..299) return "眼镜删除服务响应异常（HTTP $code）：${body.take(120)}"
            Log.i(TAG, "deleteAixOnGlasses: $path -> $body")
            return when {
                body.contains("delete success") -> DEL_OK
                body.contains("not exist") -> DEL_NOT_FOUND
                else -> "眼镜端删除失败：${body.take(120)}"
            }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 通过 ADB-over-蓝牙隧道在眼镜上发广播拉起开发者 WebServer（8848）。
     * 广播 action/cmd 与眼镜端 AssistServer 的 WebServerService 约定一致
     * （com.rokid.glass.er.webserver.command / running_start）；
     * 服务已在运行时该命令幂等（仅复用已绑定端口，不重复 bind）。
     */
    private fun ensureGlassesWebServer(app: LabApplication) {
        val cmd = "am broadcast -a com.rokid.glass.er.webserver.command --es cmd running_start"
        runCatching {
            val adb = app.cxrL.getAdbShellClient()
            if (adb == null) {
                Log.w(TAG, "ensureGlassesWebServer: AdbShellClient unavailable (tunnel route down)")
            } else {
                val out = adb.executeShellCommand(cmd, timeoutMs = 8000)
                Log.i(TAG, "ensureGlassesWebServer: broadcast -> ${out.take(200)}")
            }
        }.onFailure { Log.w(TAG, "ensureGlassesWebServer failed", it) }
        // WebServerService onStartCommand → NanoHTTPD bind 很快，留 2.5s 余量等端口就绪
        Thread.sleep(2500)
    }

    /** 单次上传尝试；null = 成功，否则为失败原因 */
    private fun uploadOnce(context: Context, app: LabApplication, file: File): String? {
        return try {
            // 复用 ADB 同款线路探测：先试用户配置的眼镜 WiFi IP，失败自动回落到蓝牙隧道
            val wifiIp = app.glassesIp
            val route = runBlocking { app.routeManager.resolve(wifiIp, GLASSES_WEB_PORT) }
            when (route) {
                is ConnectionRoute.Wifi -> try {
                    postAixUpload(file, route.ip, route.port)
                } catch (e: IOException) {
                    // WiFi 首选但用不了 → 降级蓝牙隧道重试一次。
                    //
                    // 降级条件必须是「任何 IOException」，不能只认明文被拒（CLEARTEXT）：
                    // 修正 WiFi 判据后 resolve 会**乐观**选中 WiFi（adbd 可达即认 WiFi 可用），
                    // 而 8848 WebServer 是按需启动 / 空闲 90s 自毁的，WiFi 上极可能 ECONNREFUSED；
                    // 若只认 CLEARTEXT，这类失败会直接抛给用户 —— 表现就是「切了 WiFi 反而装不上」，
                    // 比不切更糟。故此处一律降级重试，蓝牙隧道是稳定的兜底。
                    Log.w(
                        TAG,
                        "upload via WiFi ${route.ip}:${route.port} failed " +
                            "(${e.javaClass.simpleName}: ${e.message}), 降级蓝牙隧道重试",
                    )
                    app.routeManager.noteWifiFailure()
                    val localPort = app.routeManager.tunnelTo(GLASSES_WEB_PORT) ?: throw e
                    postAixUpload(file, "127.0.0.1", localPort)
                }
                is ConnectionRoute.Bluetooth -> postAixUpload(file, route.ip, route.localPort)
                is ConnectionRoute.None ->
                    return "无法连接到眼镜（WiFi 与蓝牙隧道均不可用），请先确认手机与眼镜已连接"
            }
        } catch (e: Exception) {
            Log.e(TAG, "uploadAixToGlasses failed", e)
            "上传到眼镜失败：${e.message ?: e.javaClass.simpleName}"
        }
    }

    /** 单次 HTTP multipart 上传（不换线路）；null = 成功，否则为失败原因 */
    private fun postAixUpload(file: File, ip: String, port: Int): String? {
        val boundary = "----RokidLabAix" + System.currentTimeMillis()
        val url = URL("http://$ip:$port/server/upload")
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 10_000
            // .aix 可能数百 KB，蓝牙隧道吞吐约 1-2Mbps，读超时放宽到 90s
            conn.readTimeout = 90_000
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            DataOutputStream(conn.outputStream).use { out ->
                out.writeBytes("--$boundary\r\n")
                out.writeBytes("Content-Disposition: form-data; name=\"upfile\"; filename=\"${file.name}\"\r\n")
                out.writeBytes("Content-Type: application/octet-stream\r\n\r\n")
                file.inputStream().use { ins ->
                    val buf = ByteArray(32 * 1024)
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                    }
                }
                out.writeBytes("\r\n--$boundary--\r\n")
                out.flush()
            }
            val code = conn.responseCode
            val body = if (code in 200..299) {
                conn.inputStream.bufferedReader().readText()
            } else {
                conn.errorStream?.bufferedReader()?.readText().orEmpty()
            }
            if (code !in 200..299) return "眼镜上传服务响应异常（HTTP $code）：${body.take(200)}"
            val ok = runCatching { JSONObject(body).optBoolean("isSuccess") }.getOrDefault(false)
            if (!ok) return "眼镜上传服务返回异常：${body.take(200)}"
            Log.i(TAG, "uploadAixToGlasses ok: ${file.name} (${file.length()}B) via $ip:$port")
            return null
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 启动托管并返回地址；先停掉旧的。
     * @return [HostedAix]（.aix 下载地址 + agents.json 目录地址），失败 null
     */
    fun hostAix(
        aix: File,
        agentId: String,
        agentName: String,
        agentDesc: String,
        fileMd5: String,
        nativeVersion: String = "0.0.74",
        inkVersion: String = "",
    ): HostedAix? {
        currentServer?.stop()
        val server = AixHttpServer(
            aix,
            AixHttpServer.CatalogMeta(
                agentId = agentId,
                agentName = agentName,
                agentDesc = agentDesc,
                fileMd5 = fileMd5,
                nativeVersion = nativeVersion,
                inkVersion = inkVersion,
            ),
        )
        return try {
            server.start()
            currentServer = server
            // 注意：不能用 127.0.0.1 作为下载地址——眼镜无独立网络，下载请求经 NetProxy
            // 上行走官方 Rokid AI App(com.rokid.sprite.aiapp) 的代理应答器（CXR 链路宿主），
            // 官方 App 对 loopback 代理挂起不响应 → 眼镜下载超时被 sync cancel(code=499)。
            // 改用手机局域网 IP（server 绑 0.0.0.0），官方中继将其当作普通外网请求转发回
            // 手机本机，即可命中我们的 AixHttpServer。取不到 IP 时兜底 127.0.0.1。
            val host = phoneReachableIp() ?: "127.0.0.1"
            val base = "http://$host:${server.port}"
            HostedAix(aixUrl = "$base/${aix.name}", catalogUrl = "$base/agents.json").also {
                currentHosted = it
            }
        } catch (e: Exception) {
            Log.e(TAG, "hostAix failed", e)
            server.stop()
            currentHosted = null
            null
        }
    }

    /**
     * 取手机上一个官方 App（同机）可回环访问的非回环 IPv4：
     * 优先 wlan，其次其它接口（rmnet 等），全部失败返回 null。
     */
    private fun phoneReachableIp(): String? {
        try {
            val interfaces = java.util.Collections.list(NetworkInterface.getNetworkInterfaces())
            // 第一轮优先 wlan
            val wlan = interfaces.firstOrNull { it.isUp && it.name.contains("wlan") }?.let(::firstIpv4)
            if (wlan != null) return wlan
            // 第二轮其它已启用接口
            for (nif in interfaces) {
                if (!nif.isUp || nif.isLoopback || nif.name.contains("wlan")) continue
                firstIpv4(nif)?.let { return it }
            }
        } catch (e: Exception) {
            Log.w(TAG, "phoneReachableIp failed", e)
        }
        return null
    }

    private fun firstIpv4(nif: NetworkInterface): String? {
        val addrs = java.util.Collections.list(nif.inetAddresses)
        return addrs.firstOrNull { !it.isLoopbackAddress && it is Inet4Address }?.hostAddress
    }

    /** 文件 MD5（32 位小写十六进制，AgentFileVerifier 期望格式） */
    fun md5Hex(file: File): String {
        val md = MessageDigest.getInstance("MD5")
        file.inputStream().use { ins ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * 计算项目源码内容指纹（uuid v4 形状），用作 .aix 内 VERSION。
     *
     * 排除 VERSION 与 .rokid_*（避免自指递归）：内容不变指纹幂等；
     * 任一源码文件路径或内容变化 → 指纹变化 → 眼镜 Ink 引擎判定
     * version mismatch → 重新解压 runtime 缓存（见 [buildAix] 注释）。
     */
    private fun contentVersion(src: File): String {
        val md = MessageDigest.getInstance("MD5")
        src.walkTopDown()
            .filter { it.isFile && it.name != "VERSION" && !it.name.startsWith(".rokid_") }
            .sortedBy { it.relativeTo(src).path }
            .forEach { f ->
                md.update(f.relativeTo(src).path.toByteArray(Charsets.UTF_8))
                f.inputStream().use { ins ->
                    val buf = ByteArray(8192)
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        md.update(buf, 0, n)
                    }
                }
            }
        val hex = md.digest().joinToString("") { "%02x".format(it) }
        return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-" +
            "${hex.substring(16, 20)}-${hex.substring(20)}"
    }
}
