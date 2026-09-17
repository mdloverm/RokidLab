package com.rokidlab.phone.ai

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.rokidlab.phone.app.LabApplication

/**
 * 调试入口：验证 Sys_AIUI_Start / Sys_AIUI_Stop 控制 cxr 目录 .aix 的渲染。
 *
 * **仅在 `src/debug` 源集编译**：release 包既不含本类，也不含 manifest 中的 receiver
 * 声明（见 `src/debug/AndroidManifest.xml`）。原因见 P0-1 —— 本类 `act="shell"` 分支
 * 可经 ADB-over-蓝牙隧道在眼镜端执行任意 shell，`exported=true` 且无权限保护时
 * 任何本机 App 都能投递广播触发。
 *
 * 触发方式（需手机 App 已连接眼镜）：
 * ```
 * adb -s <phone> shell am broadcast -a com.rokidlab.phone.DEBUG_CMD --es act start --es pkg <packageName>
 * adb -s <phone> shell am broadcast -a com.rokidlab.phone.DEBUG_CMD --es act stop  --es pkg <packageName>
 * ```
 * act 缺省为 start；packageName 是已上传到眼镜 device-protected
 * files/aiui/package/cxr/ 的 .aix 文件名（不含 .aix）。上传方式见
 * [CxrLHiRokidSession.startAiuiPackage]。
 */
class DebugAiuiReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? LabApplication ?: return
        val act = intent.getStringExtra("act") ?: "start"
        Log.i(TAG, "act=$act extras=${intent.extras}")
        when (act) {
            "stop" -> {
                val pkg = intent.getStringExtra("pkg") ?: return
                val r = app.cxrL.stopAiuiPackage(pkg)
                Log.i(TAG, "stopAiuiPackage -> $r")
            }
            // 模拟 install_aiui_project 前半段：打包 + 直传眼镜 cxr 目录（不启动）。
            // 广播回调在主线程，网络上传必须切后台线程。
            "install" -> {
                val project = intent.getStringExtra("project") ?: return
                val appName = intent.getStringExtra("appName") ?: project
                Thread {
                    val aix = AiuiProject.buildAix(context, project, appName)
                    if (aix == null) {
                        Log.e(TAG, "buildAix failed: project=$project")
                        return@Thread
                    }
                    val err = AiuiProject.uploadAixToGlasses(context, aix.aixFile)
                    // 与工具层一致：安装成功后登记项目记录（管理页/语音列表可见、可更新重装）
                    if (err == null) {
                        AiuiAppRegistry.upsert(
                            context,
                            AiuiAppRegistry.AiuiAppRecord(
                                appName = appName,
                                agentId = aix.agentId,
                                project = project,
                                pageName = aix.pageName,
                                sourceProjectDir = AiuiProject.projectDir(context, project).absolutePath,
                                origin = AiuiAppRegistry.ORIGIN_GENERATED,
                                aixOnGlasses = true,
                            ),
                        )
                    }
                    Log.i(TAG, "uploadAixToGlasses(project=$project, agentId=${aix.agentId}) -> ${err ?: "OK (${aix.aixFile.length()}B)"}")
                }.apply {
                    name = "debug-aix-install"
                    start()
                }
            }
            // 升级 Termux 内的 ollama（pkg 源）并自动以 CPU 模式重启，便于验证新版 Vulkan 后端：
            // adb -s <phone> shell am broadcast -a com.rokidlab.phone.DEBUG_CMD --es act upgrade_ollama
            "upgrade_ollama" -> Thread {
                val old = LocalOllamaManager.serverVersion() ?: "?"
                Log.i(TAG, "upgrade_ollama: current=$old")
                val script = buildString {
                    append("export PATH=/data/data/com.termux/files/usr/bin:\$PATH\n")
                    append("export OLLAMA_HOST=127.0.0.1:11434\n")
                    append("export OLLAMA_VULKAN=0\n")
                    // 清理可能卡死的旧 apt 下载进程，避免锁/残留。
                    // 注意：只能用 -x 按进程名杀，禁止 pkill -f（会匹配到本脚本自身命令行而自杀）
                    append("pkill -x apt 2>/dev/null; pkill -x apt-get 2>/dev/null; pkill -x https 2>/dev/null; pkill -x gpgv 2>/dev/null; pkill -x dpkg 2>/dev/null; sleep 1\n")
                    // 官方源在部分网络下极慢/停滞：先切换清华镜像（备份原配置）
                    append("L=/data/data/com.termux/files/usr/etc/apt/sources.list\n")
                    append("if grep -q packages.termux.org \"\$L\"; then cp \"\$L\" \"\$L.bak\"; sed -i 's|https://packages.termux.org/apt/termux-main|https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main|g' \"\$L\"; fi\n")
                    // 修复上次中断可能残留的 dpkg 半配置状态
                    append("dpkg --configure -a 2>/dev/null\n")
                    append("apt-get update -o Acquire::Retries=2 -o Acquire::http::Timeout=25 -o Acquire::https::Timeout=25\n")
                    append("apt-get install -y --no-install-recommends ollama\n")
                    append("pkill -x ollama 2>/dev/null\n")
                    append("sleep 1\n")
                    append("exec ollama serve\n")
                }
                runCatching { LocalOllamaManager.runInTermux(context, script) }
                    .onFailure { Log.e(TAG, "upgrade_ollama: launch failed: $it") }
                // RUN_COMMAND 不回传输出，改轮询 HTTP：等到新版本就绪（或 10 分钟超时）
                val deadline = System.currentTimeMillis() + 10 * 60 * 1000L
                while (System.currentTimeMillis() < deadline) {
                    Thread.sleep(3000)
                    val now = LocalOllamaManager.serverVersion()
                    if (now != null && now != old) {
                        Log.i(TAG, "upgrade_ollama: upgraded $old -> $now")
                        return@Thread
                    }
                }
                Log.w(TAG, "upgrade_ollama: timeout waiting for new version (still ${LocalOllamaManager.serverVersion() ?: "down"})")
            }.apply { name = "debug-ollama-upgrade"; start() }
            // 经 ADB-over-蓝牙隧道在眼镜上执行 shell（调试用）：
            // adb shell am broadcast ... --es act shell --es cmd "id && ls /data/user_de/0/*/files/aiui/package/cxr/"
            "shell" -> {
                val cmd = intent.getStringExtra("cmd") ?: return
                Thread {
                    val adb = app.cxrL.getAdbShellClient()
                    if (adb == null) {
                        Log.e(TAG, "shell: AdbShellClient unavailable")
                        return@Thread
                    }
                    val out = adb.executeShellCommand(cmd, timeoutMs = 15000)
                    Log.i(TAG, "shell [$cmd] ->\n${out.take(2000)}")
                }.apply { name = "debug-glass-shell"; start() }
            }
            // 关闭当前正在渲染的自托管宿主。
            // ⚠️ 重跑/换包前**必须先关**：宿主是 singleTask，旧实例还在时新的一次 open
            // 会走 onNewIntent 换包，而它当前实现是 finish() + postDelayed(150ms, startActivity)，
            // 那个延迟内旧实例的 WebView 还没销毁完，重新启动会被系统吞掉（实测：Activity
            // START 后 384ms 被 dispose 且再无第二次 START，页面根本没跑起来）。
            // adb shell am broadcast ... --es act closehost
            "closehost" -> {
                val r = app.cxrL.closeAiuiHost()
                Log.i(TAG, "closeAiuiHost -> $r")
            }
            // 打开已安装的 AIUI 应用，走「Lab 自托管 AiuiLinkActivity(web 宿主)」——
            // 该宿主带页面工具桥（`globalThis.Lab`），与工具层 open_aiui_app 同路径。
            // ⚠️ 不要改用 openAiuiAgent：它发 Ai_RenderPayload(jsui)，渲染在**官方宿主**
            //    (AssistServer)，那里没有 lab-page-bridge，页面调 callTool 必然失败。
            // adb shell am broadcast ... --es act open --es pkg <agentId> [--es params '<json>']
            "open" -> {
                val pkg = intent.getStringExtra("pkg") ?: return
                // ⚠️ 不要命名为 name：局部变量会遮蔽 Thread.name，使下面 apply{} 里
                // `name = ...` 变成给 val 赋值 → Kotlin 报 "Val cannot be reassigned"。
                val launchParams = intent.getStringExtra("params")
                Thread {
                    val aix = AiuiProject.packageFile(context, pkg)
                    if (!aix.isFile) {
                        Log.e(TAG, "open(host): aix missing for $pkg -> ${aix.absolutePath}")
                        return@Thread
                    }
                    val r = app.cxrL.pushAixToRokidLinkHost(
                        aix,
                        openAfter = true,
                        launchParams = launchParams,
                    )
                    Log.i(TAG, "pushAixToRokidLinkHost(pkg=$pkg) -> ${r ?: "null"}")
                }.apply { name = "debug-aiui-open-host"; start() }
            }
            // 走官方宿主（AiuiFrontendController.openAiuiAgent → Ai/Ai_RenderPayload，
            // jsui 卡片 480×168）。**该宿主无工具桥**，只用于对比渲染差异/复现官方路径问题。
            "opencard" -> {
                val pkg = intent.getStringExtra("pkg") ?: return
                val appName = intent.getStringExtra("appName") ?: pkg
                Thread {
                    val rec = AiuiAppRegistry.getByAgentId(context, pkg)
                    val r = app.cxrL.openAiuiAgent(
                        pkg,
                        appName,
                        rec?.nativeVersion ?: "0.0.74",
                        rec?.pageName ?: "pages/index/index",
                    )
                    Log.i(TAG, "openAiuiAgent(card host, no bridge) pkg=$pkg -> $r")
                }.apply { name = "debug-aiui-opencard"; start() }
            }
            else -> {
                val pkg = intent.getStringExtra("pkg") ?: return
                val r = app.cxrL.startAiuiPackage(pkg)
                Log.i(TAG, "startAiuiPackage -> $r")
            }
        }
    }

    private companion object {
        const val TAG = "DebugAiuiReceiver"
    }
}
