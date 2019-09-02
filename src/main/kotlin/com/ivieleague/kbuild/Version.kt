package com.ivieleague.kbuild


data class Version(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val variant: String? = null
) : Comparable<Version> {
    override fun compareTo(other: Version): Int {
        return when {
            this.major != other.major -> this.major.compareTo(other.major)
            this.minor != other.minor -> this.minor.compareTo(other.minor)
            this.patch != other.patch -> this.patch.compareTo(other.patch)
            else -> 0
        }
    }

    fun satisfies(required: Version): Boolean {
        return this.major == required.major && this.minor >= required.minor
    }

    fun patched() = copy(patch = patch + 1)
    fun featureAdded() = copy(minor = minor + 1)
    fun compatibilityBroken() = copy(major = major + 1)

    override fun toString(): String {
        return if (variant == null)
            "$major.$minor.$patch"
        else
            "$major.$minor.$patch-$variant"
    }

    constructor(string: String) : this(
        major = string.substringBefore('.').toInt(),
        minor = string.substringAfter('.').substringBefore('.').toInt(),
        patch = string.substringAfterLast('.').substringBefore('-').toInt(),
        variant = string.substringAfterLast('-', "").takeUnless { it.isEmpty() }
    )
}