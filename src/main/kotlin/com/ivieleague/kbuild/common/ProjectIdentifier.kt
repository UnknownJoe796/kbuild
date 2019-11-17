package com.ivieleague.kbuild.common

data class ProjectIdentifier(
    val group: String,
    val name: String,
    val version: Version
) {
    constructor(group: String, name: String, version: String) : this(group, name, Version(version))
    constructor(string: String) : this(
        string.substringBefore(':'),
        string.substringAfter(':').substringBeforeLast(':'),
        string.substringAfterLast(':')
    )

    override fun toString(): String {
        return "$group:$name:$version"
    }

    val nameDashVersion: String get() = "$name-$version"
}