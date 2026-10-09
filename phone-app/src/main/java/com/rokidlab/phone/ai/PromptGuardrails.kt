package com.rokidlab.phone.ai

/**
 * **提示词护栏文件** —— 被注入到 system prompt、因此能影响模型行为，但**不该由模型自己改写**的文件。
 *
 * ## 为什么需要它
 *
 * 对应 Muse 那条「agent 不能改写自己运行的规则」的保护（其做法是把 `.git` / `.muse` /
 * `.agents` 在 workspace 内设为只读，写入一律停在人工审核）。
 *
 * 本项目里这条风险的真实形状如下：
 *
 * `下载/RokidLab规则.md` 会被 `AiConversationService` 读出来、作为【项目规则】注入
 * 每轮 system prompt（见 `PROJECT_RULES_RELPATH` 的用法）。它**本身就是一段指令** ——
 * 用户在里面写「生成代码前先确认」「不要动我的照片」这类约束。
 *
 * 于是存在两条与直觉相反的路径：
 *  - **删除它 = 提权**：约束消失，模型从此不受该文件里那些规则约束；
 *  - **改写它 = 提权**：往里写「所有操作都无需确认」这类文本，下一轮就被当成用户规则注入。
 *
 * ## 现状核查（2026-10-09，逐条确认而不是推断）
 *
 * | 路径 | 可达？ | 依据 |
 * |---|---|---|
 * | `edit_text_file` 改它 | **不可达** | 该工具只作用**私有项目镜像**，`downloads` 作用域不参与写 |
 * | `save_code_file` 写到它 | **不可达** | `WebTools.writeProjectFile` 强制 `Download/<project>/`：project 名正则 `[\w\-\u4e00-\u9fa5]{1,40}` 排除空与 `.`，`rel` 拒绝 `..` |
 * | `move_file` 移它 | **不可达** | 仅 `project` 作用域 |
 * | `run_shell` 改它 | **不可达** | 容器挂 `/mnt/lab`（= `Download/Lab`）与只读 `/mnt/download`，都到不了 `Download/` 根 |
 * | **`delete_file(scope="downloads")` 删它** | ⚠️ **可达** | `FileWorkspace.deleteDownload` 按 `DisplayName` 匹配，**不筛创建者** |
 *
 * 📌 所以这里**只**防删除一条 —— 按「不为不存在的问题加代码」的口径，
 *   其余四条路径经核查本就不可达，给它们加防护只会是空转的复杂度
 *   （而且那些"防护"永远不会有测试能证明它拦住了真实攻击）。
 *
 * ⚠️ 若将来新增了能写 `Download/` 根目录的能力，**必须回到本文件补判定**，
 *   并把上表那一行的结论更新 —— 否则这里会变成一份"曾经正确过"的文档。
 */
internal object PromptGuardrails {

    /**
     * 项目规则文件的文件名（相对手机「下载」目录）。
     *
     * ★ **唯一产地**：`AiConversationService.PROJECT_RULES_RELPATH`（读取端）与
     *   [FileWorkspace] 的删除防护（写入端）都引用这里，
     *   避免"改了读取路径忘了改防护清单"⇒ 防护静默失效。
     */
    const val PROJECT_RULES_FILE = "RokidLab规则.md"

    /**
     * 下载目录里受保护的护栏文件（**小写**比对，因为 MediaStore 的 DisplayName
     * 在不同文件系统/输入法下大小写与 Unicode 规范化都可能不同）。
     */
    private val GUARDED = setOf(PROJECT_RULES_FILE.lowercase())

    /**
     * 该相对路径（相对「下载」目录）是否指向护栏文件。
     *
     * ## 判据：**只认下载根**，不认子目录里的同名文件
     *
     * 读取端是固定的 `openDownloadInput(context, "RokidLab规则.md")` —— 即**只有下载根**
     * 的那一份会被注入提示词。所以 `下载/myproj/RokidLab规则.md` 删掉也不影响模型行为，
     * 拦它属于**过度保护**：用户在自己项目里放一个同名文件是他的自由，
     * 而一个连普通文件都删不掉的防护会被当成 bug。
     *
     * 📌 与判据⑥同源：为守住某条不变式而多拦了东西时，要问"能不能只拦真正有风险的那个"。
     *   这里能 —— 所以拦得精确，而不是"名字像就拦"。
     *
     * ⚠️ 比对用 lower + 去首尾空白：用户手工创建文件时产生的尾随空格
     *   （Windows 上从资源管理器新建文件很常见）会让 `==` 判不出，
     *   而那恰恰是最容易被"绕过"的一种写法。
     */
    fun isGuardedDownloadPath(relPath: String): Boolean {
        val normalized = relPath.trim().replace('\\', '/').trim('/')
        // 含斜杠 = 子目录里的文件，不参与提示词注入 ⇒ 不是护栏
        if (normalized.contains('/')) return false
        return normalized.trim().lowercase() in GUARDED
    }

    /**
     * 拒绝删除护栏文件时给模型的说明。
     *
     * 📌 按既有口径（差距③）：拒绝信息必须是「原因 + 下一步」，否则模型只会原样重试。
     *   这里明确指出**这不属于它该做的事**，并给出用户侧的出路 ——
     *   与 `ApprovalGate.denyUnconfirmed` 的写法同源。
     */
    fun refuseDeleteMessage(relPath: String): String {
        val name = relPath.trim().replace('\\', '/').trim('/').substringAfterLast('/')
        return "「$name」是**用户手写的规则文件**（它的内容会被逐轮注入你的系统提示，是你遵守的约定），" +
            "不能由你改写或删除 —— 删掉/改掉它等于修改你自己的行为准则。" +
            "如果用户确实想调整规则，请告诉用户：用文件管理器打开「下载/$name」自行编辑。"
    }
}
