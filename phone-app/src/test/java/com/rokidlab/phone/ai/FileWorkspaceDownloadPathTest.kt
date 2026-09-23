package com.rokidlab.phone.ai

import android.os.Environment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 下载作用域的**目录解析**（[FileWorkspace.resolveDownloadPath]）回归测试。
 *
 * ## 锁的是哪个 bug
 * `read_text_file(scope="downloads")` 原先只取路径**第一段**当项目名（`Download/<首段>/`），
 * 而写侧（`WebTools.writeProjectFile`）与解压侧（[ArchiveTools.unzipFile]）用的都是
 * **完整路径**。于是 `unzip_file` 解出来的嵌套文件（`app/pages/index/index.ink`）
 * 永远读不到 —— 而两个工具的 schema 互相承诺了这个闭环，用户看到的是
 * "文件明明在、列得出来、就是读不出内容"。
 *
 * 这类 bug 的共性是**纯路径规则**，所以它值得一条不依赖 MediaStore / Context 的单测
 * （[FileWorkspace.resolveDownloadPath] 就是为它抽出来的纯函数）。
 */
class FileWorkspaceDownloadPathTest {

    /**
     * 剥掉「下载目录」前缀后剩下的相对目录。
     *
     * ⚠️ 不能把前缀写死成 `"Download"`：JVM 单测跑在 AGP 的 mockable android.jar 上，
     * `Environment.DIRECTORY_DOWNLOADS` 这个常量在那里被清成了 **null**
     * （首次写本测试时实测 actual = `"null/app/pages/index/"`）。
     * 好在本次回归要锁的是**目录层级**（"完整路径" 还是 "只取首段"），前缀由环境决定，
     * 所以统一剥掉再断言 —— 这样在真机与 mock 环境里都能跑。
     */
    private fun dirOf(r: Pair<String, String>): String =
        r.first.removePrefix("${Environment.DIRECTORY_DOWNLOADS}/")

    @Test
    fun `嵌套路径按完整目录定位`() {
        val r = FileWorkspace.resolveDownloadPath("", "app/pages/index/index.ink")!!
        assertEquals("app/pages/index/", dirOf(r))
        assertEquals("app/pages/index/index.ink", r.second)
    }

    @Test
    fun `单层路径与根目录文件`() {
        val nested = FileWorkspace.resolveDownloadPath("", "app/app.json")!!
        assertEquals("app/", dirOf(nested))
        assertEquals("app/app.json", nested.second)

        val root = FileWorkspace.resolveDownloadPath("", "readme.txt")!!
        assertEquals("根目录文件：目录部分为空", "", dirOf(root))
        assertEquals("readme.txt", root.second)
    }

    @Test
    fun `另传 project 时补在路径前面`() {
        val r = FileWorkspace.resolveDownloadPath("app", "pages/index/index.ink")!!
        assertEquals("app/pages/index/", dirOf(r))
        assertEquals("app/pages/index/index.ink", r.second)
    }

    @Test
    fun `path 已自带项目名时不会被重复拼接`() {
        val r = FileWorkspace.resolveDownloadPath("app", "app/pages/index.ink")!!
        assertEquals("app/pages/", dirOf(r))
        assertEquals("app/pages/index.ink", r.second)
    }

    @Test
    fun `反斜杠与首尾斜杠被归一`() {
        assertEquals(
            FileWorkspace.resolveDownloadPath("", "app/index.ink"),
            FileWorkspace.resolveDownloadPath("", "\\app\\index.ink\\"),
        )
    }

    @Test
    fun `逃逸与非法规格一律返回 null`() {
        // 空路径（读文件必须有具体目标）
        assertNull(FileWorkspace.resolveDownloadPath("", ""))
        // 路径穿越
        assertNull(FileWorkspace.resolveDownloadPath("", "../secret.txt"))
        assertNull(FileWorkspace.resolveDownloadPath("", "app/../../secret.txt"))
        // 段名不合规（段内空格起始、非法字符）
        // ⚠️ 注意：整串的首尾空白会先被 trim 掉，所以用例要打在**段中间**（"app/ x.txt"）
        assertNull(FileWorkspace.resolveDownloadPath("", "app/ x.txt"))
        assertNull(FileWorkspace.resolveDownloadPath("", "app/x:y.txt"))
        // 项目名不合规（与 WebTools 的项目名规则同源）
        assertNull(FileWorkspace.resolveDownloadPath("bad/name", "x.txt"))
        assertNull(FileWorkspace.resolveDownloadPath("bad name", "x.txt"))
    }
}
