package com.rokidlab.phone.ai

import android.content.Context
import android.util.Log
import com.rokidlab.phone.platform.ProotShell
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** 产出文件的类别（决定聊天里出哪种卡：文本卡 / 图片卡 / 音视频卡） */
enum class OutputKind { TEXT, IMAGE, MEDIA }

/** 一次容器执行里新出现/被改写的文件（给聊天窗口的卡片用） */
data class ProducedFile(
    val path: String,
    val name: String,
    val sizeBytes: Long,
    /** 文本文件的字符数（非文本为 null） */
    val chars: Int?,
    val kind: OutputKind = OutputKind.TEXT,
)

/**
 * 识别「AI 用容器写出来的文件」。
 *
 * ## 为什么用"执行前后目录 diff"而不是解析命令
 * 写文件的方式太多：`echo a > f`、`tee`、`python x.py`（由脚本内部写）、`sed -i`、`git clone`…
 * 想靠正则从命令字符串里认出目标文件，注定漏（尤其"脚本内部写文件"这种）。
 * 反过来，**文件系统本身是唯一权威**：跑之前记一份目录快照、跑完再记一份，差集就是产出 ——
 * 与命令怎么写无关。
 *
 * ## 挂点
 * [com.rokidlab.phone.ai.tools.ShellToolProvider] 的 `run_shell`：那里本来就已经在跑完后
 * 调 [LabMediaScan] 补 MediaStore 索引（同一个"容器写完盘要善后"的位置）。
 *
 * ## 边界与代价（明确写出来，避免误以为它万能）
 *  - 只扫**共享目录**（`Download/Lab` 或未授权时的 App 私有降级目录，见 [ProotShell.shareStatus]）——
 *    容器里写到别处（`/root`、`/tmp`）的文件**扫不到**，那也不在用户可见范围，扫了没意义；
 *  - 文件多时快照有成本 ⇒ 上限 [MAX_ENTRIES] 条、跳过 `.git`/`node_modules` 这类目录，
 *    且只把**文本类**、且**不超过** [MAX_PICK] 条的成果交给 UI（一次产出几十个文件时全铺成卡片
 *    会把对话框刷屏）；
 *  - **只在 IO 线程调用**（走文件系统遍历）。
 */
internal object LabFileOutputs {

    private const val TAG = "LabFileOutputs"

    /** 单次快照最多记多少条（防病态目录把工具调用拖长） */
    private const val MAX_ENTRIES = 3000

    /** 一次执行最多报几个产出文件（其余只记日志） */
    private const val MAX_PICK = 3

    /** 单个文件超过这个大小就不读正文（只给卡片，不显示字数） */
    private const val MAX_READ_BYTES = 2L * 1024 * 1024

    /** 不参与 diff 的重目录（依赖/版本库，每次构建都会变，报出来只会刷屏） */
    private val SKIP_DIRS = setOf(".git", "node_modules", ".cache", "__pycache__", ".venv", "venv", "site-packages")

    /** 图片产出（走图片卡，复用已有的预览/下载） */
    private val IMAGE_EXTS = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp")

    /** 音视频产出（容器里 ffmpeg 之类生成的，走播放卡） */
    private val MEDIA_EXTS = setOf(
        "mp4", "webm", "mkv", "mov", "3gp", "mp3", "wav", "m4a", "aac", "ogg", "flac", "opus",
    )

    /** 这个文件名该出哪种卡；不认识的扩展名返回 null（不出卡） */
    private fun kindOf(name: String): OutputKind? {
        val ext = TextFileKind.extOf(name)
        return when {
            ext.isEmpty() -> null
            IMAGE_EXTS.contains(ext) -> OutputKind.IMAGE
            MEDIA_EXTS.contains(ext) -> OutputKind.MEDIA
            TextFileKind.isTextLike(name, null) -> OutputKind.TEXT
            else -> null
        }
    }

    /** 产出文件的接收端（乐奇聊天页注册）；未注册时静默（眼镜语音等入口不显示卡片） */
    private val sink = AtomicReference<((List<ProducedFile>) -> Unit)?>(null)

    fun setSink(handler: ((List<ProducedFile>) -> Unit)?) {
        sink.set(handler)
    }

    /** 共享目录根；容器没装/未就绪返回 null（此时不扫） */
    private fun rootOf(ctx: Context): File? {
        val status = runCatching { ProotShell.shareStatus(ctx.applicationContext) }.getOrNull() ?: return null
        val root = File(status.hostPath)
        return root.takeIf { it.isDirectory }
    }

    /** 目录快照：path → mtime+size（够判断"新增或被重写"，不必算内容哈希） */
    fun snapshot(ctx: Context): Map<String, Long> {
        val root = rootOf(ctx) ?: return emptyMap()
        return runCatching {
            root.walkTopDown()
                .onEnter { dir -> !SKIP_DIRS.contains(dir.name) }
                .filter { it.isFile }
                .take(MAX_ENTRIES)
                .associate { it.absolutePath to (it.lastModified() * 31 + it.length()) }
        }.onFailure { Log.w(TAG, "snapshot failed: ${it.message}") }.getOrDefault(emptyMap())
    }

    /**
     * 与 [before] 比对并发布产出。**永不抛异常**（跑在工具返回路径上）。
     */
    fun publish(ctx: Context, before: Map<String, Long>) {
        val handler = sink.get() ?: return
        val after = snapshot(ctx)
        if (after.isEmpty()) return
        val changed = after.filter { (path, stamp) ->
            val old = before[path]
            old == null || old != stamp
        }
        if (changed.isEmpty()) return

        val picked = changed.keys
            .asSequence()
            .map { File(it) }
            .filter { it.isFile && it.length() > 0 }
            .mapNotNull { f ->
                val kind = kindOf(f.name) ?: return@mapNotNull null
                val chars = if (kind == OutputKind.TEXT && f.length() <= MAX_READ_BYTES) {
                    runCatching { f.readText().length }.getOrNull()
                } else {
                    null
                }
                ProducedFile(
                    path = f.absolutePath,
                    name = f.name,
                    sizeBytes = f.length(),
                    chars = chars,
                    kind = kind,
                )
            }
            .sortedByDescending { File(it.path).lastModified() }
            .take(MAX_PICK)
            .toList()

        if (picked.isEmpty()) {
            Log.i(TAG, "publish: ${changed.size} changed file(s), none text-like; skipped")
            return
        }
        Log.i(TAG, "publish: ${picked.size} produced file(s): ${picked.map { it.name }}")
        runCatching { handler.invoke(picked) }
            .onFailure { Log.w(TAG, "sink failed: ${it.message}") }
    }
}
