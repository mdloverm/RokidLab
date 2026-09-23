package com.rokidlab.phone.ai

import android.content.Context
import android.media.MediaScannerConnection
import android.util.Log
import com.rokidlab.phone.platform.ProotShell
import java.io.File

/**
 * 把本机 Linux 容器写在共享目录里的**产出**回填进 MediaStore。
 *
 * ## 为什么需要它（这是两个视图对不上的那一半）
 * 容器的 `/mnt/lab` 挂的是 `Download/Lab` 这个**真实路径**，写文件走内核 `write()`
 * —— 完全绕开 `MediaProvider`。而 `FileWorkspace` / `WebTools` 读同一个目录走的是
 * `MediaStore.Downloads` + `RELATIVE_PATH`。于是：
 *  - 容器里 `echo hi > /mnt/lab/a.txt` 之后，用户在文件管理器里**看得到**（那是真实的文件系统）；
 *  - 但 `list_files(scope="downloads")` 查不到它、`delete_file` 也找不到那一行
 *    —— 因为 MediaStore 的索引里根本没有这条记录。
 *
 * `MediaScannerConnection.scanFile` 正是"补索引"的标准手段：给它路径，它把记录插进/更新到
 * MediaStore。本类把它收成一个入口，在每次容器执行之后调一次。
 *
 * ## 三条设计约束
 *  - **只在公共目录时扫**：降级到 App 私有目录时那个目录本就不在媒体库里，扫了是白跑；
 *  - **异步**：[MediaScannerConnection.scanFile] 内部把活交给 MediaScanner 服务，不阻塞调用方
 *    （工具线程刚跑完一条几百秒的命令，不该再等索引）；
 *  - **有上限**：单次最多 [MAX_FILES] 个文件。这是一个**尽力而为**的补偿动作，
 *    绝不能让一个病态的目录把工具调用拖长 —— 失败只记日志，不影响工具结果。
 */
internal object LabMediaScan {

    private const val TAG = "LabMediaScan"

    /** 单次扫描文件数上限（够覆盖正常使用；超出部分留待下次执行时补扫） */
    private const val MAX_FILES = 300

    /**
     * 扫一遍共享目录并把文件登记进 MediaStore。**永不抛异常**（它跑在工具返回路径上）。
     */
    fun scanLabOutputs(context: Context) {
        val appContext = context.applicationContext
        val status = runCatching { ProotShell.shareStatus(appContext) }.getOrNull() ?: return
        // 降级在 App 私有目录（未授「所有文件访问」）时不扫：那个目录不在媒体库的可见范围内
        if (!status.publicDownload) return
        val root = File(status.hostPath)
        if (!root.isDirectory) return

        val files = runCatching {
            root.walkTopDown()
                .filter { it.isFile }
                .take(MAX_FILES)
                .map { it.absolutePath }
                .toList()
        }.getOrElse {
            Log.w(TAG, "scanLabOutputs: enumerate failed: ${it.message}")
            return
        }
        if (files.isEmpty()) return

        runCatching {
            MediaScannerConnection.scanFile(appContext, files.toTypedArray(), null, null)
            Log.i(TAG, "scanLabOutputs: scanned ${files.size} file(s) under ${status.hostPath}")
        }.onFailure {
            Log.w(TAG, "scanLabOutputs: scan failed: ${it.javaClass.simpleName}: ${it.message}")
        }
    }
}
