// ── 发布前本地闸门（阶段二 #10）──
// 由 phone-app 与 RokidLink 两个模块共同 apply —— 单独构建眼镜端 release 时同样受门禁约束。
// （历史教训：checkProtocolSynced 只挂在 phone-app 侧，导致单跑 :RokidLink:assembleRelease 可绕过校验。）
//
// 1) checkGitClean —— 出 release 包（packageRelease）前 `git status --porcelain` 必须为空。
//    这是上线报告 B1「发布的不是仓库里的东西」的机器兜底：HEAD 里缺 52 个源码文件时，
//    本地能编过、clean checkout 直接失败。debug / 单测 / 仅编译不受影响（开发常态允许脏工作区）。
// 2) packageRelease 依赖 testDebugUnitTest —— 出 release 包必须先把单测跑绿，
//    避免「改了读写语义但没跑测试就出包」。
// 挂 `packageRelease` 而非 `preReleaseBuild`：门禁语义是「不许把脏工作区打成包」，
// 不应连带把 `compileReleaseKotlin` 这类纯编译校验也堵死。
//
// 逃逸阀（仅限本地临时验证，禁止用于正式出包）：-PallowDirtyWorktree=true

val checkGitClean by tasks.registering {
    group = "verification"
    description = "release 构建前校验模块所在 git 仓库无未提交改动（git status --porcelain 必须为空）"
    doLast {
        if (providers.gradleProperty("allowDirtyWorktree").orNull == "true") {
            logger.lifecycle("checkGitClean: 已由 -PallowDirtyWorktree=true 跳过（禁止用于正式出包）")
            return@doLast
        }
        // 锚定到模块所在 git 仓库根，不依赖守护进程当前工作目录：
        // 构建入口唯一化后根工程是外层壳（d:\rokidapp，源码目录全部未跟踪、永不洁净），
        // 旧实现受守护进程 CWD 漂移影响会误检壳工程状态，与「产物须与 Gitee 提交追溯对应」的语义不符。
        val topLevelExec = providers.exec {
            commandLine("git", "-C", project.projectDir.absolutePath, "rev-parse", "--show-toplevel")
            isIgnoreExitValue = true
        }
        if (topLevelExec.result.get().exitValue != 0) {
            error("checkGitClean: git rev-parse 执行失败——模块 ${project.projectDir} 不在 git 工作区内")
        }
        val repoRoot = topLevelExec.standardOutput.asText.get().trim()
        val execOutput = providers.exec {
            commandLine("git", "-C", repoRoot, "status", "--porcelain")
            isIgnoreExitValue = true
        }
        val exitCode = execOutput.result.get().exitValue
        if (exitCode != 0) {
            error("checkGitClean: git status 执行失败（exit=$exitCode）—— git 未安装或当前不在 git 工作区")
        }
        val dirty = execOutput.standardOutput.asText.get().trim()
        if (dirty.isNotEmpty()) {
            val lines = dirty.lines()
            error(
                buildString {
                    append("工作区不干净（${lines.size} 条），release 产物无法与提交追溯对应：\n")
                    lines.take(30).forEach { append("  ").append(it).append("\n") }
                    if (lines.size > 30) append("  …（其余 ${lines.size - 30} 条省略）\n")
                    append("先提交全部改动（含未跟踪的新源码/测试）再出 release 包，见 RULES §12.13。")
                },
            )
        }
        logger.lifecycle("checkGitClean: 工作区干净，release 产物可追溯")
    }
}

tasks.matching { it.name == "packageRelease" }.configureEach {
    dependsOn(checkGitClean)
    dependsOn("testDebugUnitTest")
}
