package com.rokidlab.phone.feature

import com.rokidlab.phone.R
import com.rokidlab.phone.app.LabApplication
import com.rokidlab.phone.app.MainActivity
import com.rokidlab.phone.model.GuideStep
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.widget.Toast
import androidx.lifecycle.lifecycleScope

/**
 * L5 feature/RokidLinkController —— Phase 5 拆 `MainActivity` 的 RokidLink 生命周期控制器。
 *
 * 职责：眼镜端 RokidLink 的安装检测（含 ADB 探活）、安装/引导安装/重装、
 * 启动/停止/自动拉起，以及运行/安装状态的三个镜像状态位同步
 * （screenMirrorState / phoneMirrorState / fileManagerState）。
 *
 * 依赖（activity 句柄，门面零改动策略）：Activity 的 lifecycleScope / assets /
 * Compose 状态（screenMirrorState 等，internal）/ log / updateBusy /
 * runWithPrerequisites / showExportLogDialog / cxrL（L2 会话门面）。
 */
internal class RokidLinkController(private val activity: MainActivity) {

    /**
     * 把重链路 CXR 调用放到 IO 线程执行。
     *
     * **为什么必须放后台**：`cxrL.launchApp / stopApp / queryInstalledApps / installApk`
     * 的第一段都是 `session.cleanup()` —— 拆 ADB 共享会话、断开 CXR 链路、解绑宿主服务，
     * 全是阻塞 IO。这些入口又被 Compose 的 `clickable` 回调（引导页「跳过」/「发送」按钮、
     * 主页/商店的启动按钮）和 `runWithPrerequisites` 直接在**主线程**同步调用，
     * 眼镜离线时一次建链要等满 TCP + 握手超时 → 主线程 5s 内无法响应触摸 → 系统 ANR。
     */
    private fun runCxrOperation(block: suspend () -> Unit) {
        activity.lifecycleScope.launch(Dispatchers.IO) { block() }
    }

    // ── 会话级运行状态（迁自 MainActivity，仅本控制器读写）──
    /** 用户按键启动后整个会话期间都视为已启动（停止键是唯一清除途径） */
    private var rokidLinkUserStarted = false
    /** 前台自动拉起成功后置位，避免重复 launchApp */
    private var rokidLinkAutoRunning = false
    /** 新启动会话只做一次 ADB 探活 */
    private var rokidLinkAdbTested = false
    /** 安装查询去重 */
    private var isCheckingRokidLink = false
    /** 启动去重 */
    private var isOpeningRokidLink = false

    // ════════════════════════════════════════════════════════════════
    //  RokidLink 运维（探测 / 安装 / 重装 / 启停）
    // ════════════════════════════════════════════════════════════════
    internal fun checkRokidLinkInstallation() {
        val app = activity.application as LabApplication
        // 跳过持久化缓存兜底——每次都等 SDK 查询结果，避免卸载后仍显示"已安装"
        
        // 运行时缓存：有结果直接用
        if (app.rokidLinkInstalled != null) {
            activity.screenMirrorState = activity.screenMirrorState.copy(rokidLinkInstalled = app.rokidLinkInstalled)
            activity.phoneMirrorState = activity.phoneMirrorState.copy(rokidLinkInstalled = app.rokidLinkInstalled)
            activity.fileManagerState = activity.fileManagerState.copy(rokidLinkInstalled = app.rokidLinkInstalled)
            return
        }
        
        // SDK 查询
        if (!activity.cxrL.hasAuthorization()) return
        
        if (isCheckingRokidLink) return
        isCheckingRokidLink = true

        // 放 IO：queryInstalledApps 首段是 session.cleanup()，眼镜离线时会阻塞主线程 → ANR
        runCxrOperation {
            activity.cxrL.queryInstalledApps(
                packageNames = listOf("com.rokidlab.rokidlink"),
                onResult = { _, installed ->
                    if (installed == null) {
                        // 查询失败：状态未知，保持既有状态，不误判为"未安装"
                        android.util.Log.w("RokidLinkInstall", "查询 RokidLink 安装状态失败，保持既有状态")
                        return@queryInstalledApps
                    }
                    app.setRokidLinkInstalled(installed)
                    activity.screenMirrorState = activity.screenMirrorState.copy(rokidLinkInstalled = installed)
                    activity.phoneMirrorState = activity.phoneMirrorState.copy(rokidLinkInstalled = installed)
                    activity.fileManagerState = activity.fileManagerState.copy(rokidLinkInstalled = installed)
                    if (!installed) {
                        // 未安装时重置 running 状态
                        activity.screenMirrorState = activity.screenMirrorState.copy(rokidLinkRunning = false)
                        activity.phoneMirrorState = activity.phoneMirrorState.copy(rokidLinkRunning = false)
                        activity.fileManagerState = activity.fileManagerState.copy(rokidLinkRunning = false)
                    }
                },
                onComplete = {
                    isCheckingRokidLink = false
                }
            )
        }
    }

    /**
     * 测试本机能否通过 ADB (TCP 5555) 连通眼镜。
     * 仅在新启动会话中调用一次，连通则视为 RokidLink 已在眼镜端运行。
     */
    internal fun testAdbOnFreshLaunch() {
        if (rokidLinkAdbTested || rokidLinkUserStarted) return
        rokidLinkAdbTested = true
        val app = activity.application as LabApplication
        // 使用任一模块配置的眼镜 IP（默认 192.168.1.168）
        val ip = app.phoneMirrorIp.ifBlank {
            app.fileManagerIp.ifBlank {
                app.screenMirrorIp
            }
        }
        activity.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val socket = java.net.Socket()
                socket.connect(java.net.InetSocketAddress(ip, 5555), 3000)
                socket.close()
                // ADB 连通 → 认为眼镜端 RokidLink 已在运行
                withContext(Dispatchers.Main) {
                    activity.log(activity.getString(R.string.log_rokidlink_launched))
                    activity.screenMirrorState = activity.screenMirrorState.copy(rokidLinkRunning = true)
                    activity.phoneMirrorState = activity.phoneMirrorState.copy(rokidLinkRunning = true)
                    activity.fileManagerState = activity.fileManagerState.copy(rokidLinkRunning = true)
                }
            } catch (_: Exception) {
                // ADB 不通 → 眼镜不在线，保持未运行状态
            }
        }
    }

    /** 将内置 RokidLink APK 上传安装到眼镜（纯安装，不含卸载） */
    internal fun installRokidLinkToGlasses(onResult: ((Boolean) -> Unit)? = null) {
        activity.runWithPrerequisites {
            runCxrOperation {
                activity.updateBusy(true)
                activity.log(activity.getString(R.string.log_installing_rokidlink))
                activity.runOnUiThread {
                    activity.screenMirrorState = activity.screenMirrorState.copy(isInstallingRokidLink = true)
                    activity.phoneMirrorState = activity.phoneMirrorState.copy(isInstallingRokidLink = true)
                    activity.fileManagerState = activity.fileManagerState.copy(isInstallingRokidLink = true)
                }

                runCatching {
                    val apkInputStream = activity.assets.open("RokidLink.apk")
                    val tempFile = File(activity.cacheDir, "RokidLink.apk")
                    apkInputStream.use { input ->
                        tempFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    activity.cxrL.installApk(tempFile) { installed ->
                        activity.runOnUiThread {
                            activity.screenMirrorState = activity.screenMirrorState.copy(
                                rokidLinkInstalled = installed,
                                isInstallingRokidLink = false
                            )
                            activity.phoneMirrorState = activity.phoneMirrorState.copy(
                                rokidLinkInstalled = installed,
                                isInstallingRokidLink = false
                            )
                            activity.fileManagerState = activity.fileManagerState.copy(
                                rokidLinkInstalled = installed,
                                isInstallingRokidLink = false
                            )
                            (activity.application as LabApplication).setRokidLinkInstalled(installed)
                            activity.updateBusy(false)
                            if (installed) {
                                activity.settingsReinstallError = null
                                activity.log(activity.getString(R.string.log_rokidlink_installed))
                                Toast.makeText(activity, activity.getString(R.string.toast_rokidlink_installed), Toast.LENGTH_SHORT).show()
                            } else {
                                activity.settingsReinstallError = activity.getString(R.string.log_rokidlink_install_failed)
                                activity.log(activity.settingsReinstallError!!)
                                Toast.makeText(activity, activity.getString(R.string.toast_rokidlink_install_failed), Toast.LENGTH_SHORT).show()
                            }
                            onResult?.invoke(installed)
                        }
                        tempFile.delete()
                    }
                }.onFailure { e ->
                    // 本块已跑在 IO 线程：Toast / 弹窗 / Compose 状态写必须回主线程
                    activity.runOnUiThread {
                        onResult?.invoke(false)
                        activity.settingsReinstallError = activity.getString(R.string.install_failed_simple, e.message)
                        activity.log(activity.settingsReinstallError!!)
                        Toast.makeText(activity, activity.getString(R.string.install_failed_simple, e.message), Toast.LENGTH_LONG).show()
                        activity.screenMirrorState = activity.screenMirrorState.copy(isInstallingRokidLink = false)
                        activity.phoneMirrorState = activity.phoneMirrorState.copy(isInstallingRokidLink = false)
                        activity.fileManagerState = activity.fileManagerState.copy(isInstallingRokidLink = false)
                        activity.updateBusy(false)
                        // 安装异常时自动弹出错误报告
                        activity.showExportLogDialog()
                    }
                }
            }
        }
    }

    /**
     * 引导流程中安装 RokidLink 到眼镜。
     *
     * 先查询安装状态（三态判断）：
     * - `false`（眼镜端确认未安装）→ 跳过卸载，直接上传安装，省一次卸载操作；
     * - `true`（已安装，无论 debug/release、任何版本）→ 先停后卸再装，保证干净重装；
     * - `null`（查询失败，状态未知）→ 保守走「先停后卸再装」，宁可多卸一次也不能覆盖安装——
     *   旧版 debug 签名与新版 release 签名冲突时眼镜端 pm install 会报
     *   INSTALL_FAILED_UPDATE_INCOMPATIBLE（用户可见的"系统报错"）。
     */
    internal fun installRokidLinkForGuide(onResult: (Boolean) -> Unit) {
        runCxrOperation {
            activity.updateBusy(true)
            android.util.Log.i("RokidLinkInstall", "=== 开始安装 RokidLink 到眼镜（先查询安装状态）===")
            var installed: Boolean? = null
            activity.cxrL.queryInstalledApps(
                packageNames = listOf("com.rokidlab.rokidlink"),
                onResult = { _, isInstalled -> installed = isInstalled },
                onComplete = {
                    // onComplete 由 SDK 回调经主线程 post 进来，而它上游的 finishQueries() 会先
                    // session.cleanup() 把链路整个拆掉（cxrLink=null / cxrlConnected=false）；
                    // 随后无论是安装还是「先停后卸再装」都要重建会话（阻塞 IO）→ 必须切回 IO，
                    // 否则建链卡在主线程（ANR）。
                    runCxrOperation {
                        if (installed == false) {
                            android.util.Log.i("RokidLinkInstall", "查询确认未安装，直接上传安装")
                            installRokidLinkForGuideCore(onResult)
                        } else {
                            android.util.Log.i("RokidLinkInstall", "RokidLink 已安装或状态未知（installed=$installed），先停止再卸载旧版")
                            uninstallThenInstallForGuide(onResult)
                        }
                    }
                },
            )
        }
    }

    /** 引导流程：先停止并卸载眼镜端 RokidLink，再上传安装；卸载未成功不中止（可能本就未安装） */
    private fun uninstallThenInstallForGuide(onResult: (Boolean) -> Unit) {
        runCxrOperation {
            activity.log(activity.getString(R.string.log_closing_rokidlink))
            // 未安装时该操作无害（仅回调失败），统一调用以保证眼镜端进程已退出
            activity.cxrL.stopApp(
                packageName = "com.rokidlab.rokidlink",
                onStopResult = { success ->
                    activity.log(if (success) activity.getString(R.string.log_rokidlink_closed) else activity.getString(R.string.log_rokidlink_close_failed))
                },
            )
            runCxrOperation {
                delay(300)
                activity.log(activity.getString(R.string.log_uninstalling_rokidlink))
                activity.cxrL.uninstallApp(
                    packageName = "com.rokidlab.rokidlink",
                    onUninstallResult = { uninstalled ->
                        if (uninstalled) {
                            activity.log(activity.getString(R.string.log_rokidlink_uninstalled))
                        } else {
                            // 卸载未成功（大概率是本就未安装）：不中止，继续安装；
                            // 若确实存在残留旧包，installApk 会自然失败并由 UI 报错重试
                            android.util.Log.w("RokidLinkInstall", "卸载未成功（可能本就未安装），继续安装")
                            activity.log(activity.getString(R.string.log_rokidlink_uninstall_skip_install))
                        }
                        runCxrOperation {
                            delay(500)
                            installRokidLinkForGuideCore(onResult)
                        }
                    },
                )
            }
        }
    }

    /** 引导流程：卸载完成后上传安装 RokidLink，成功后自动启动 */
    internal fun installRokidLinkForGuideCore(onResult: (Boolean) -> Unit) {
        activity.log(activity.getString(R.string.log_installing_rokidlink))
        runCatching {
            android.util.Log.i("RokidLinkInstall", "正在从 activity.assets 读取 RokidLink.apk")
            val apkInputStream = activity.assets.open("RokidLink.apk")
            val tempFile = File(activity.cacheDir, "RokidLink.apk")
            val size = apkInputStream.available()
            android.util.Log.i("RokidLinkInstall", "APK 大小: ${size / 1024} KB")
            apkInputStream.use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            android.util.Log.i("RokidLinkInstall", "临时文件已准备: ${tempFile.absolutePath}")
            android.util.Log.i("RokidLinkInstall", "调用 activity.cxrL.installApk()...")
            activity.cxrL.installApk(tempFile) { installed ->
                if (installed) {
                    android.util.Log.i("RokidLinkInstall", "=== 安装成功 ===")
                    activity.log(activity.getString(R.string.log_rokidlink_installed))
                    (activity.application as LabApplication).setRokidLinkInstalled(true)
                    // 安装后自动启动 RokidLink，使其 KeyButtonService 开始运行并 subscribe 消息
                    android.util.Log.i("RokidLinkInstall", "正在启动 RokidLink...")
                    activity.cxrL.launchApp("com.rokidlab.rokidlink", onLaunchResult = { launched ->
                        activity.runOnUiThread {
                            activity.updateBusy(false)
                            if (launched) {
                                android.util.Log.i("RokidLinkInstall", "RokidLink 启动成功")
                            } else {
                                android.util.Log.w("RokidLinkInstall", "RokidLink 启动失败，WiFi 配置可能不可用")
                            }
                            onResult(true)
                        }
                    })
                } else {
                    activity.runOnUiThread {
                        activity.updateBusy(false)
                        android.util.Log.w("RokidLinkInstall", "=== 安装失败 ===")
                        activity.log(activity.getString(R.string.log_rokidlink_install_failed))
                        onResult(false)
                    }
                }
                tempFile.delete()
            }
        }.onFailure { e ->
            activity.updateBusy(false)
            android.util.Log.e("RokidLinkInstall", "安装异常: ${e.javaClass.simpleName}: ${e.message}")
            activity.log(activity.getString(R.string.install_failed_simple, e.message))
            onResult(false)
        }
    }

    /**
     * 启动 RokidLink：用户按键启动后整个会话期间都视为已启动，
     * 不再受 CXR-L 连接状态影响。停止键是唯一清除途径。
     */
    internal fun openRokidLinkOnGlasses() {
        if (isOpeningRokidLink) return
        isOpeningRokidLink = true
        // 标记用户已在本会话中启动，之后保持运行状态
        rokidLinkUserStarted = true
        activity.screenMirrorState = activity.screenMirrorState.copy(rokidLinkRunning = true)
        activity.phoneMirrorState = activity.phoneMirrorState.copy(rokidLinkRunning = true)
        activity.fileManagerState = activity.fileManagerState.copy(rokidLinkRunning = true)
        activity.log(activity.getString(R.string.log_opening_rokidlink))
        // 仍然尝试启动眼镜端应用，但无论成功失败都不影响 running 状态。
        // sendCmdAfterLaunch="rokidlab_show_main"：appStart 隐形启动（退后台）后，
        // 通过自定义指令让眼镜端带 EXTRA_SHOW_UI 显示状态页（含 WiFi IP），供用户查看。
        // 放 IO：launchApp 首段是 session.cleanup()，主线程同步调用会 ANR（见 runCxrOperation）
        runCxrOperation {
            activity.cxrL.launchApp(
                packageName = "com.rokidlab.rokidlink",
                activityClass = ".MainActivity",
                sendCmdAfterLaunch = "rokidlab_show_main",
                onLaunchResult = { success ->
                    isOpeningRokidLink = false
                    if (success) {
                        activity.log(activity.getString(R.string.log_rokidlink_launched))
                    } else {
                        activity.log(activity.getString(R.string.log_rokidlink_launch_failed))
                    }
                }
            )
        }
    }

    /**
     * 停止 RokidLink：直接停止眼镜端应用并清除运行状态。
     * 停止后需用户再次点击启动按键才会重新标记为运行中。
     */
    internal fun stopRokidLinkOnGlasses() {
        rokidLinkUserStarted = false
        activity.runWithPrerequisites {
            activity.log(activity.getString(R.string.log_closing_rokidlink))
            // 放 IO：stopApp 首段是 session.cleanup()，主线程同步调用会 ANR（见 runCxrOperation）
            runCxrOperation {
                activity.cxrL.stopApp(
                    packageName = "com.rokidlab.rokidlink",
                    onStopResult = { success ->
                        if (success) {
                            activity.log(activity.getString(R.string.log_rokidlink_closed))
                        } else {
                            activity.log(activity.getString(R.string.log_rokidlink_close_failed))
                        }
                        activity.runOnUiThread {
                            activity.screenMirrorState = activity.screenMirrorState.copy(rokidLinkRunning = false)
                            activity.phoneMirrorState = activity.phoneMirrorState.copy(rokidLinkRunning = false)
                            activity.fileManagerState = activity.fileManagerState.copy(rokidLinkRunning = false)
                        }
                    }
                )
            }
        }
    }

    internal fun reinstallRokidLinkOnGlasses(onDone: ((Boolean) -> Unit)? = null) {
        activity.settingsReinstallError = null
        rokidLinkUserStarted = false
        rokidLinkAutoRunning = false
        activity.runWithPrerequisites {
            runCxrOperation {
                // 先查询是否已安装：未安装则直接纯安装，避免多余的卸载清掉眼镜端本地数据
                var alreadyInstalled: Boolean? = null
                activity.cxrL.queryInstalledApps(
                    packageNames = listOf("com.rokidlab.rokidlink"),
                    onResult = { _, installed -> alreadyInstalled = installed },
                    onComplete = {
                        if (alreadyInstalled == false) {
                            // 确定未安装：跳过 stop/uninstall，直接安装
                            activity.log(activity.getString(R.string.log_installing_rokidlink))
                            installRokidLinkToGlasses(onResult = onDone)
                        } else {
                            // 已安装（或查询失败无法确定）：保留原有的先停后卸再装语义
                            activity.log(activity.getString(R.string.log_closing_rokidlink))
                            activity.cxrL.stopApp(
                                packageName = "com.rokidlab.rokidlink",
                                onStopResult = { success ->
                                    activity.log(if (success) activity.getString(R.string.log_rokidlink_closed) else activity.getString(R.string.log_rokidlink_close_failed))
                                },
                            )
                            runCxrOperation {
                                delay(300)
                                activity.log(activity.getString(R.string.log_uninstalling_rokidlink))
                                activity.cxrL.uninstallApp(
                                    packageName = "com.rokidlab.rokidlink",
                                    onUninstallResult = { uninstalled ->
                                        activity.log(if (uninstalled) activity.getString(R.string.log_rokidlink_uninstalled) else activity.getString(R.string.log_rokidlink_uninstall_failed))
                                        activity.runOnUiThread {
                                            activity.screenMirrorState = activity.screenMirrorState.copy(rokidLinkRunning = false)
                                            activity.phoneMirrorState = activity.phoneMirrorState.copy(rokidLinkRunning = false)
                                            activity.fileManagerState = activity.fileManagerState.copy(rokidLinkRunning = false)
                                        }
                                        if (!uninstalled) {
                                            // 卸载失败必须中止：旧版 debug 签名与新版 release 签名冲突时，
                                            // 强行覆盖安装会让眼镜端系统报 INSTALL_FAILED_UPDATE_INCOMPATIBLE
                                            activity.settingsReinstallError = activity.getString(R.string.log_rokidlink_uninstall_failed)
                                            onDone?.invoke(false)
                                            return@uninstallApp
                                        }
                                        runCxrOperation {
                                            delay(500)
                                            installRokidLinkToGlasses(onResult = onDone)
                                        }
                                    },
                                )
                            }
                        }
                    }
                )
            }
        }
    }

    /** 引导完成后自动启动眼镜端 RokidLink 服务 */
    internal fun autoStartRokidLink() {
        if (activity.prerequisitesState.currentGuideStep != GuideStep.READY) return
        android.util.Log.i("RokidLab", "Guide completed, auto-starting RokidLink on glasses...")
        // 放 IO：本方法是「引导页跳过/发送按钮」与商店启动按钮的同步回调，
        // 而 launchApp 首段 session.cleanup() 会拆 ADB 会话 / 断 CXR 链路（阻塞 IO）→ ANR
        runCxrOperation {
            activity.cxrL.launchApp("com.rokidlab.rokidlink", onLaunchResult = { launched ->
                activity.runOnUiThread {
                    if (launched) {
                        activity.log(activity.getString(R.string.log_rokidlink_autostarted))
                        android.util.Log.i("RokidLab", "RokidLink auto-started on glasses")
                    } else {
                        android.util.Log.w("RokidLab", "RokidLink auto-start failed")
                    }
                }
            })
        }
    }

    /**
     * 每次进前台确保眼镜端 RokidLink 在运行（用户无需手动点开）。
     * 仅在引导已完成（GuideStep.READY）且尚未运行/未手动启动时尝试 launchApp，
     * 成功回调后置 rokidLinkAutoRunning，避免重复拉起。
     */
    internal fun ensureRokidLinkRunning() {
        if (activity.prerequisitesState.currentGuideStep != GuideStep.READY) return
        if (rokidLinkAutoRunning || rokidLinkUserStarted) return
        // 放 IO：见 runCxrOperation（主页/商店一进来就走这里，眼镜离线时会阻塞主线程 → ANR）
        runCxrOperation {
            activity.cxrL.launchApp("com.rokidlab.rokidlink", onLaunchResult = { launched ->
                rokidLinkAutoRunning = launched
                if (launched) {
                    activity.log(activity.getString(R.string.log_rokidlink_autostarted))
                } else {
                    android.util.Log.w("RokidLab", "ensureRokidLinkRunning: launch failed")
                }
            })
        }
    }

    /**
     * 退出时关闭眼镜端 RokidLink（仅在进程真正退出时调用）。
     * 旋转/配置变更触发的 onDestroy 因 isFinishing=false 不会进入。
     * 进程直接被系统 kill 时 onDestroy 不一定回调，已知限制。
     */
    internal fun stopRokidLinkNow() {
        rokidLinkUserStarted = false
        rokidLinkAutoRunning = false
        // 放 IO：onDestroy 在主线程，stopApp 首段 session.cleanup() 为阻塞 IO → 退出时 ANR
        runCxrOperation {
            runCatching {
                activity.cxrL.stopApp(
                    packageName = "com.rokidlab.rokidlink",
                    onStopResult = { /* 退出时回调不保证抵达，忽略 */ },
                )
            }
        }
    }
}
