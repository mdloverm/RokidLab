package com.rokidlab.phone.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SkillFetcher 链接分类与仓库页 URL 解析（纯函数部分）单测。
 * 只测 classify / parseRepoUrl 两类不依赖网络的纯函数。
 */
class SkillFetcherTest {

    // ═══════════════════ classify ═══════════════════

    @Test
    fun `zip 直链归为 ZIP`() {
        assertEquals(SkillFetcher.Kind.ZIP, SkillFetcher.classify("https://x.com/a/b/skills-pack.zip"))
        assertEquals(SkillFetcher.Kind.ZIP, SkillFetcher.classify("https://x.com/a/b.zip?download=1"))
    }

    @Test
    fun `md 直链归为 MD_FILE`() {
        assertEquals(SkillFetcher.Kind.MD_FILE, SkillFetcher.classify("https://x.com/skills/foo/SKILL.md"))
        assertEquals(
            SkillFetcher.Kind.MD_FILE,
            SkillFetcher.classify("https://raw.githubusercontent.com/o/r/main/skills/foo/SKILL.md"),
        )
        assertEquals(
            SkillFetcher.Kind.MD_FILE,
            SkillFetcher.classify("https://gitee.com/o/r/raw/main/skills/foo/SKILL.md"),
        )
    }

    @Test
    fun `github 与 gitee 仓库页归为 REPO_PAGE`() {
        assertEquals(SkillFetcher.Kind.REPO_PAGE, SkillFetcher.classify("https://github.com/o/r"))
        assertEquals(
            SkillFetcher.Kind.REPO_PAGE,
            SkillFetcher.classify("https://github.com/o/r/tree/main/skills"),
        )
        // 网页 blob 页即使指向 .md 文件页，也走仓库页解析（网页 HTML 不是 raw 内容）
        assertEquals(
            SkillFetcher.Kind.REPO_PAGE,
            SkillFetcher.classify("https://github.com/o/r/blob/main/skills/foo/SKILL.md"),
        )
        assertEquals(
            SkillFetcher.Kind.REPO_PAGE,
            SkillFetcher.classify("https://gitee.com/o/r/tree/master/skills"),
        )
        assertEquals(
            SkillFetcher.Kind.REPO_PAGE,
            SkillFetcher.classify("https://gitee.com/o/r/blob/master/skills/foo/SKILL.md"),
        )
    }

    @Test
    fun `无法识别链接归为 UNKNOWN`() {
        assertEquals(SkillFetcher.Kind.UNKNOWN, SkillFetcher.classify(""))
        assertEquals(SkillFetcher.Kind.UNKNOWN, SkillFetcher.classify("随便输入的一段文字"))
        assertEquals(SkillFetcher.Kind.UNKNOWN, SkillFetcher.classify("https://example.com/readme.txt"))
    }

    // ═══════════════════ parseRepoUrl ═══════════════════

    @Test
    fun `解析仓库根 URL`() {
        val info = SkillFetcher.parseRepoUrl("https://github.com/dlover1314/RokidLab")
        assertTrue(info != null)
        info!!
        assertEquals(false, info.gitee)
        assertEquals("dlover1314", info.owner)
        assertEquals("RokidLab", info.repo)
        assertNull(info.branch)
        assertNull(info.subPath)
    }

    @Test
    fun `解析 gitee 仓库根并识别 gitee 域`() {
        val info = SkillFetcher.parseRepoUrl("https://gitee.com/dlover1314/RokidLab")
        assertTrue(info != null)
        assertEquals(true, info!!.gitee)
        assertEquals("dlover1314", info.owner)
        assertEquals("RokidLab", info.repo)
    }

    @Test
    fun `解析 tree 目录页得到分支与子路径`() {
        val info = SkillFetcher.parseRepoUrl("https://github.com/o/r/tree/main/skills")
        assertTrue(info != null)
        info!!
        assertEquals("main", info.branch)
        assertEquals("skills", info.subPath)

        val deep = SkillFetcher.parseRepoUrl("https://gitee.com/o/r/tree/dev/skills/weather")
        assertTrue(deep != null)
        assertEquals("dev", deep!!.branch)
        assertEquals("skills/weather", deep.subPath)
    }

    @Test
    fun `解析 blob 文件页 URL`() {
        val info = SkillFetcher.parseRepoUrl("https://github.com/o/r/blob/main/skills/foo/SKILL.md")
        assertTrue(info != null)
        info!!
        assertEquals("main", info.branch)
        assertEquals("skills/foo/SKILL.md", info.subPath)
    }

    @Test
    fun `解析 git 后缀仓库名并容忍首尾空白`() {
        val info = SkillFetcher.parseRepoUrl("  https://gitee.com/o/r.git  ")
        assertTrue(info != null)
        assertEquals("r", info!!.repo)
    }

    @Test
    fun `非 github gitee 域名或畸形 URL 返回 null`() {
        assertNull(SkillFetcher.parseRepoUrl("https://example.com/o/r"))
        assertNull(SkillFetcher.parseRepoUrl("https://raw.githubusercontent.com/o/r/main/skills/x/SKILL.md"))
        assertNull(SkillFetcher.parseRepoUrl("not a url"))
        assertNull(SkillFetcher.parseRepoUrl("https://github.com"))
        assertNull(SkillFetcher.parseRepoUrl("https://github.com/"))
    }
}
