package com.ivieleague.kbuild.git

import java.io.File

/**
 * Git command-line wrapper for version detection and repository info.
 *
 * Example:
 * ```
 * val git = Git(File("."))
 * val branch = git.branch()
 * val status = git.status()
 * val tag = git.closestTag()
 * ```
 */
class Git(val directory: File) {

    /**
     * Check if the directory is a git repository.
     */
    fun isRepository(): Boolean = directory.resolve(".git").exists()

    /**
     * Run a git command and return stdout.
     * @throws GitException if the command fails
     */
    fun run(vararg args: String): String {
        require(directory.exists()) { "Directory does not exist: $directory" }

        val command = listOf("git") + args.toList()
        val process = ProcessBuilder(command)
            .directory(directory)
            .start()

        process.outputStream.close()
        val stdout = process.inputStream.readAllBytes().toString(Charsets.UTF_8)
        val stderr = process.errorStream.readAllBytes().toString(Charsets.UTF_8)
        val exitCode = process.waitFor()

        if (exitCode != 0) {
            throw GitException("Git command failed (exit $exitCode): ${command.joinToString(" ")}\n$stderr$stdout")
        }
        return stdout
    }

    /**
     * Run a git command, returning null if it fails instead of throwing.
     */
    fun runOrNull(vararg args: String): String? {
        return try {
            run(*args)
        } catch (e: GitException) {
            null
        }
    }

    /**
     * Get the current branch name.
     * Returns "HEAD" if in detached HEAD state.
     */
    fun branch(): String = run("rev-parse", "--abbrev-ref", "HEAD").trim()

    /**
     * Get the current commit hash (full SHA).
     */
    fun commitHash(): String = run("rev-parse", "HEAD").trim()

    /**
     * Get the current commit hash (short form).
     */
    fun commitHashShort(): String = run("rev-parse", "--short", "HEAD").trim()

    /**
     * Get the git status information.
     */
    fun status(): GitStatus {
        val raw = run("status")
        return GitStatus(
            raw = raw,
            branch = raw.substringAfter("On branch ", "").substringBefore('\n').trim(),
            workingTreeClean = raw.contains("working tree clean", ignoreCase = true) ||
                    raw.contains("nothing to commit", ignoreCase = true),
            ahead = parseAhead(raw),
            behind = parseBehind(raw)
        )
    }

    private fun parseAhead(status: String): Int {
        // "Your branch is ahead of 'origin/main' by 3 commits"
        return status.substringAfter("Your branch is ahead", "")
            .substringAfter("by ")
            .substringBefore(" commit")
            .toIntOrNull()
            // "and have 2 and 3 different commits each, respectively"
            ?: status.substringBefore(" different commits each", "")
                .substringAfter("and have ")
                .substringAfter(" and ")
                .toIntOrNull()
            ?: 0
    }

    private fun parseBehind(status: String): Int {
        // "Your branch is behind 'origin/main' by 2 commits"
        return status.substringAfter("Your branch is behind", "")
            .substringAfter("by ")
            .substringBefore(" commit")
            .toIntOrNull()
            // "and have 2 and 3 different commits each, respectively"
            ?: status.substringBefore(" different commits each", "")
                .substringAfter("and have ")
                .substringBefore(" and ")
                .toIntOrNull()
            ?: 0
    }

    /**
     * Get the exact tag at HEAD, or null if HEAD is not tagged.
     */
    fun exactTag(): String? {
        return runOrNull("describe", "--exact-match", "--tags")?.trim()
    }

    /**
     * Get the closest tag (most recent ancestor tag).
     * Returns the tag name, possibly with suffix like "v1.0.0-5-g1234abc"
     */
    fun closestTagDescribe(): String? {
        return runOrNull("describe", "--tags")?.trim()
    }

    /**
     * Get the closest tag name only (without commit count suffix).
     */
    fun closestTag(): String? {
        return closestTagDescribe()?.substringBeforeLast('-')?.substringBeforeLast('-')
    }

    /**
     * Get the closest tag with commit count (strips only the hash suffix).
     * e.g., "v1.0.0-5-gabcdef" becomes "v1.0.0-5"
     * This preserves the commit count which is useful for versioning.
     * by Claude
     */
    fun closestTagWithCount(): String? {
        return closestTagDescribe()?.substringBeforeLast('-')
    }

    /**
     * List all tags matching a pattern, sorted by version (newest first).
     */
    fun tagsMatching(pattern: String): List<String> {
        return runOrNull("tag", "-l", "--sort=-version:refname", pattern)
            ?.lines()
            ?.filter { it.isNotBlank() }
            ?.map { it.trim() }
            ?: emptyList()
    }

    /**
     * Get the commit hash for a given tag.
     */
    fun tagHash(tag: String): String = run("rev-list", "-n", "1", tag).trim()
}

/**
 * Git status information.
 */
data class GitStatus(
    val raw: String,
    val branch: String,
    val workingTreeClean: Boolean,
    val ahead: Int,
    val behind: Int
) {
    /**
     * True if the working tree is clean and in sync with remote.
     */
    val fullyPushed: Boolean get() = workingTreeClean && ahead == 0 && behind == 0
}

/**
 * Exception thrown when a git command fails.
 */
class GitException(message: String) : RuntimeException(message)

/**
 * Check if running in a CI environment.
 */
val isCI: Boolean
    get() = System.getenv("GITHUB_ACTIONS") == "true" ||
            System.getenv("TRAVIS") == "true" ||
            System.getenv("CIRCLECI") == "true" ||
            System.getenv("GITLAB_CI") == "true" ||
            System.getenv("CI") == "true"
