package com.aliothmoon.maafw.gradle

import org.gradle.api.Project
import java.io.File

/**
 * A standalone checkout versions itself. When this checkout is a submodule, walk through any
 * nested superprojects and version from the outermost repository instead.
 */
private fun Project.versionGitWorkingDir(): File {
    // Four callers each walked the whole chain, and every `git rev-parse` is a process spawn.
    // Kept on the root project extras so the cache dies with the build, not with the daemon
    val extras = rootProject.extensions.extraProperties
    (extras.properties[GIT_WORKING_DIR_KEY] as? File)?.let { return it }

    var workingDir = rootProject.projectDir
    while (true) {
        val superproject = providers.exec {
            workingDir(workingDir)
            commandLine("git", "rev-parse", "--show-superproject-working-tree")
            isIgnoreExitValue = true
        }.standardOutput.asText.get().trim()
        if (superproject.isEmpty()) break
        workingDir = File(superproject)
    }
    extras.set(GIT_WORKING_DIR_KEY, workingDir)
    return workingDir
}

private const val GIT_WORKING_DIR_KEY = "maafw.gitWorkingDir"

/**
 * versionCode counts commits in the selected version repo; the build fails without a git checkout
 *
 * ⚠️ BAASMAM: **versionCode = fork 提交数 + 主仓库提交数**，两者都计入。
 *
 * ## 为什么不能用上游的实现
 *
 * 上游 `versionGitWorkingDir()` 会向上走到**最外层 superproject**，只取主仓库
 * 的提交数。但在 BAASMAM 里我们**同时改两处**：
 *
 *   - `agent/`（主仓库）—— 适配层，改动最频繁
 *   - `upstream/MaaFwApp`（fork）—— Kotlin 侧改动
 *
 * 只取一边都会出问题，两条路都实测踩过：
 *
 *   | 取谁的提交数 | 后果 |
 *   |---|---|
 *   | 只取主仓库 | 出 5 个功能不同的 APK，versionCode 只从 15 变到 21；<br>**同一 versionCode 对应多个不同 APK** → 覆盖安装被系统静默跳过 |
 *   | 只取 fork   | 改 `agent/` 时 fork 不动 → **versionCode 卡住不变**（同样是多包同号） |
 *
 * ## 现在的做法
 *
 * `fork 提交数 * 10000 + 主仓库提交数`，两边任一变化都会让 versionCode 递增。
 *
 * 乘 10000 是留足空间：主仓库提交数远小于 10000，不会进位串号；
 * 且 fork 每 +1，versionCode 至少 +10000，**严格单调递增**，
 * 不会出现「fork 加了提交但 versionCode 反而变小」。
 *
 * 注意 `gitVersionName()` 仍用 superproject —— 那是**显示用**的版本名
 * （让用户看到项目整体的版本），且它有 tag 兜底，不影响安装判断。
 */
internal fun Project.gitVersionCode(): Int {
    fun countCommits(dir: File): Int = providers.exec {
        workingDir(dir)
        commandLine("git", "rev-list", "--count", "HEAD")
    }.standardOutput.asText.get().trim().toInt()

    val forkCount = countCommits(rootProject.projectDir)

    // 主仓库：本仓库作为 submodule 时的 superproject。
    // 拿不到（比如单独 clone 了 fork）就按 0 算，此时 fork 提交数独自决定版本号。
    val mainCount = runCatching {
        val superproject = providers.exec {
            workingDir(rootProject.projectDir)
            commandLine("git", "rev-parse", "--show-superproject-working-tree")
            isIgnoreExitValue = true
        }.standardOutput.asText.get().trim()
        if (superproject.isEmpty()) 0 else countCommits(File(superproject))
    }.getOrDefault(0)

    return forkCount * 10_000 + mainCount
}

internal fun Project.gitVersionName(workingDir: File): String =
    versionNameFromDescribe(
        providers.exec {
            workingDir(workingDir)
            commandLine("git", "describe", "--tags", "--always")
            isIgnoreExitValue = true
        }.standardOutput.asText.get().trim(),
    )

/**
 * A tag on HEAD gives x.y.z, keeping any prerelease suffix. Past a release tag, patch is bumped by
 * one and alpha.<distance> appended. Past a prerelease tag the distance is appended to that
 * prerelease instead: the build still leads up to x.y.z, and bumping patch there would sort it
 * above the release, so the update check would never offer it. A describe output that does not
 * match degrades to itself instead of blocking the build, so a repository without a single tag
 * versions itself by short hash
 */
internal fun versionNameFromDescribe(desc: String): String {
    val match = DESCRIBE.matchEntire(desc) ?: return desc.removePrefix("v").ifEmpty { "0.0.0-dev" }
    val (major, minor, patch, pre, distance) = match.destructured
    return when {
        distance.isEmpty() && pre.isEmpty() -> "$major.$minor.$patch"
        distance.isEmpty() -> "$major.$minor.$patch-$pre"
        pre.isEmpty() -> "$major.$minor.${patch.toInt() + 1}-alpha.$distance"
        else -> "$major.$minor.$patch-$pre.$distance"
    }
}

private val DESCRIBE = Regex("""^v?(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z.]+))?(?:-(\d+)-g[0-9a-f]+)?$""")

internal fun Project.gitVersionName(): String = gitVersionName(versionGitWorkingDir())

/** The shell's own version, told apart from the packaged project's in the about card */
internal fun Project.gitOwnVersionName(): String = gitVersionName(rootProject.projectDir)

/** Empty when this checkout is not a submodule: there is no project around it to version */
internal fun Project.gitParentVersionName(): String {
    val parent = versionGitWorkingDir()
    return if (parent == rootProject.projectDir) "" else gitVersionName(parent)
}
