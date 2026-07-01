package com.ivieleague.kbuild.standard

import com.ivieleague.kbuild.common.Library
import com.ivieleague.kbuild.common.Project
import com.ivieleague.kbuild.common.TestResult
import com.ivieleague.kbuild.common.Version
import com.ivieleague.kbuild.jvm.JVM
import com.ivieleague.kbuild.jvm.Jar
import com.ivieleague.kbuild.jvm.jarBuild
import com.ivieleague.kbuild.junit.junitRun
import com.ivieleague.kbuild.kotlin.kotlinJvmCompile
import com.ivieleague.kbuild.common.Dependency
import com.ivieleague.kbuild.common.DependencyScope
import com.ivieleague.kbuild.maven.MavenAether
import com.ivieleague.kbuild.maven.aether
import com.ivieleague.kbuild.watch.DirectoryWatch
import com.lightningkite.reactive.core.Constant
import com.lightningkite.reactive.core.Reactive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File
import java.util.jar.Manifest

/**
 * A standardized JVM application build configuration with sensible defaults.
 *
 * Extend this class to create your own JVM application:
 * ```
 * object MyApp : JvmApp() {
 *     override val name = "my-app"
 *     override val projectRoot = File(".")
 *     override val mainClass = "com.example.MainKt"
 *
 *     override suspend fun dependencies() = super.dependencies() + setOf(
 *         dependency("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
 *     )
 *
 *     // Example with kotlinx.serialization plugin
 *     override suspend fun compilerPlugins() = setOf(SerializationPlugin.pluginJar())
 * }
 *
 * // In build script:
 * MyApp.run()
 * MyApp.fatJar()
 * MyApp.test()
 * ```
 */
abstract class JvmApp : Project() {
    abstract val mainClass: String

    open val jvmTarget: String = "17"
    open val jvmArgs: List<String> = emptyList()

    /**
     * Main dependencies for this application.
     * This is a suspend function to allow async resolution.
     */
    open suspend fun dependencies(): Set<Dependency> = emptySet()

    /**
     * Test-only dependencies.
     */
    open suspend fun testDependencies(): Set<Dependency> = emptySet()

    /**
     * Compiler plugin JARs to apply during compilation.
     * This is a suspend function to allow async resolution from Maven.
     *
     * Example usage with kotlinx.serialization:
     * ```
     * override suspend fun compilerPlugins() = setOf(SerializationPlugin.pluginJar())
     * ```
     */
    open suspend fun compilerPlugins(): Set<File> = emptySet()

    // Directory layout (convention over configuration)
    open val srcDir: File get() = projectRoot.resolve("src/main/kotlin")
    open val testSrcDir: File get() = projectRoot.resolve("src/test/kotlin")
    open val resourcesDir: File get() = projectRoot.resolve("src/main/resources")
    open val testResourcesDir: File get() = projectRoot.resolve("src/test/resources")
    open val classesDir: File get() = buildDir.resolve("classes/kotlin/main")
    open val testClassesDir: File get() = buildDir.resolve("classes/kotlin/test")
    open val cacheDir: File get() = buildDir.resolve("kotlin/cache")
    open val testCacheDir: File get() = buildDir.resolve("kotlin/test-cache")
    open val libsDir: File get() = buildDir.resolve("libs")

    // ============== Dependency Resolution ==============

    /**
     * Get all default dependencies (Kotlin stdlib + user dependencies).
     */
    protected suspend fun defaultDependencies(): Set<Dependency> =
        setOf(JvmLibrary.kotlinStdlib()) + dependencies()

    /**
     * Get all test dependencies (JUnit 5 + Kotlin test + user test dependencies).
     */
    protected suspend fun defaultTestDependencies(): Set<Dependency> = setOf(
        JvmLibrary.kotlinTest(),
        JvmLibrary.junitJupiter(),
        JvmLibrary.junitPlatformLauncher()
    ) + testDependencies()

    /**
     * Resolve compile dependencies to Library objects.
     */
    suspend fun resolveCompileDependencies(): Set<Library> {
        val deps = defaultDependencies()
        return MavenAether.libraries(
            dependencies = deps.filter { it.scope.includeInCompilation() }.map { it.aether() }
        )
    }

    /**
     * Resolve compile dependencies to JAR files.
     */
    suspend fun resolveCompileClasspath(): Set<File> {
        return resolveCompileDependencies().mapTo(mutableSetOf()) { it.default }
    }

    /**
     * Resolve runtime dependencies to JAR files.
     */
    suspend fun resolveRuntimeClasspath(): Set<File> {
        val deps = defaultDependencies()
        return MavenAether.libraries(
            dependencies = deps.filter { it.scope.includeInDistribution() }.map { it.aether() }
        ).mapTo(mutableSetOf()) { it.default }
    }

    /**
     * Resolve test dependencies to JAR files.
     */
    suspend fun resolveTestClasspath(): Set<File> {
        val compileDeps = defaultDependencies().filter { it.scope.includeInCompilation() }
        val testDeps = defaultTestDependencies()
        return MavenAether.libraries(
            dependencies = (compileDeps + testDeps).map { it.aether() }
        ).mapTo(mutableSetOf()) { it.default }
    }

    // ============== Source Watching ==============

    /**
     * Create a reactive watch for source files.
     */
    fun watchSources(): Reactive<Set<File>> {
        return if (srcDir.exists()) {
            DirectoryWatch(srcDir, "**/*.kt")
        } else {
            Constant(emptySet())
        }
    }

    /**
     * Create a reactive watch for test source files.
     */
    fun watchTestSources(): Reactive<Set<File>> {
        return if (testSrcDir.exists()) {
            DirectoryWatch(testSrcDir, "**/*.kt")
        } else {
            Constant(emptySet())
        }
    }

    // ============== Compilation ==============

    /**
     * Compile main sources.
     *
     * Uses the reactive [kotlinJvmCompile] with [watchSources] so that file-system changes are
     * tracked when this method is called from inside a [reactiveSuspending] watch loop (--watch
     * mode).  One-shot calls are unaffected: accessing a Reactive outside an active tracking
     * scope simply returns its current value and compiles exactly once.
     */
    suspend fun compile(): File = coroutineScope {
        // Resolve classpath and plugins in parallel
        val classpathDeferred = async { resolveCompileClasspath() }
        val pluginsDeferred = async { compilerPlugins() }

        val classpath = classpathDeferred.await()
        val plugins = pluginsDeferred.await()

        val app = this@JvmApp
        // kotlinJvmCompile calls sourceRoots() inside a reactive context (if one is active),
        // registering the DirectoryWatch as a dependency so --watch re-runs on source changes.
        kotlinJvmCompile(
            name = name,
            sourceRoots = watchSources(),
            classpathJars = classpath,
            arguments = {
                jvmTarget = app.jvmTarget
                if (app.enableContextParameters) contextParameters = true
                if (app.optIns.isNotEmpty()) optIn = (optIn ?: emptyArray()) + app.optIns.toTypedArray()
                if (app.freeCompilerArgs.isNotEmpty()) freeArgs = freeArgs + app.freeCompilerArgs
                app.languageVersion?.let { languageVersion = it }
                app.apiVersion?.let { apiVersion = it }
                if (app.allWarningsAsErrors) allWarningsAsErrors = true
                if (plugins.isNotEmpty()) {
                    val existing = pluginClasspaths ?: emptyArray()
                    pluginClasspaths = existing + plugins.map { it.absolutePath }.toTypedArray()
                }
            },
            cache = cacheDir,
            outputFolder = classesDir
        )
    }

    /**
     * Compile test sources.
     */
    suspend fun compileTest(): File = coroutineScope {
        // Compile main sources first
        val mainClasses = compile()

        // Resolve test classpath and plugins in parallel
        val testClasspathDeferred = async { resolveTestClasspath() }
        val pluginsDeferred = async { compilerPlugins() }

        val testClasspath = testClasspathDeferred.await() + mainClasses
        val plugins = pluginsDeferred.await()

        val app = this@JvmApp
        withContext(Dispatchers.IO) {
            kotlinJvmCompile(
                name = "$name-test",
                sourceRoots = Constant(setOf(testSrcDir).filter { it.exists() }.toSet()),
                classpathJars = testClasspath,
                arguments = {
                    jvmTarget = app.jvmTarget
                    if (enableContextParameters) contextParameters = true
                    if (app.optIns.isNotEmpty()) optIn = (optIn ?: emptyArray()) + app.optIns.toTypedArray()
                    if (app.freeCompilerArgs.isNotEmpty()) freeArgs = freeArgs + app.freeCompilerArgs
                    app.languageVersion?.let { languageVersion = it }
                    app.apiVersion?.let { apiVersion = it }
                    if (app.allWarningsAsErrors) allWarningsAsErrors = true
                    if (plugins.isNotEmpty()) {
                        val existing = pluginClasspaths ?: emptyArray()
                        pluginClasspaths = existing + plugins.map { it.absolutePath }.toTypedArray()
                    }
                },
                cache = testCacheDir,
                outputFolder = testClassesDir,
                enableContextParameters = enableContextParameters
            )
        }
    }

    // ============== JAR Building ==============

    /**
     * Build a thin JAR (classes only, no dependencies).
     */
    suspend fun thinJar(): File {
        val classes = compile()
        val jarFile = libsDir.resolve("$name-${version}.jar")
        withContext(Dispatchers.IO) {
            jarBuild(createManifest(), setOf(classes), jarFile)
        }
        return jarFile
    }

    /**
     * Build a fat JAR with all dependencies included.
     */
    suspend fun fatJar(): File {
        val classes = compile()
        val jarFile = libsDir.resolve("$name-${version}-all.jar")
        val runtimeJars = resolveRuntimeClasspath()

        withContext(Dispatchers.IO) {
            jarFile.parentFile.mkdirs()
            Jar.fatJar(
                output = jarFile,
                manifest = createManifest(),
                classes = listOf(classes),
                jars = runtimeJars.toList()
            )
        }

        return jarFile
    }

    /**
     * Build a sources JAR.
     */
    suspend fun sourcesJar(): File {
        val jarFile = libsDir.resolve("$name-${version}-sources.jar")
        withContext(Dispatchers.IO) {
            jarBuild(Manifest(), setOf(srcDir).filter { it.exists() }.toSet(), jarFile)
        }
        return jarFile
    }

    protected open fun createManifest(): Manifest = Manifest().apply {
        mainAttributes.putValue("Manifest-Version", "1.0")
        mainAttributes.putValue("Main-Class", mainClass)
        mainAttributes.putValue("Implementation-Title", name)
        mainAttributes.putValue("Implementation-Version", version.toString())
        mainAttributes.putValue("Created-By", "KBuild")
    }

    // ============== Running ==============

    /**
     * Run the application.
     *
     * @param args Command-line arguments to pass to the application
     */
    suspend fun run(vararg args: String): Any? {
        compile()
        val runtimeClasspath = resolveRuntimeClasspath() + classesDir
        return withContext(Dispatchers.IO) {
            JVM.runMain(runtimeClasspath.toList(), mainClass, args.toList().toTypedArray())
        }
    }

    /**
     * Run the application in a separate process (for long-running apps).
     *
     * @param args Command-line arguments to pass to the application
     * @return The Process object
     */
    suspend fun runProcess(vararg args: String): Process {
        compile()
        val runtimeClasspath = resolveRuntimeClasspath() + classesDir
        val classpathString = runtimeClasspath.joinToString(File.pathSeparator) { it.absolutePath }

        val command = mutableListOf("java")
        command.addAll(jvmArgs)
        command.add("-cp")
        command.add(classpathString)
        command.add(mainClass)
        command.addAll(args)

        return ProcessBuilder(command)
            .directory(projectRoot)
            .inheritIO()
            .start()
    }

    /**
     * Run the fat JAR in a separate process.
     *
     * @param args Command-line arguments to pass to the application
     * @return The Process object
     */
    suspend fun runFatJarProcess(vararg args: String): Process {
        val jarFile = fatJar()

        val command = mutableListOf("java")
        command.addAll(jvmArgs)
        command.add("-jar")
        command.add(jarFile.absolutePath)
        command.addAll(args)

        return ProcessBuilder(command)
            .directory(projectRoot)
            .inheritIO()
            .start()
    }

    // ============== Testing ==============

    /**
     * Run tests.
     */
    suspend fun test(): Set<TestResult> {
        val testClasses = compileTest()
        val testClasspath = resolveTestClasspath() + classesDir
        return withContext(Dispatchers.IO) {
            junitRun(Constant(testClasses), testClasspath)
        }
    }

    // ============== Scaffold ==============

    /**
     * Create the standard directory structure.
     */
    fun scaffold() {
        srcDir.mkdirs()
        testSrcDir.mkdirs()
        resourcesDir.mkdirs()
        testResourcesDir.mkdirs()

        // Create main class if empty
        val packagePath = mainClass.substringBeforeLast('.').replace('.', '/')
        val className = mainClass.substringAfterLast('.')
        val mainFile = srcDir.resolve("$packagePath/${className.removeSuffix("Kt")}.kt")
        if (!mainFile.exists()) {
            mainFile.parentFile.mkdirs()
            mainFile.writeText("""
                package ${mainClass.substringBeforeLast('.')}

                fun main(args: Array<String>) {
                    println("Hello from $name!")
                }
            """.trimIndent())
        }
    }

    companion object {
        /**
         * Helper to create a dependency from a string.
         */
        fun dependency(path: String): Dependency = JvmLibrary.dependency(path)
    }
}
