package com.ivieleague.kbuild.git

import com.ivieleague.kbuild.common.Version
import java.io.File

/**
 * Get the project version based on git state.
 *
 * Version is determined by:
 * 1. Finding the closest git tag (interpreted as semantic version)
 * 2. Analyzing the current branch name
 * 3. Checking if the working tree is clean
 *
 * Version logic:
 * - Standard branches (main, master, dev): Use tag version directly
 * - Version branches (version-X, version-X.Y): Pre-release for that version
 * - Other branches: Branch name becomes variant
 * - Dirty working tree: Adds "-local" suffix
 * - CI environments: Treated as clean
 *
 * Example:
 * ```
 * val version = gitVersion(File("."))
 * println("Building version: $version")
 * ```
 *
 * @param directory The git repository directory
 * @return The computed version
 */
fun gitVersion(directory: File): Version {
    val git = Git(directory)
    if (!git.isRepository()) {
        return Version(0, 0, 1, "nogit")
    }

    val branch = try {
        git.branch()
    } catch (e: GitException) {
        return Version(0, 0, 1, "nogit")
    }

    val isClean = try {
        git.status().workingTreeClean || isCI
    } catch (e: GitException) {
        false
    }

    // by Claude - Use closestTagWithCount to preserve commit count in version
    val tagVersion = try {
        git.closestTagWithCount()?.let { parseTagVersion(it) }
    } catch (e: Exception) {
        null
    } ?: Version(0, 0, 0)

    return computeVersion(branch, tagVersion, isClean)
}

/**
 * Blocking version of [gitVersion].
 */
fun gitVersionBlocking(directory: File): Version = gitVersion(directory)

/**
 * Parse a git tag string into a Version.
 * Handles tags like "1.0.0", "v1.0.0", "1.0.0-rc1"
 */
fun parseTagVersion(tag: String): Version {
    val cleaned = tag.removePrefix("v").removePrefix("V")
    val parts = cleaned.substringBefore('-').substringBefore('+').split(".")
    val major = parts.getOrNull(0)?.toIntOrNull() ?: 0
    val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
    val patch = parts.getOrNull(2)?.toIntOrNull() ?: 0
    val variant = cleaned.substringAfter('-', "").takeUnless { it.isBlank() }
    return Version(major, minor, patch, variant)
}

/**
 * Core version computation logic.
 *
 * @param branch Current git branch name
 * @param tagVersion Version from closest tag
 * @param isClean Whether working tree is clean
 */
internal fun computeVersion(
    branch: String,
    tagVersion: Version,
    isClean: Boolean
): Version {
    val branchLower = branch.lowercase()

    // Check if this is a "standard" branch name
    val isStandardBranch = branchLower in setOf("dev", "development", "main", "master", "head") ||
            branch.removePrefix("version").removePrefix("-").all { it.isDigit() || it == '.' }

    // Try to parse version intent from branch name (e.g., "version-2.0" or "v2.0")
    val intendedVersion = parseIntendedVersionFromBranch(branch)

    // Is this a pre-release branch for a future version?
    val isPreReleaseBranch = intendedVersion != null && intendedVersion > tagVersion

    // Compute the version components
    val major: Int
    val minor: Int
    val patch: Int

    if (isPreReleaseBranch) {
        major = intendedVersion!!.major
        minor = intendedVersion.minor
        patch = intendedVersion.patch
    } else {
        major = tagVersion.major
        minor = tagVersion.minor
        // Bump patch if:
        // - dirty working tree, OR
        // - tag has a variant (not exact release), OR
        // - on a non-standard branch (feature branches always get bumped)
        patch = if (isClean && tagVersion.variant == null && isStandardBranch) {
            tagVersion.patch
        } else {
            tagVersion.patch + 1
        }
    }

    // Build the variant string
    val variantParts = mutableListOf<String>()

    if (!isStandardBranch) {
        // Non-standard branch: include sanitized branch name
        variantParts.add(branch.filter { it.isLetterOrDigit() })
    } else if (isPreReleaseBranch) {
        // Pre-release branch for future version
        variantParts.add("prerelease")
    }

    // Carry forward tag variant if dirty
    if (!isClean && tagVersion.variant != null) {
        val tagVariantNum = tagVersion.variant.toIntOrNull()
        if (tagVariantNum != null) {
            variantParts.add((tagVariantNum + 1).toString())
        } else {
            variantParts.add(tagVersion.variant)
        }
    }

    // Add "local" suffix for dirty working tree
    if (!isClean) {
        variantParts.add("local")
    }

    val variant = variantParts.joinToString("-").takeUnless { it.isBlank() }

    return Version(major, minor, patch, variant)
}

/**
 * Try to parse an intended version from a branch name.
 * Handles: "version-2", "version-2.0", "v2.0", "2.0"
 */
private fun parseIntendedVersionFromBranch(branch: String): Version? {
    val cleaned = branch
        .removePrefix("version")
        .removePrefix("v")
        .removePrefix("-")
        .replace('-', '.')

    if (!cleaned.all { it.isDigit() || it == '.' }) {
        return null
    }

    if (cleaned.isBlank()) {
        return null
    }

    val parts = cleaned.split(".")
    val major = parts.getOrNull(0)?.toIntOrNull() ?: return null
    val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
    val patch = parts.getOrNull(2)?.toIntOrNull() ?: 0

    return Version(major, minor, patch)
}

/**
 * Extension function to get version for a project directory.
 */
fun File.getGitVersion(): Version = gitVersion(this)
