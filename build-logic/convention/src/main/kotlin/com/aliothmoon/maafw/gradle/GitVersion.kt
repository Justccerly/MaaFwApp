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
 * ⚠️ BAASMAM: **versionCode 用本仓库（fork）的提交数，不用最外层 superproject。**
 *
 * 上游的 `versionGitWorkingDir()` 会向上走到最外层仓库 —— 那是为「本仓库作为
 * 别人的 submodule 发布」设计的。但在 BAASMAM 里，我们**只改 fork 和 agent**，
 * 主仓库提交数增长很慢，导致：
 *
 *   - 出了 5 个功能不同的 APK，versionCode 只从 15 变到 21
 *   - **同一 versionCode 可能对应多个不同的 APK**，Android 的安装/更新
 *     判断因此不可靠（覆盖安装可能被当成「版本相同」而静默跳过）
 *   - 真机排查时无法从 `Version : xxx (NN)` 反推装的是哪个包
 *
 * 所以改为**始终用本仓库的提交数**。fork 提交数增长快，每次出包必然递增。
 *
 * 注意 `gitVersionName()` 仍用 superproject —— 那是**显示用**的版本名
 * （让用户看到项目整体的版本），且它有 tag 兜底，不影响安装判断。
 */
internal fun Project.gitVersionCode(): Int {
    val ownDir = rootProject.projectDir
    return providers.exec {
        workingDir(ownDir)
        commandLine("git", "rev-list", "--count", "HEAD")
    }.standardOutput.asText.get().trim().toInt()
}

/**
 * A tag on HEAD gives x.y.z, keeping any prerelease suffix; a tag further back bumps patch by one
 * and appends alpha.<distance>. A describe output that does not match degrades to itself instead
 * of blocking the build, so a repository without a single tag versions itself by short hash
 */
internal fun Project.gitVersionName(workingDir: File): String {
    val desc = providers.exec {
        workingDir(workingDir)
        commandLine("git", "describe", "--tags", "--always")
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim()
    val match = Regex("""^v?(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z.]+))?(?:-(\d+)-g[0-9a-f]+)?$""")
        .matchEntire(desc) ?: return desc.removePrefix("v").ifEmpty { "0.0.0-dev" }
    val (major, minor, patch, pre, distance) = match.destructured
    return when {
        distance.isNotEmpty() -> "$major.$minor.${patch.toInt() + 1}-alpha.$distance"
        pre.isNotEmpty() -> "$major.$minor.$patch-$pre"
        else -> "$major.$minor.$patch"
    }
}

internal fun Project.gitVersionName(): String = gitVersionName(versionGitWorkingDir())

/** The shell's own version, told apart from the packaged project's in the about card */
internal fun Project.gitOwnVersionName(): String = gitVersionName(rootProject.projectDir)

/** Empty when this checkout is not a submodule: there is no project around it to version */
internal fun Project.gitParentVersionName(): String {
    val parent = versionGitWorkingDir()
    return if (parent == rootProject.projectDir) "" else gitVersionName(parent)
}
