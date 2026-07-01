package com.ivieleague.kbuild.standard

import com.ivieleague.kbuild.common.Project
import com.ivieleague.kbuild.common.TestResult
import com.ivieleague.kbuild.common.Version
import com.ivieleague.kbuild.jvm.JVM
import com.ivieleague.kbuild.jvm.Jar
import com.ivieleague.kbuild.jvm.jarBuildBlocking
import com.ivieleague.kbuild.kmp.*
import com.ivieleague.kbuild.kotlin.JsModuleKind
import com.ivieleague.kbuild.kotlin.Kotlin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import com.ivieleague.kbuild.common.Dependency
import org.jetbrains.kotlin.cli.common.arguments.K2JSCompilerArguments
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import java.io.File
import java.util.jar.Manifest

/**
 * A standardized Kotlin Multiplatform application build configuration with sensible defaults.
 *
 * Supports building executable applications for:
 * - JVM (with fat JAR support)
 * - JavaScript (browser or Node.js)
 * - Native (macOS, Linux, Windows executables)
 *
 * Extend this class to create your own KMP application:
 * ```
 * object MyApp : MultiplatformApp() {
 *     override val name = "my-app"
 *     override val projectRoot = File(".")
 *     override val group = "com.example"
 *
 *     override val targets = setOf(
 *         KmpTarget.Jvm,
 *         KmpTarget.Native.host()
 *     )
 *
 *     override val jvmMainClass = "com.example.MainKt"
 *
 *     override suspend fun commonDependencies() = super.commonDependencies() + setOf(
 *         Dependency("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
 *     )
 *
 *     // Example with kotlinx.serialization plugin
 *     override suspend fun compilerPlugins() = setOf(SerializationPlugin.pluginJar())
 * }
 *
 * // In build script:
 * MyApp.runJvm()
 * MyApp.fatJar()
 * MyApp.buildNativeExecutable()
 * ```
 */
abstract class MultiplatformApp : Project() {

    // Targets - default to JVM only
    open val targets: Set<KmpTarget> get() = setOf(KmpTarget.Jvm)

    // Entry points
    open val jvmMainClass: String? = null
    open val nativeEntryPoint: String? = null
    open val jvmArgs: List<String> = emptyList()

    /**
     * Common dependencies shared by all targets.
     * This is a suspend function to allow async resolution.
     */
    open suspend fun commonDependencies(): Set<Dependency> =
        setOf(Dependency("org.jetbrains.kotlin:kotlin-stdlib:${Kotlin.versionString}"))

    /**
     * Platform-specific dependencies.
     * This is a suspend function to allow async resolution.
     */
    open suspend fun targetDependencies(): Map<KmpTarget, Set<Dependency>> = emptyMap()

    /**
     * Additional native compiler arguments.
     */
    open val nativeCompilerArguments: List<String> get() = emptyList()

    /**
     * Compiler plugin JARs to apply to all targets.
     * This is a suspend function to allow async resolution from Maven.
     *
     * Example usage with kotlinx.serialization:
     * ```
     * override suspend fun compilerPlugins() = setOf(SerializationPlugin.pluginJar())
     * ```
     */
    open suspend fun compilerPlugins(): Set<File> = emptySet()

    // Directory layout
    open val libsDir: File get() = buildDir.resolve("libs")
    open val binDir: File get() = buildDir.resolve("bin")
    open val jsDir: File get() = buildDir.resolve("js")

    /**
     * Build the KmpProjectConfig, resolving all async dependencies and plugins in parallel.
     */
    protected suspend fun buildKmpConfig(): KmpProjectConfig = coroutineScope {
        // Resolve dependencies, target dependencies, and plugins in parallel
        val commonDepsDeferred = async { commonDependencies() }
        val targetDepsDeferred = async { targetDependencies() }
        val pluginsDeferred = async { compilerPlugins() }

        val commonDeps = commonDepsDeferred.await()
        val targetDeps = targetDepsDeferred.await()
        val plugins = pluginsDeferred.await()

        // Capture before entering extension lambdas: languageVersion, apiVersion, and
        // allWarningsAsErrors shadow identically-named fields on K2* compiler argument types.
        val projectOptIns = optIns
        val projectFreeArgs = freeCompilerArgs
        val projectLangVer = languageVersion
        val projectApiVer = apiVersion
        val projectWerror = allWarningsAsErrors

        val jvmArgs: (K2JVMCompilerArguments.() -> Unit) = {
            if (enableContextParameters) contextParameters = true
            if (projectOptIns.isNotEmpty()) optIn = (optIn ?: emptyArray()) + projectOptIns.toTypedArray()
            if (projectFreeArgs.isNotEmpty()) freeArgs = freeArgs + projectFreeArgs
            projectLangVer?.let { languageVersion = it }
            projectApiVer?.let { apiVersion = it }
            if (projectWerror) allWarningsAsErrors = true
            if (plugins.isNotEmpty()) {
                val existing = pluginClasspaths ?: emptyArray()
                pluginClasspaths = existing + plugins.map { it.absolutePath }.toTypedArray()
            }
        }

        val jsArgs: (K2JSCompilerArguments.() -> Unit) = {
            if (enableContextParameters) contextParameters = true
            if (projectOptIns.isNotEmpty()) optIn = (optIn ?: emptyArray()) + projectOptIns.toTypedArray()
            if (projectFreeArgs.isNotEmpty()) freeArgs = freeArgs + projectFreeArgs
            projectLangVer?.let { languageVersion = it }
            projectApiVer?.let { apiVersion = it }
            if (projectWerror) allWarningsAsErrors = true
            if (plugins.isNotEmpty()) {
                val existing = pluginClasspaths ?: emptyArray()
                pluginClasspaths = existing + plugins.map { it.absolutePath }.toTypedArray()
            }
        }

        val nativeArgs = buildList {
            addAll(nativeCompilerArguments)
            if (enableContextParameters) add("-Xcontext-parameters")
            projectOptIns.forEach { add("-opt-in=$it") }
            addAll(projectFreeArgs)
            projectLangVer?.let { add("-language-version=$it") }
            projectApiVer?.let { add("-api-version=$it") }
            if (projectWerror) add("-Werror")
            plugins.forEach { add("-Xplugin=${it.absolutePath}") }
        }

        KmpProjectConfig(
            name = name,
            projectRoot = projectRoot,
            targets = targets,
            commonDependencies = commonDeps,
            targetDependencies = targetDeps,
            jvmCompilerArguments = jvmArgs,
            jsCompilerArguments = jsArgs,
            nativeCompilerArguments = nativeArgs
        )
    }

    // ============== JVM Compilation & Running ==============

    /**
     * Compile JVM target.
     *
     * Uses the reactive [kmpCompileJvm] so that file-system changes are tracked when this method
     * is called from inside a [reactiveSuspending] watch loop (--watch mode).  One-shot calls
     * are unaffected: accessing a Reactive outside an active tracking scope simply returns its
     * current value and compiles exactly once.
     */
    suspend fun compileJvm(): File {
        require(KmpTarget.Jvm in targets) { "JVM target not enabled" }
        return kmpCompileJvm(buildKmpConfig())
    }

    /**
     * Build a thin JAR (classes only, no dependencies).
     */
    suspend fun thinJar(): File {
        require(KmpTarget.Jvm in targets) { "JVM target not enabled" }
        require(jvmMainClass != null) { "JVM main class not specified" }

        val config = buildKmpConfig()
        val classesDir = kmpCompileJvmBlocking(config)
        val jarFile = libsDir.resolve("$name-${version}.jar")

        withContext(Dispatchers.IO) {
            jarBuildBlocking(
                manifest = Manifest().apply {
                    mainAttributes.putValue("Manifest-Version", "1.0")
                    mainAttributes.putValue("Main-Class", jvmMainClass)
                    mainAttributes.putValue("Implementation-Title", name)
                    mainAttributes.putValue("Implementation-Version", version.toString())
                    mainAttributes.putValue("Created-By", "KBuild")
                },
                folders = setOf(classesDir),
                output = jarFile
            )
        }

        return jarFile
    }

    /**
     * Build a fat JAR with all dependencies included.
     */
    suspend fun fatJar(): File {
        require(KmpTarget.Jvm in targets) { "JVM target not enabled" }
        require(jvmMainClass != null) { "JVM main class not specified" }

        val config = buildKmpConfig()
        val classesDir = kmpCompileJvmBlocking(config)
        val jarFile = libsDir.resolve("$name-${version}-all.jar")

        // Resolve runtime dependencies
        val runtimeJars = config.dependencies.resolveJvmClasspath()

        withContext(Dispatchers.IO) {
            jarFile.parentFile.mkdirs()
            Jar.fatJar(
                output = jarFile,
                manifest = Manifest().apply {
                    mainAttributes.putValue("Manifest-Version", "1.0")
                    mainAttributes.putValue("Main-Class", jvmMainClass)
                    mainAttributes.putValue("Implementation-Title", name)
                    mainAttributes.putValue("Implementation-Version", version.toString())
                    mainAttributes.putValue("Created-By", "KBuild")
                },
                classes = listOf(classesDir),
                jars = runtimeJars.toList()
            )
        }

        return jarFile
    }

    /**
     * Run the JVM application.
     */
    suspend fun runJvm(vararg args: String): Any? {
        require(KmpTarget.Jvm in targets) { "JVM target not enabled" }
        require(jvmMainClass != null) { "JVM main class not specified" }

        val config = buildKmpConfig()
        val classesDir = kmpCompileJvmBlocking(config)
        val runtimeClasspath = config.dependencies.resolveJvmClasspath() + classesDir

        return withContext(Dispatchers.IO) {
            JVM.runMain(runtimeClasspath.toList(), jvmMainClass!!, args.toList().toTypedArray())
        }
    }

    /**
     * Run the JVM application in a separate process.
     */
    suspend fun runJvmProcess(vararg args: String): Process {
        require(KmpTarget.Jvm in targets) { "JVM target not enabled" }
        require(jvmMainClass != null) { "JVM main class not specified" }

        val config = buildKmpConfig()
        val classesDir = kmpCompileJvmBlocking(config)
        val runtimeClasspath = config.dependencies.resolveJvmClasspath() + classesDir
        val classpathString = runtimeClasspath.joinToString(File.pathSeparator) { it.absolutePath }

        val command = mutableListOf("java")
        command.addAll(jvmArgs)
        command.add("-cp")
        command.add(classpathString)
        command.add(jvmMainClass!!)
        command.addAll(args)

        return ProcessBuilder(command)
            .directory(projectRoot)
            .inheritIO()
            .start()
    }

    // ============== JS Compilation ==============

    /**
     * Compile JS target to executable JavaScript.
     *
     * Uses the reactive [kmpCompileJs] so that source changes are tracked when called from a
     * watch loop.  One-shot callers are unaffected.
     */
    suspend fun compileJs(moduleKind: JsModuleKind = JsModuleKind.ES): File {
        require(targets.any { it is KmpTarget.Js || it == KmpTarget.Js }) { "JS target not enabled" }
        return kmpCompileJs(buildKmpConfig(), moduleKind = moduleKind)
    }

    /**
     * Build JS distribution (with all dependencies bundled).
     * Returns the directory containing the JS files.
     */
    suspend fun buildJsDistribution(moduleKind: JsModuleKind = JsModuleKind.ES): File {
        val jsOutput = compileJs(moduleKind)

        // Copy to distribution directory
        val distDir = buildDir.resolve("distributions/js")
        distDir.mkdirs()

        jsOutput.copyRecursively(distDir, overwrite = true)

        // Create a simple HTML launcher
        val htmlFile = distDir.resolve("index.html")
        htmlFile.writeText("""
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <title>$name</title>
            </head>
            <body>
                <script type="module" src="$name.mjs"></script>
            </body>
            </html>
        """.trimIndent())

        return distDir
    }

    // ============== Native Compilation ==============

    /**
     * Build a native executable for the specified target.
     */
    suspend fun buildNativeExecutable(
        target: KmpTarget.Native = KmpTarget.Native.host()
    ): File {
        require(target in targets) { "Target $target not enabled" }
        return kmpCompileNativeExecutableBlocking(buildKmpConfig(), target, nativeEntryPoint)
    }

    /**
     * Build native executables for all enabled native targets.
     */
    suspend fun buildAllNativeExecutables(): Map<KmpTarget.Native, File> {
        val results = mutableMapOf<KmpTarget.Native, File>()
        for (target in targets.filterIsInstance<KmpTarget.Native>()) {
            results[target] = buildNativeExecutable(target)
        }
        return results
    }

    /**
     * Run the native executable for the host platform.
     */
    suspend fun runNative(vararg args: String): Process {
        val target = KmpTarget.Native.host()
        require(target in targets) { "Host native target not enabled" }

        val executable = buildNativeExecutable(target)

        return ProcessBuilder(listOf(executable.absolutePath) + args)
            .directory(projectRoot)
            .inheritIO()
            .start()
    }

    // ============== Build All ==============

    /**
     * Build all enabled targets.
     */
    suspend fun buildAll(): Map<KmpTarget, File> {
        val results = mutableMapOf<KmpTarget, File>()

        if (KmpTarget.Jvm in targets && jvmMainClass != null) {
            results[KmpTarget.Jvm] = fatJar()
        } else if (KmpTarget.Jvm in targets) {
            results[KmpTarget.Jvm] = compileJvm()
        }

        if (targets.any { it is KmpTarget.Js || it == KmpTarget.Js }) {
            results[KmpTarget.Js] = buildJsDistribution()
        }

        for (target in targets.filterIsInstance<KmpTarget.Native>()) {
            results[target] = buildNativeExecutable(target)
        }

        return results
    }

    // ============== Testing ==============

    /**
     * Run native tests for the specified target.
     */
    suspend fun testNative(
        target: KmpTarget.Native = KmpTarget.Native.host()
    ): Set<TestResult> {
        require(target in targets) { "Target $target not enabled" }
        return kmpRunNativeTestsBlocking(buildKmpConfig(), target)
    }

    // ============== Scaffold ==============

    /**
     * Create the standard directory structure for KMP app projects.
     */
    fun scaffold() {
        // Common source sets
        projectRoot.resolve("src/commonMain/kotlin").mkdirs()
        projectRoot.resolve("src/commonTest/kotlin").mkdirs()

        // Platform-specific source sets
        if (KmpTarget.Jvm in targets) {
            projectRoot.resolve("src/jvmMain/kotlin").mkdirs()
            projectRoot.resolve("src/jvmTest/kotlin").mkdirs()
        }

        if (targets.any { it is KmpTarget.Js || it == KmpTarget.Js }) {
            projectRoot.resolve("src/jsMain/kotlin").mkdirs()
            projectRoot.resolve("src/jsTest/kotlin").mkdirs()
        }

        val hasNative = targets.any { it is KmpTarget.Native }
        if (hasNative) {
            projectRoot.resolve("src/nativeMain/kotlin").mkdirs()
            projectRoot.resolve("src/nativeTest/kotlin").mkdirs()
        }

        // Create sample common main file
        val packagePath = group.replace('.', '/')
        val commonMain = projectRoot.resolve("src/commonMain/kotlin/$packagePath/App.kt")
        if (!commonMain.exists()) {
            commonMain.parentFile.mkdirs()
            commonMain.writeText("""
                package $group

                /**
                 * Common application logic for $name.
                 */
                expect fun platformName(): String

                class App {
                    fun run() {
                        println("Hello from $name on ${'$'}{platformName()}!")
                    }
                }
            """.trimIndent())
        }

        // Create JVM main
        if (KmpTarget.Jvm in targets && jvmMainClass != null) {
            val mainPackage = jvmMainClass!!.substringBeforeLast('.')
            val mainClassName = jvmMainClass!!.substringAfterLast('.').removeSuffix("Kt")
            val mainPath = mainPackage.replace('.', '/')
            val jvmMain = projectRoot.resolve("src/jvmMain/kotlin/$mainPath/$mainClassName.kt")
            if (!jvmMain.exists()) {
                jvmMain.parentFile.mkdirs()
                jvmMain.writeText("""
                    package $mainPackage

                    import $group.App

                    actual fun platformName(): String = "JVM"

                    fun main(args: Array<String>) {
                        App().run()
                    }
                """.trimIndent())
            }
        }

        // Create JS main
        if (targets.any { it is KmpTarget.Js || it == KmpTarget.Js }) {
            val jsMain = projectRoot.resolve("src/jsMain/kotlin/$packagePath/Main.kt")
            if (!jsMain.exists()) {
                jsMain.parentFile.mkdirs()
                jsMain.writeText("""
                    package $group

                    actual fun platformName(): String = "JavaScript"

                    fun main() {
                        App().run()
                    }
                """.trimIndent())
            }
        }

        // Create Native main
        if (hasNative) {
            val nativeMain = projectRoot.resolve("src/nativeMain/kotlin/$packagePath/Main.kt")
            if (!nativeMain.exists()) {
                nativeMain.parentFile.mkdirs()
                nativeMain.writeText("""
                    package $group

                    actual fun platformName(): String = "Native"

                    fun main(args: Array<String>) {
                        App().run()
                    }
                """.trimIndent())
            }
        }
    }

    /**
     * Print build configuration summary.
     */
    suspend fun printSummary() {
        val deps = commonDependencies()
        println("Multiplatform App: $name")
        println("Group: $group")
        println("Version: $version")
        println("Targets: ${targets.joinToString { it.name }}")
        if (jvmMainClass != null) {
            println("JVM Main Class: $jvmMainClass")
        }
        if (nativeEntryPoint != null) {
            println("Native Entry Point: $nativeEntryPoint")
        }
        println()
        if (deps.isNotEmpty()) {
            println("Common Dependencies:")
            deps.forEach { println("  - ${it.groupId}:${it.artifactId}:${it.version}") }
        }
    }

    companion object {
        // Target presets from MultiplatformLibrary
        val iosTargets = MultiplatformLibrary.iosTargets
        val macosTargets = MultiplatformLibrary.macosTargets
        val allAppleTargets = MultiplatformLibrary.allAppleTargets
        val desktopTargets = MultiplatformLibrary.desktopTargets
        val allNativeTargets = MultiplatformLibrary.allNativeTargets
    }
}
