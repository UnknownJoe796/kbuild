package com.ivieleague.kbuild.cli

/**
 * Parsed annotations from a build script source file.
 */
data class BuildScriptAnnotations(
    /** URLs declared via @file:Repository("url") */
    val repositories: List<String>,
    /** Maven coordinates declared via @file:DependsOn("group:artifact:version") */
    val dependsOn: List<String>
)

/**
 * Parse @file:DependsOn and @file:Repository annotations from a Kotlin source file's text.
 *
 * These annotations have SOURCE retention, so they do not appear in compiled classfiles.
 * The CLI reads them from source text before compiling the build script, then resolves
 * the declared libraries and injects them into both the compile classpath and the
 * classloader used to load the compiled script.
 *
 * Handles:
 *   @file:DependsOn("group:artifact:version")
 *   @file:DependsOn(maven = "group:artifact:version")
 *   @DependsOn("group:artifact:version")              (without @file: prefix)
 *   @file:Repository("https://example.com/repo")
 *   @Repository("https://example.com/repo")           (without @file: prefix)
 *   Multiple occurrences of each (annotations are @Repeatable)
 */
fun parseBuildScriptAnnotations(source: String): BuildScriptAnnotations {
    // Matches @file:DependsOn("val") and @DependsOn(maven = "val"), with optional whitespace
    val dependsOnRegex = Regex("""@(?:file:)?DependsOn\(\s*(?:maven\s*=\s*)?"([^"]+)"\s*\)""")
    // Matches @file:Repository("val") and @Repository("val"), with optional whitespace
    val repositoryRegex = Regex("""@(?:file:)?Repository\(\s*"([^"]+)"\s*\)""")

    return BuildScriptAnnotations(
        repositories = repositoryRegex.findAll(source).map { it.groupValues[1] }.toList(),
        dependsOn = dependsOnRegex.findAll(source).map { it.groupValues[1] }.toList()
    )
}
