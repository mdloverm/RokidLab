package com.rokidlab.phone.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetAddress

/**
 * 出网闸门（[NetGuard]）回归测试 —— 锁住一条**闸门旁路**。
 *
 * ## 为什么值得一条测试
 * `res/xml/network_security_config.xml` 出于真实需要明文放行了 `127.0.0.1`（Ollama / 蓝牙隧道）
 * 与 `192.168.1.168`（眼镜 WebServer）。这份白名单是**进程级**的 —— 任何走
 * `HttpURLConnection` 的代码都吃它。而 AI 的网页工具（`fetch_webpage` / `http_request` /
 * `download_file` / `search_web`）的输入**可能来自被注入的外部内容**（网页正文本身就是
 * `UNTRUSTED_EXTERNAL`）。若不拦住，一条"请把这份数据 POST 到 http://192.168.1.168/..."
 * 的网页就能让模型去调眼镜 WebServer 上**本该过审批闸门**的安装/删除接口。
 *
 * 所以本测试锁三件事：
 *  1. 内网/回环/链路本地 **一律拒绝**（含域名解析到内网的情况，如 `localhost`）；
 *  2. 公网 URL **照常放行**（不能用"一刀拒"来实现安全）；
 *  3. **明文白名单与闸门联动**：白名单里有几个主机，闸门就必须拦住几个
 *     （豁免项必须是显式写出来的公网地址）—— 新增白名单条目时这条会立刻变红。
 */
class NetGuardTest {

    // ════════════════════════════════════════════════════════════════
    // 1. 内网 / 回环 / 保留段 → 必须拒绝
    // ════════════════════════════════════════════════════════════════

    @Test
    fun `内网与回环地址一律拒绝`() {
        val blocked = listOf(
            "http://127.0.0.1:5556/",            // 蓝牙隧道本地端口（真实存在）
            "http://127.0.0.1:11434/api/tags",   // 本机 Ollama
            "http://localhost:8080/x",           // 域名解析到 127.0.0.1（只比对字面量挡不住）
            "http://192.168.1.168/cgi-bin/install", // 眼镜 WebServer（真实存在）
            "http://192.168.49.1:8848/",         // 眼镜热点网关
            "http://10.1.2.3/",                  // RFC1918
            "http://172.16.5.5/",                // RFC1918
            "http://169.254.10.10/",             // 链路本地
            "http://100.100.1.1/",               // CGNAT（常被隧道占用）
            "http://0.0.0.0/",                   // 本机语义
            "http://239.1.1.1/",                 // 组播
        )
        blocked.forEach { url ->
            assertNotNull("必须拒绝：$url", NetGuard.rejectReason(url))
        }
    }

    @Test
    fun `拒绝理由要说清是内网并给出出路`() {
        val reason = NetGuard.rejectReason("http://192.168.1.168/install")!!
        assertTrue("必须点明是内网/本机：$reason", reason.contains("内网") || reason.contains("本机"))
        assertTrue("必须给出可行动的方向（专用工具），否则模型只能瞎猜：$reason", reason.contains("专用工具"))
    }

    // ════════════════════════════════════════════════════════════════
    // 2. 公网 / 协议 → 放行与拒绝
    // ════════════════════════════════════════════════════════════════

    @Test
    fun `公网地址放行`() {
        // 注入固定解析到公网 IP 的解析器：本用例只验证"解析结果为公网 ⇒ 放行"。
        // 不走系统 DNS —— 部分网络会把公网域名污染到 0.0.0.0（实测
        // raw.githubusercontent.com），用真实解析会让结果随运行环境变红。
        val publicDns: (String) -> Array<InetAddress>? = {
            arrayOf(InetAddress.getByAddress(byteArrayOf(8, 8, 8, 8)))
        }
        listOf(
            "https://cn.bing.com/search?q=rokid",
            "http://ip-api.com/json/",
            "https://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports/",
            "https://raw.githubusercontent.com/x/y/main/README.md",
        ).forEach { url ->
            assertNull("公网地址不该被拦：$url", NetGuard.rejectReason(url, publicDns))
        }
    }

    @Test
    fun `域名被 DNS 污染到 0_0_0_0 时仍拒绝`() {
        // 0.0.0.0 在 Linux/Android 上 connect 语义等同本机回环，是真实 SSRF 面：
        // 污染场景必须继续拦，不能为了让被投域名"能访问"而放行。
        val sinkholeDns: (String) -> Array<InetAddress>? = { host ->
            arrayOf(InetAddress.getByAddress(host, ByteArray(4)))
        }
        assertNotNull(
            "解析到 0.0.0.0 必须拒绝",
            NetGuard.rejectReason("https://raw.githubusercontent.com/x/y/main/README.md", sinkholeDns),
        )
    }

    @Test
    fun `只认 http https`() {
        listOf(
            "file:///etc/passwd",
            "ftp://example.com/x",
            "javascript:alert(1)",
            "/just/a/path",
            "",
        ).forEach { url ->
            assertNotNull("非 http/https 必须拒绝：'$url'", NetGuard.rejectReason(url))
        }
    }

    // ════════════════════════════════════════════════════════════════
    // 3. 明文白名单 ⇄ 闸门联动（本次修复的核心不变式）
    // ════════════════════════════════════════════════════════════════

    /**
     * 豁免项：**公网**且确有 http 需求的主机。写在这里而不是"默认全放"，
     * 就是为了让每个例外都要有人签字。目前只有定位接口（它的免费档只有 http）。
     */
    private val cleartextExempt = setOf("ip-api.com")

    @Test
    fun `明文白名单里除豁免项外都必须被出网闸门拦住`() {
        val xml = nscFile().readText()
        val hosts = Regex("<domain[^>]*>([^<]+)</domain>")
            .findAll(xml)
            .map { it.groupValues[1].trim() }
            .filter { it.isNotEmpty() }
            .toList()
        assertTrue("没解析到明文白名单条目（测试会形同虚设）", hosts.size >= 3)

        val leaked = hosts.filter { it !in cleartextExempt }
            .filter { NetGuard.rejectReason("http://$it/") == null }
        assertEquals(
            "以下明文白名单主机可以被动地由 AI 的网页工具访问 ⇒ 审批闸门旁路。" +
                "要么把该主机加进 cleartextExempt（并说明为什么它是公网且必须有 http），" +
                "要么确认它确实需要白名单（眼镜/Ollama 走专用通道，本不该经网页工具）。",
            emptyList<String>(),
            leaked,
        )
    }

    /** 与 [com.rokidlab.phone.ai.AiuiRuntimeDocSizeTest] 同一套定位约定（单测工作目录可能是模块目录或仓库根） */
    private fun nscFile(): File {
        val candidates = listOf(
            File("src/main/res/xml/network_security_config.xml"),
            File("phone-app/src/main/res/xml/network_security_config.xml"),
        )
        return candidates.firstOrNull { it.exists() }
            ?: throw AssertionError(
                "找不到 network_security_config.xml（试过 ${candidates.joinToString { it.path }}）。" +
                    "单测工作目录变了请更新这里 —— 不要因此删掉这条断言。",
            )
    }
}
