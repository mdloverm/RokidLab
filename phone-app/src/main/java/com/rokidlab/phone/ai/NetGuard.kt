package com.rokidlab.phone.ai

import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

/**
 * 出网 URL 的**准入闸门** —— [WebTools] 四个 URL 入口（`search_web` / `fetch_webpage` /
 * `http_request` / `download_file`）的唯一产地。
 *
 * ## 它堵的是哪一个洞
 * 改造前四个入口只查 `startsWith("http")`、**不查主机**，且 `instanceFollowRedirects = true`
 * 不复查跳转目标。而 `res/xml/network_security_config.xml` 出于真实需要明文放行了
 * `127.0.0.1`、`192.168.1.168`（眼镜 WebServer）等内网地址 ——
 * 于是"网页工具"顺手变成了**审批闸门的旁路**：
 *
 *  - 眼镜 WebServer（`192.168.1.168`）上的安装/删除 AIUI 应用本来要过 `ApprovalGate`，
 *    但一条被注入的网页（`fetch_webpage` 的结果被标成 `UNTRUSTED_EXTERNAL`，可它仍会
 *    进模型上下文）就能指示模型去 `http_request` POST 那个内网地址；
 *  - 手机的蓝牙隧道本地端口（`127.0.0.1:5556+`）同理，等于把眼镜通道暴露给外部内容。
 *
 * 判据是**主机可达性**，不是"某个工具危不危险"：公网地址放行，内网/回环/链路本地一律拒绝。
 * 需要操作内网服务的场景（眼镜、本机 Ollama、AIUI 安装）走各自的**专用通道**
 * （`AiuiProject` / `LlmRegistry`），它们不经这里、也不该由"抓网页"这条路代劳。
 *
 * ## 为什么必须逐跳校验
 * `HttpURLConnection` 的自动重定向是**服务端可控**的：一个公网 URL 只要回
 * `302 Location: http://127.0.0.1:5556/…`，自动跟随就会替你把请求送进内网 ——
 * 主机校验形同虚设。所以这里关掉自动跟随，自己逐跳走，**每一跳都重新过一遍**
 * [rejectReason]（相对 `Location` 也要能解）。
 *
 * ## ⚠️ 已知旁路：proot 容器里的进程**不经过这里**（2026-09-23 如实声明）
 *
 * `run_shell` / `run_script` / `install_packages` 在 proot 容器里跑的命令（`curl` / `wget` /
 * `apt` / `pip` …）以 **App 自己的 uid 直接开 socket**，不经过本对象、也不经过
 * `network_security_config` —— 那两样都只作用于 Java 层的 `HttpURLConnection` /
 * `NetworkSecurityPolicy`。也就是说容器**能**访问内网与回环地址（眼镜 WebServer、
 * 蓝牙隧道本地端口、本机 Ollama…）。
 *
 * **为什么不在这里堵**：容器是**通用的** rootfs，用户/AI 合法地需要 `apt install`（必须出网）、
 * 也可能需要访问本机 Ollama；用 `LD_PRELOAD` 垫片或改 `/etc/hosts` 只能挡住"按域名访问"，
 * 挡不住直连 IP，反而会制造"以为堵住了"的错觉。真正的隔离要靠 netns/iptables，那需要 root ——
 * 本 App 没有。**所以这里不做半吊子拦截，而是把事实写下来。**
 *
 * **实际依赖的边界**（改容器相关代码前必须知道）：
 *  - 容器出网**没有** URL 级准入；唯一的技术边界是 Android 的 `INTERNET` 权限本身；
 *  - 因此 `run_shell` 的能力边界只能靠**审批闸门 + 用户授权**，不能靠 NetGuard；
 *  - ⚠️ 目前 `run_shell` / `run_script` 是 `LOCAL_SIDE_EFFECT`（**不产生 `Ask`**），
 *    `install_packages` 是 `EXTERNAL_SIDE_EFFECT`（会问，但 `confirmPolicy = PROCEED`）。
 *    要让容器执行也受确认约束，改的是**这些工具的 `risk` / `confirmPolicy` 声明**，
 *    不是本对象。
 */
internal object NetGuard {

    /** 最多跟随几跳重定向（超过即中止，避免重定向环把一次工具调用拖到超时） */
    const val MAX_REDIRECTS = 5

    /**
     * 该 URL 是否允许访问。
     *
     * @return null = 放行；非 null = **给模型看的拒绝原因**（它会照实转告用户）
     */
    fun rejectReason(rawUrl: String): String? = rejectReason(rawUrl, systemResolver)

    /**
     * 可注入 DNS 解析器的版本，供单测使用。真实网络里公网域名可能被 DNS 污染到
     * `0.0.0.0`（实测 `raw.githubusercontent.com`），用系统解析会让"公网放行"用例
     * 的结果随运行环境变红；生产路径恒走 [systemResolver]。
     */
    internal fun rejectReason(
        rawUrl: String,
        resolve: (String) -> Array<InetAddress>?,
    ): String? {
        val u = rawUrl.trim()
        if (!u.startsWith("https://") && !u.startsWith("http://")) {
            return "只支持 http/https 链接（当前是：$u）"
        }
        val url = runCatching { URL(u) }.getOrNull() ?: return "链接格式不合法（$u）"
        val host = url.host
        if (host.isNullOrBlank()) return "链接里没有主机名（$u）"
        return hostRejectReason(host, resolve)
    }

    private val systemResolver: (String) -> Array<InetAddress>? = { host ->
        runCatching { InetAddress.getAllByName(host) }.getOrNull()
    }

    /**
     * 主机名准入判定。
     *
     * ⚠️ 域名要**先解析再看 IP**：`localhost`、`foo.local`、或攻击者自建的 A 记录都能
     * 指向 `127.0.0.1`，只比对字面量（`host == "127.0.0.1"`）挡不住。
     * 解析失败时**放行**（返回 null）—— 那是正常的网络错误，交给连接阶段如实报错，
     * 不该在这里编一个"地址不合法"的结论。
     */
    private fun hostRejectReason(host: String, resolve: (String) -> Array<InetAddress>?): String? {
        val addresses = resolve(host) ?: return null
        if (addresses.any { isPrivate(it) }) {
            return "这个地址在**内网或本机**（$host），本工具只能访问公网。" +
                "要操作眼镜/本机服务请用对应的专用工具（例如 AIUI 安装、投屏、本机执行环境），不要用抓网页这条路。"
        }
        return null
    }

    /** 内网 / 回环 / 链路本地 / 组播 / 保留段 —— 一律不允许由网页工具触达 */
    private fun isPrivate(a: InetAddress): Boolean {
        if (a.isAnyLocalAddress || a.isLoopbackAddress || a.isLinkLocalAddress ||
            a.isSiteLocalAddress || a.isMulticastAddress
        ) {
            return true
        }
        val b = a.address
        if (b.size == 4) {
            val b0 = b[0].toInt() and 0xFF
            val b1 = b[1].toInt() and 0xFF
            // 0.0.0.0/8（本机语义）、100.64/10（CGNAT，常被隧道占用）、
            // 198.18/15（基准测试段）、240/4（保留，含 255.255.255.255）
            if (b0 == 0) return true
            if (b0 == 100 && b1 in 64..127) return true
            if (b0 == 198 && b1 in 18..19) return true
            if (b0 >= 240) return true
        }
        return false
    }

    /**
     * 建连并**逐跳**校验重定向，返回最终（已校验过的）连接。
     *
     * 调用方负责 `disconnect()`。校验不通过时抛 [IOException]（消息就是给模型看的原因），
     * 与网络错误同一形态 —— 调用方的 catch 已经会把它如实带回模型。
     *
     * @param method 已由调用方白名单校验过的 HTTP method
     */
    @Throws(IOException::class)
    fun open(
        rawUrl: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        connectTimeoutMs: Int = 10_000,
        readTimeoutMs: Int = 20_000,
    ): HttpURLConnection {
        rejectReason(rawUrl)?.let { throw IOException(it) }
        var current = rawUrl.trim()
        var redirects = 0
        while (true) {
            val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                requestMethod = method
                // 关掉自动跟随：它会绕过下一跳的主机校验（见类注释）
                instanceFollowRedirects = false
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
            }
            val code = runCatching { conn.responseCode }.getOrElse {
                runCatching { conn.disconnect() }
                throw it
            }
            val location = conn.getHeaderField("Location")
            if (code in 300..399 && !location.isNullOrBlank()) {
                conn.disconnect()
                redirects++
                if (redirects > MAX_REDIRECTS) {
                    throw IOException("重定向次数过多（超过 $MAX_REDIRECTS 跳），已中止")
                }
                // 相对 Location（`/x/y`）也要能解 —— URL(base, location) 两者都吃
                val next = runCatching { URL(URL(current), location).toString() }
                    .getOrElse { throw IOException("重定向目标无法解析（$location）") }
                rejectReason(next)?.let { throw IOException(it) }
                current = next
                continue
            }
            return conn
        }
    }
}
