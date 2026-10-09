package com.rokidlab.phone.demo

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.rokidlab.phone.R
import com.rokidlab.phone.adb.AdbShellClient
import com.rokidlab.phone.ai.ToolRegistry
import com.rokidlab.phone.util.LogCollector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 双端录像（乐奇工具页「双端录像」）：眼镜 screenrecord + 手机 MediaProjection 同时录，
 * 结束后由 [SideBySideCompositor] 合成「左眼镜 / 右手机」拼屏 mp4；用户确认后存入系统相册。
 *
 * ## 为什么眼镜端走 ADB shell 而不是 LinkProtocol
 * screenrecord 是眼镜 ROM 自带命令，直接 shell 起即可 —— LinkProtocol 不动、眼镜端零改动。
 *
 * ## 时长与分段
 * 当前 [RECORD_SEC] = 175s，恰好落在分段触发点上 → 全程单段零拼接。
 * 分段能力保留：若把 [RECORD_SEC] 提到 175s 以上，segN 会在 175*(N-1) 秒提前 5s 起录
 * 形成重叠预卷、合成时裁掉，避免段间掉帧；ROM 不允许双实例时降级为顺序起段并记日志。
 *
 * ## 静默
 * 眼镜端不注入任何 UI（无 Toast / 无通知），screenrecord 本身无声无息；
 * 手机端状态栏的投屏图标与每次录制一次的系统授权框是 MediaProjection 的系统限制，无法去除。
 *
 * ## 双端对齐
 * 眼镜 seg1 文件出现时刻 T_g0 与手机 MediaRecorder.start 成功时刻 T_p 的差
 * [phoneDelayMs] = T_p - T_g0，合成时眼镜侧跳过前 phoneDelayMs 秒，
 * 使左右画面内容对齐（否则手机总是晚几秒起，开头对不上）。
 */
object DemoRecordManager {

    private const val TAG = "DemoRecordManager"

    /** 眼镜端录屏工作目录（眼镜侧绝对路径） */
    private const val GLASSES_DIR = "/sdcard/rokidlab_demo"

    /** 单段时长上限 = screenrecord --time-limit 上限 */
    private const val SEGMENT_SEC = 180

    /** 段间重叠预卷：下一段提前这么久起，合成时裁掉 */
    private const val OVERLAP_SEC = 5

    /** 有效段间隔 = 单段 - 重叠 */
    private const val SEGMENT_STEP_SEC = SEGMENT_SEC - OVERLAP_SEC

    /**
     * 录制时长（到时自动停；用户可提前点「停止录制」）。
     *
     * **175 = 单段上限**：分段调度在 175s 触发（判据 `175 < targetSec`），取 175 恰好
     * 不触发第二段，全程单段零拼接；眼镜 ROM 的 `--time-limit` 硬顶 180s 也不依赖。
     * 超过 175s 会走分段拼接路径（真机未验证）。合成耗时 ≈ 本值（渲染循环实时驱动）。
     */
    const val RECORD_SEC = 175

    sealed class State {
        /** 空闲（可开始） */
        object Idle : State()

        /** 检查 ADB / screenrecord 可用性 */
        object Preparing : State()

        /** 录制中：elapsedSec 已录秒数，totalSec 目标秒数 */
        data class Recording(val elapsedSec: Int, val totalSec: Int) : State()

        /** 停止收尾（kill -INT 等 finalize） */
        object Stopping : State()

        /** 拉取眼镜分段（0..1） */
        data class Pulling(val progress: Float) : State()

        /** 拼屏合成（0..1） */
        data class Composing(val progress: Float) : State()

        /** 完成：output 为可分享的 mp4 */
        data class Done(val output: File) : State()

        /** 失败：reason 为用户可见文案 */
        data class Failed(val reason: String) : State()
    }

    @Volatile
    var state: State = State.Idle
        private set

    /** 状态订阅（UI 用 collectAsState） */
    private val listeners = mutableListOf<(State) -> Unit>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 本次录制会话标识：防止上一轮协程的延迟调度污染新一轮 */
    private val session = java.util.concurrent.atomic.AtomicLong(0)

    // ── 会话内共享（仅录制协程线程读写）──
    private var glassesSegFiles = mutableListOf<Pair<String, Long>>() // remote path -> 文件出现时刻 epoch ms
    private var phoneDelayMs = 0L
    private var targetSec = 0
    private var appContext: Context? = null

    fun addListener(l: (State) -> Unit) {
        synchronized(listeners) { listeners.add(l) }
    }

    fun removeListener(l: (State) -> Unit) {
        synchronized(listeners) { listeners.remove(l) }
    }

    private fun setState(s: State) {
        state = s
        val snapshot = synchronized(listeners) { listeners.toList() }
        for (l in snapshot) runCatching { l(s) }
    }

    /**
     * 开始一次双端录制。
     * @param resultCode MediaProjection 授权结果（UI 侧 launcher 拿到后传入）
     * @param projectionData 授权 intent
     */
    fun start(context: Context, resultCode: Int, projectionData: Intent) {
        if (state !is State.Idle && state !is State.Done && state !is State.Failed) return
        val app = context.applicationContext as Context
        appContext = app
        val sessionId = session.incrementAndGet()
        glassesSegFiles = mutableListOf()
        phoneDelayMs = 0L
        targetSec = RECORD_SEC

        setState(State.Preparing)
        scope.launch {
            val failure = runCatching { runSession(app, sessionId, resultCode, projectionData) }
                .exceptionOrNull()
            if (failure != null && session.get() == sessionId) {
                LogCollector.e(TAG, "demo record session failed", failure)
                // 失败时尽力清掉眼镜端残留进程，避免 screenrecord 挂后台一直录
                stopGlassesRecordingQuietly()
                setState(State.Failed(app.getString(R.string.demo_err_start)))
            }
        }
    }

    /** 手动停止（录制中可调；到时自动停也走同一条路） */
    fun stop() {
        if (state !is State.Recording && state !is State.Preparing) return
        stopRequested = true
    }

    @Volatile
    private var stopRequested = false

    private suspend fun runSession(
        app: Context,
        sessionId: Long,
        resultCode: Int,
        projectionData: Intent,
    ) {
        // ── 1. ADB / screenrecord 可用性检查 ──
        var adb = ToolRegistry.adbClient(app) ?: run {
            setState(State.Failed(app.getString(R.string.demo_err_adb)))
            return
        }
        val which = withContext(Dispatchers.IO) {
            synchronized(ToolRegistry.adbLock) {
                adb.executeShellCommand("which screenrecord", 8000)
            }
        }.trim()
        if (which.isEmpty()) {
            setState(State.Failed(app.getString(R.string.demo_err_screenrecord)))
            return
        }
        withContext(Dispatchers.IO) {
            synchronized(ToolRegistry.adbLock) {
                adb.executeShellCommand("mkdir -p $GLASSES_DIR && rm -f $GLASSES_DIR/seg*.mp4", 8000)
            }
        }

        // ── 2. 眼镜 seg1 先起（文件出现即视为时间基准；共享会话可能中途死亡，带一次重建重试）──
        var tG0 = startSegment(adb, 0)
        if (tG0 == null) {
            adb = ToolRegistry.adbClient(app) ?: adb
            tG0 = startSegment(adb, 0)
        }
        if (tG0 == null) {
            setState(State.Failed(app.getString(R.string.demo_err_start)))
            return
        }
        glassesSegFiles.add("$GLASSES_DIR/seg0.mp4" to tG0)

        // ── 3. 手机端起录（授权数据转交前台服务；服务回报真实起录时刻）──
        PhoneScreenRecordService.begin(app, resultCode, projectionData, targetSec * 1000L)
        val tP = PhoneScreenRecordService.awaitStarted(30_000L)
        if (tP <= 0) {
            LogCollector.e(TAG, "phone recorder failed to start; aborting glasses side")
            stopGlassesRecordingQuietly()
            setState(State.Failed(app.getString(R.string.demo_err_start)))
            return
        }
        phoneDelayMs = (tP - tG0).coerceAtLeast(0L)
        Log.i(TAG, "phoneDelay=${phoneDelayMs}ms")

        // ── 4. 录制主循环：计时 + 分段调度 ──
        val startElapsed = System.currentTimeMillis()
        var nextSegIndex = 1
        stopRequested = false
        setState(State.Recording(0, targetSec))
        while (session.get() == sessionId) {
            val elapsedMs = System.currentTimeMillis() - startElapsed
            val elapsedSec = (elapsedMs / 1000).toInt()
            if (stopRequested || elapsedSec >= targetSec) break

            setState(State.Recording(elapsedSec.coerceAtMost(targetSec), targetSec))

            // 分段：segN 在全局 175*(N-1) 秒起（seg1 已在 t=0 起过）
            val scheduledAtMs = nextSegIndex * SEGMENT_STEP_SEC * 1000L
            if (elapsedMs >= scheduledAtMs && nextSegIndex * SEGMENT_STEP_SEC < targetSec) {
                val idx = nextSegIndex
                val remote = "$GLASSES_DIR/seg$idx.mp4"
                val started = startSegmentWithOverlap(adb, idx)
                if (started == null) {
                    LogCollector.w(TAG, "seg$idx concurrent start rejected, fallback to sequential")
                    // 降级：等当前段自然结束（time-limit 到点）再起下一段，
                    // 代价是段间最多 ~1 帧缝隙（无重叠预卷）
                    delay((OVERLAP_SEC + 1) * 1000L)
                    val seq = startSegment(adb, idx)
                    if (seq == null) {
                        LogCollector.e(TAG, "seg$idx sequential start failed")
                    } else {
                        glassesSegFiles.add(remote to seq)
                    }
                }
                nextSegIndex++
            }
            delay(500)
        }
        if (session.get() != sessionId) return

        // ── 5. 收尾：手机停 + 眼镜 kill -INT finalize ──
        setState(State.Stopping)
        PhoneScreenRecordService.requestStop(app)
        val phonePath = PhoneScreenRecordService.awaitFinished(20_000L)
        stopGlassesRecording()
        if (session.get() != sessionId) return
        if (phonePath == null) {
            setState(State.Failed(app.getString(R.string.demo_err_start)))
            return
        }

        // ── 6. 拉取眼镜分段 ──
        val localDir = File(app.getExternalFilesDir(null), "demo").apply { mkdirs() }
        val locals = mutableListOf<Pair<String, Long>>() // localPath -> 文件出现时刻
        val n = glassesSegFiles.size
        glassesSegFiles.forEachIndexed { i, (remote, appear) ->
            val local = File(localDir, "glasses_seg$i.mp4").absolutePath
            // 录制期间共享会话可能被升级逻辑释放：拉取前重取会话，失败再取一次重试
            val ok = withContext(Dispatchers.IO) {
                synchronized(ToolRegistry.adbLock) {
                    fun pullOnce(): Boolean {
                        val c = adbClient() ?: return false
                        val size = c.getRemoteFileSize(remote)
                        return c.pullFile(remote, local, size)
                    }
                    pullOnce() || pullOnce()
                }
            }
            if (!ok) {
                LogCollector.e(TAG, "pull failed: $remote")
                setState(State.Failed(app.getString(R.string.demo_err_pull)))
                return
            }
            locals.add(local to appear)
            if (session.get() != sessionId) return
            setState(State.Pulling((i + 1).toFloat() / n))
        }

        // ── 7. 合成 ──
        setState(State.Composing(0f))
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val out = File(localDir, "DualScreen_$stamp.mp4")
        val ok = SideBySideCompositor.compose(
            glassesSegments = locals,
            phoneSegment = phonePath,
            phoneDelayMs = phoneDelayMs,
            output = out.absolutePath,
            onProgress = { p -> if (session.get() == sessionId) setState(State.Composing(p)) },
        )
        if (session.get() != sessionId) return
        if (!ok) {
            setState(State.Failed(app.getString(R.string.demo_err_compose)))
            return
        }
        // 清理眼镜端分段，避免占满存储（合成耗时长，会话可能已中途重建，重取）
        withContext(Dispatchers.IO) {
            synchronized(ToolRegistry.adbLock) {
                adbClient()?.executeShellCommand("rm -f $GLASSES_DIR/seg*.mp4", 8000)
            }
        }
        setState(State.Done(out))
    }

    /**
     * 起一段眼镜录屏并等文件出现。
     * @return 文件出现时刻 epoch ms；null = 起录失败
     */
    private suspend fun startSegment(adb: AdbShellClient, index: Int): Long? {
        val remote = "$GLASSES_DIR/seg$index.mp4"
        // ⚠️ 眼镜 v1 shell: 服务会分配 PTY：sh 退出 → PTY 关闭 → 内核向前台进程组广播 SIGHUP，
        // screenrecord 在 setsid() 完成前收到就死（真机 init 日志：status 129 = SIGHUP，32ms 内）。
        // PC adb 走 v2 raw 无 PTY，测不出此问题。三重修复：
        // 1. nohup 放最外层 —— exec 后立即把 SIGHUP 设为 SIG_IGN，setsid/screenrecord 全链继承免疫；
        // 2. setsid 脱离 PTY 前台进程组（彻底不受后续 PTY 关闭影响）；
        // 3. 末尾 sleep 1 —— 拖住 sh 退出 1s，给 1/2 完成的窗口（App 端只多等 1s，无感）。
        // stderr 落盘 segN.err：起录失败时可读出真实死因。
        val out = withContext(Dispatchers.IO) {
            synchronized(ToolRegistry.adbLock) {
                adb.executeShellCommand(
                    "nohup setsid screenrecord --time-limit $SEGMENT_SEC --bit-rate 4000000 $remote </dev/null >/dev/null 2>$GLASSES_DIR/seg$index.err & echo started; sleep 1",
                    5000,
                )
            }
        }.trim()
        if (!out.endsWith("started")) {
            // 会话死亡 / 蓝牙链路异常：命令可能根本没执行到
            LogCollector.e(TAG, "seg$index start command suspicious, echo=[$out]")
        }
        Log.i(TAG, "seg$index start -> $out")
        return awaitSegmentFile(adb, index)
    }

    /**
     * 在上一段还剩 ~5s 时提前起下一段（重叠预卷），成功则登记分段。
     * @return 文件出现时刻；null = 并发被拒（ROM 不允许双实例）
     */
    private suspend fun startSegmentWithOverlap(adb: AdbShellClient, index: Int): Long? {
        val pidsBefore = countScreenrecord(adb)
        val appear = startSegment(adb, index) ?: return null
        delay(1500)
        val pidsAfter = countScreenrecord(adb)
        // 检查时旧段按调度必然还剩 ≥3s 存活：pid 没多出来 = 新段没起来（并发被拒）
        if (pidsAfter.size <= pidsBefore.size) {
            LogCollector.w(TAG, "concurrent screenrecord rejected: before=${pidsBefore.size} after=${pidsAfter.size}")
            return null
        }
        glassesSegFiles.add("$GLASSES_DIR/seg$index.mp4" to appear)
        return appear
    }

    private suspend fun countScreenrecord(adb: AdbShellClient): List<String> =
        withContext(Dispatchers.IO) {
            synchronized(ToolRegistry.adbLock) { adb.executeShellCommand("pidof screenrecord", 5000) }
        }.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }

    /** 轮询等分段文件出现（编码器冷启动 + 慢链路余量给到 10s） */
    private suspend fun awaitSegmentFile(adb: AdbShellClient, index: Int): Long? {
        val remote = "$GLASSES_DIR/seg$index.mp4"
        repeat(40) {
            val size = withContext(Dispatchers.IO) {
                synchronized(ToolRegistry.adbLock) { adb.getRemoteFileSize(remote) }
            }
            if (size > 0) return System.currentTimeMillis()
            delay(250)
        }
        // 10s 仍未出现：抓 screenrecord 的 stderr 与进程状态定位死因（落 App 日志面板）
        val err = withContext(Dispatchers.IO) {
            synchronized(ToolRegistry.adbLock) {
                adb.executeShellCommand("cat $GLASSES_DIR/seg$index.err 2>/dev/null", 5000)
            }
        }.trim()
        val pids = countScreenrecord(adb)
        LogCollector.e(TAG, "seg$index file never appeared: pids=[$pids] stderr=[${err.take(300)}]")
        return null
    }

    /** SIGINT 让 screenrecord 正确 finalize mp4（SIGKILL 会留下没有 moov 的坏文件） */
    private suspend fun stopGlassesRecording() {
        // 长录制期间共享 ADB 会话可能已被线路升级/存活校验逻辑释放重建，
        // runSession 里缓存的引用会失效 —— 每次操作都重取（transport.get 复用活会话）
        val adb = adbClient() ?: run {
            LogCollector.e(TAG, "stopGlasses: no adb session")
            return
        }
        withContext(Dispatchers.IO) {
            synchronized(ToolRegistry.adbLock) {
                adb.executeShellCommand("kill -INT \$(pidof screenrecord) 2>/dev/null", 8000)
            }
        }
        // 等 finalize：所有 screenrecord 进程退出（最长 5s）
        repeat(25) {
            val pids = withContext(Dispatchers.IO) {
                synchronized(ToolRegistry.adbLock) { adb.executeShellCommand("pidof screenrecord", 5000) }
            }.trim()
            if (pids.isEmpty()) return
            delay(200)
        }
        LogCollector.w(TAG, "screenrecord still alive after SIGINT, forcing kill")
        withContext(Dispatchers.IO) {
            synchronized(ToolRegistry.adbLock) {
                adb.executeShellCommand("kill -9 \$(pidof screenrecord) 2>/dev/null", 8000)
            }
        }
    }

    /** 当前共享 ADB 会话（复用或按需重建）；null = 无可用路径 */
    private fun adbClient(): AdbShellClient? = appContext?.let { ToolRegistry.adbClient(it) }

    /** 异常路径的静默清理：不改变当前状态 */
    private suspend fun stopGlassesRecordingQuietly() {
        runCatching { stopGlassesRecording() }
    }

    /**
     * 把成片写进系统相册（Movies/ 下，相册可见）。
     * 耗时（拷贝整个 mp4），调用方应在 IO 线程执行。
     * @return true = 已入库
     */
    fun saveToGallery(context: Context, file: File): Boolean {
        if (!file.exists() || file.length() <= 0) return false
        return try {
            val resolver = context.contentResolver
            val pending = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES)
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, pending)
                ?: return false
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                ?: return false
            val done = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
            true
        } catch (e: Exception) {
            LogCollector.e(TAG, "saveToGallery failed", e)
            false
        }
    }

    /** 保存/放弃后回到空闲态（按钮从「保存视频」变回「双端录像」） */
    fun reset() {
        if (state is State.Done || state is State.Failed) setState(State.Idle)
    }
}
