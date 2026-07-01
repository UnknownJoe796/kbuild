package com.ivieleague.kbuild.standard

import com.ivieleague.kbuild.common.Project
import com.ivieleague.kbuild.common.Repository
import com.ivieleague.kbuild.common.TestResult
import com.ivieleague.kbuild.common.Version
import com.ivieleague.kbuild.junit.junitRunBlocking
import com.ivieleague.kbuild.kmp.*
import com.ivieleague.kbuild.kotlin.JsModuleKind
import com.ivieleague.kbuild.kotlin.Kotlin
import com.ivieleague.kbuild.kotlin.kotlinJvmCompileBlocking
import com.ivieleague.kbuild.maven.GpgConfig
import com.ivieleague.kbuild.maven.GpgSigner
import com.ivieleague.kbuild.maven.MavenAether
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import com.ivieleague.kbuild.common.Dependency
import com.ivieleague.kbuild.maven.PomDeveloper
import com.ivieleague.kbuild.maven.PomLicense
import com.ivieleague.kbuild.maven.PomMetadata
import org.jetbrains.kotlin.cli.common.arguments.K2JSCompilerArguments
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import java.io.File

/**
 * A standardized Kotlin Multiplatform library build configuration with sensible defaults.
 *
 * Extend this class to create your own KMP library:
 * ```
 * object MyLibrary : MultiplatformLibrary() {
 *     override val name = "my-lib"
 *     override val projectRoot = File(".")
 *     override val group = "com.example"
 *     override val version = Version("1.0.0")
 *
 *     override val targets = setOf(
 *         KmpTarget.Jvm,
 *         KmpTarget.Js,
 *         KmpTarget.Native.IosArm64,
 *         KmpTarget.Native.IosSimulatorArm64,
 *         KmpTarget.Native.MacosArm64
 *     )
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
 * MyLibrary.buildAll()
 * MyLibrary.publish()
 * ```
 */
abstract class MultiplatformLibrary : Project() {

    // Targets - default to JVM only
    open val targets: Set<KmpTarget> get() = setOf(KmpTarget.Jvm)

    /**
     * Common dependencies shared by all targets.
     * This is a suspend function to allow async resolution (e.g., fetching from remote sources).
     *
     * Example:
     * ```
     * override suspend fun commonDependencies() = super.commonDependencies() + setOf(
     *     Dependency("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
     * )
     * ```
     */
    open suspend fun commonDependencies(): Set<Dependency> =
        setOf(Dependency("org.jetbrains.kotlin:kotlin-stdlib:${Kotlin.versionString}"))

    /**
     * Platform-specific dependencies.
     * This is a suspend function to allow async resolution.
     */
    open suspend fun targetDependencies(): Map<KmpTarget, Set<Dependency>> = emptyMap()

    /**
     * Additional native compiler arguments (e.g., "-Xcontext-parameters").
     */
    open val nativeCompilerArguments: List<String> get() = emptyList()

    /**
     * Compiler plugin JARs to apply to all targets.
     * This is a suspend function to allow async resolution from Maven.
     *
     * These JARs are added to the plugin classpath for JVM and JS compilation,
     * and as -Xplugin arguments for Native compilation.
     *
     * Example usage with kotlinx.serialization:
     * ```
     * override suspend fun compilerPlugins() = setOf(SerializationPlugin.pluginJar())
     * ```
     *
     * Or resolve from Maven:
     * ```
     * override suspend fun compilerPlugins(): Set<File> {
     *     return MavenAether.libraries("org.jetbrains.kotlin:kotlin-serialization-compiler-plugin-embeddable:2.2.0")
     *         .mapNotNull { it.default }.toSet()
     * }
     * ```
     */
    open suspend fun compilerPlugins(): Set<File> = emptySet()

    // ============== POM metadata (publishing) ==============

    open val pomName: String get() = name
    open val pomDescription: String? = null
    open val pomUrl: String? = null
    open val pomLicenses: List<PomLicense> = emptyList()
    open val pomDevelopers: List<PomDeveloper> = emptyList()
    open val pomScmUrl: String? = null

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

    // ============== Compilation ==============

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
     * Compile JS target to KLIB.
     *
     * Uses the reactive [kmpCompileJsKlib] so that source changes are tracked when called from a
     * watch loop.  One-shot callers are unaffected.
     */
    suspend fun compileJsKlib(): File {
        require(targets.any { it is KmpTarget.Js || it == KmpTarget.Js }) { "JS target not enabled" }
        return kmpCompileJsKlib(buildKmpConfig())
    }

    /**
     * Compile JS target to executable JS.
     *
     * Uses the reactive [kmpCompileJs] so that source changes are tracked when called from a
     * watch loop.  One-shot callers are unaffected.
     */
    suspend fun compileJs(moduleKind: JsModuleKind = JsModuleKind.ES): File {
        require(targets.any { it is KmpTarget.Js || it == KmpTarget.Js }) { "JS target not enabled" }
        return kmpCompileJs(buildKmpConfig(), moduleKind = moduleKind)
    }

    /**
     * Compile a native target to KLIB.
     *
     * Uses [kmpCompileNativeKlib] (the non-blocking variant) for API consistency with the other
     * compile methods.  Native source tracking is not yet reactive; it will be added in a future
     * release.
     */
    suspend fun compileNative(target: KmpTarget.Native): File {
        require(target in targets) { "Target $target not enabled" }
        return kmpCompileNativeKlib(buildKmpConfig(), target)
    }

    /**
     * Compile all enabled targets.
     */
    suspend fun buildAll(): Map<KmpTarget, File> {
        return kmpBuildAllBlocking(buildKmpConfig())
    }

    // ============== iOS/Apple Framework ==============

    /**
     * Build an Apple framework for the specified target.
     */
    suspend fun buildFramework(
        target: KmpTarget.Native,
        static: Boolean = false
    ): File {
        require(target in targets) { "Target $target not enabled" }
        return kmpBuildFrameworkBlocking(buildKmpConfig(), target, static)
    }

    /**
     * Build XCFramework for all iOS targets.
     */
    suspend fun buildXCFramework(): File {
        val iosTargets = targets.filterIsInstance<KmpTarget.Native>().filter { it.isIosTarget() }
        require(iosTargets.isNotEmpty()) { "No iOS targets enabled" }

        // Build frameworks for all iOS targets
        val frameworks = mutableListOf<File>()
        for (target in iosTargets) {
            frameworks.add(buildFramework(target, static = true))
        }

        // Create XCFramework
        val xcframeworkDir = buildDir.resolve("xcframeworks/$name.xcframework")
        xcframeworkDir.deleteRecursively()

        val command = mutableListOf(
            "xcodebuild", "-create-xcframework"
        )
        for (framework in frameworks) {
            command.add("-framework")
            command.add(framework.absolutePath)
        }
        command.add("-output")
        command.add(xcframeworkDir.absolutePath)

        withContext(Dispatchers.IO) {
            ProcessBuilder(command)
                .inheritIO()
                .start()
                .waitFor()
        }

        return xcframeworkDir
    }

    // ============== Testing ==============

    /**
     * Extra test dependencies (e.g., "org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2").
     * Override this to add additional test dependencies.
     */
    open val extraTestDependencies: List<Dependency> get() = emptyList()

    /**
     * Compile JVM test sources.
     */
    suspend fun compileJvmTest(): File {
        require(KmpTarget.Jvm in targets) { "JVM target not enabled" }
        val config = buildKmpConfig()
        val mainClasses = kmpCompileJvmBlocking(config)
        val testClasspath = config.dependencies.resolveJvmTestClasspath(*extraTestDependencies.toTypedArray()) + mainClasses
        val plugins = compilerPlugins()

        return withContext(Dispatchers.IO) {
            val mpl = this@MultiplatformLibrary
            kotlinJvmCompileBlocking(
                name = "$name-test",
                sourceRoots = config.getTestSourcesForTarget(KmpTarget.Jvm),
                classpathJars = testClasspath,
                arguments = {
                    multiPlatform = true
                    if (enableContextParameters) contextParameters = true
                    expectActualClasses = true
                    commonSources = config.commonTestSourceFiles
                    if (mpl.optIns.isNotEmpty()) optIn = (optIn ?: emptyArray()) + mpl.optIns.toTypedArray()
                    if (mpl.freeCompilerArgs.isNotEmpty()) freeArgs = freeArgs + mpl.freeCompilerArgs
                    mpl.languageVersion?.let { languageVersion = it }
                    mpl.apiVersion?.let { apiVersion = it }
                    if (mpl.allWarningsAsErrors) allWarningsAsErrors = true
                    if (plugins.isNotEmpty()) {
                        val existing = pluginClasspaths ?: emptyArray()
                        pluginClasspaths = existing + plugins.map { it.absolutePath }.toTypedArray()
                    }
                },
                cache = buildDir.resolve("cache/jvm-test"),
                outputFolder = buildDir.resolve("classes/jvm/test"),
                enableContextParameters = enableContextParameters
            )
        }
    }

    /**
     * Run JVM tests using JUnit 5.
     */
    suspend fun testJvm(): Set<TestResult> {
        require(KmpTarget.Jvm in targets) { "JVM target not enabled" }
        val config = buildKmpConfig()
        val mainClasses = kmpCompileJvmBlocking(config)
        val testClasses = compileJvmTest()
        val testClasspath = config.dependencies.resolveJvmTestClasspath(*extraTestDependencies.toTypedArray())

        return withContext(Dispatchers.IO) {
            junitRunBlocking(testClasses, setOf(mainClasses) + testClasspath)
        }
    }

    /**
     * Run native tests for the specified target.
     */
    suspend fun testNative(
        target: KmpTarget.Native = KmpTarget.Native.host()
    ): Set<TestResult> {
        require(target in targets) { "Target $target not enabled" }
        return kmpRunNativeTestsBlocking(buildKmpConfig(), target)
    }

    // ============== Publishing ==============

    /**
     * Whether published artifacts are GPG-signed. A complete Maven publication (e.g. for Maven
     * Central, matching Gradle's default) is signed; set to false only for throwaway local builds.
     */
    open val signPublications: Boolean = true

    /**
     * The signer used when [signPublications] is true. Reads GPG_KEY_ID / GPG_PASSPHRASE /
     * GPG_EXECUTABLE from the environment, otherwise uses the default gpg key.
     */
    open fun publicationSigner(): GpgSigner? =
        if (signPublications) GpgConfig.fromEnvironment().toSigner() else null

    private fun buildPomMetadata() = PomMetadata(
        name = pomName,
        description = pomDescription,
        url = pomUrl,
        licenses = pomLicenses,
        developers = pomDevelopers,
        scmUrl = pomScmUrl
    )

    /**
     * Publish all artifacts to a Maven repository.
     */
    suspend fun publish(repository: Repository = Repository.mavenLocal) {
        val config = buildKmpConfig()
        val publisher = KmpPublisher(
            config = config,
            projectIdentifier = projectIdentifier,
            outputDir = buildDir.resolve("publish"),
            pomMetadata = buildPomMetadata(),
            signer = publicationSigner()
        )
        publisher.publishAll(repository.toAether())
        println("Published $group:$name:$version to ${repository.url}")
    }

    /**
     * Publish only JVM artifact.
     */
    suspend fun publishJvm(repository: Repository = Repository.mavenLocal) {
        require(KmpTarget.Jvm in targets) { "JVM target not enabled" }
        val publisher = KmpPublisher(
            config = buildKmpConfig(),
            projectIdentifier = projectIdentifier,
            outputDir = buildDir.resolve("publish"),
            pomMetadata = buildPomMetadata(),
            signer = publicationSigner()
        )
        publisher.publishJvm(repository = repository.toAether())
    }

    /**
     * Publish only JS artifact.
     */
    suspend fun publishJs(repository: Repository = Repository.mavenLocal) {
        require(targets.any { it is KmpTarget.Js || it == KmpTarget.Js }) { "JS target not enabled" }
        val publisher = KmpPublisher(
            config = buildKmpConfig(),
            projectIdentifier = projectIdentifier,
            outputDir = buildDir.resolve("publish"),
            pomMetadata = buildPomMetadata(),
            signer = publicationSigner()
        )
        publisher.publishJs(repository = repository.toAether())
    }

    // ============== Scaffold ==============

    /**
     * Create the standard directory structure for KMP projects.
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

        val hasIos = targets.filterIsInstance<KmpTarget.Native>().any { it.isIosTarget() }
        if (hasIos) {
            projectRoot.resolve("src/iosMain/kotlin").mkdirs()
            projectRoot.resolve("src/iosTest/kotlin").mkdirs()
        }

        val hasMacos = targets.filterIsInstance<KmpTarget.Native>().any { it.isMacosTarget() }
        if (hasMacos) {
            projectRoot.resolve("src/macosMain/kotlin").mkdirs()
            projectRoot.resolve("src/macosTest/kotlin").mkdirs()
        }

        // Create sample common source file
        val packagePath = group.replace('.', '/')
        val sampleFile = projectRoot.resolve("src/commonMain/kotlin/$packagePath/${name.capitalize()}.kt")
        if (!sampleFile.exists()) {
            sampleFile.parentFile.mkdirs()
            sampleFile.writeText("""
                package $group

                /**
                 * Common code for $name library.
                 */
                expect fun platformName(): String

                class ${name.split("-").joinToString("") { it.capitalize() }} {
                    fun greet(): String = "Hello from ${'$'}{platformName()}!"
                }
            """.trimIndent())
        }

        // Create JVM actual
        if (KmpTarget.Jvm in targets) {
            val jvmActual = projectRoot.resolve("src/jvmMain/kotlin/$packagePath/${name.capitalize()}Jvm.kt")
            if (!jvmActual.exists()) {
                jvmActual.parentFile.mkdirs()
                jvmActual.writeText("""
                    package $group

                    actual fun platformName(): String = "JVM"
                """.trimIndent())
            }
        }

        // Create JS actual
        if (targets.any { it is KmpTarget.Js || it == KmpTarget.Js }) {
            val jsActual = projectRoot.resolve("src/jsMain/kotlin/$packagePath/${name.capitalize()}Js.kt")
            if (!jsActual.exists()) {
                jsActual.parentFile.mkdirs()
                jsActual.writeText("""
                    package $group

                    actual fun platformName(): String = "JavaScript"
                """.trimIndent())
            }
        }

        // Create Native actual
        if (hasNative) {
            val nativeActual = projectRoot.resolve("src/nativeMain/kotlin/$packagePath/${name.capitalize()}Native.kt")
            if (!nativeActual.exists()) {
                nativeActual.parentFile.mkdirs()
                nativeActual.writeText("""
                    package $group

                    actual fun platformName(): String = "Native"
                """.trimIndent())
            }
        }
    }

    /**
     * Print build configuration summary.
     */
    suspend fun printSummary() {
        val deps = commonDependencies()
        val config = buildKmpConfig()
        println("Multiplatform Library: $name")
        println("Group: $group")
        println("Version: $version")
        println("Targets: ${targets.joinToString { it.name }}")
        println()
        if (deps.isNotEmpty()) {
            println("Common Dependencies:")
            deps.forEach { println("  - ${it.groupId}:${it.artifactId}:${it.version}") }
        }
        config.printSummary()
    }

    companion object {
        /**
         * Common target presets for convenience.
         */
        val iosTargets: Set<KmpTarget> = setOf(
            KmpTarget.Native.IosArm64,
            KmpTarget.Native.IosSimulatorArm64
        )

        val macosTargets: Set<KmpTarget> = setOf(
            KmpTarget.Native.MacosX64,
            KmpTarget.Native.MacosArm64
        )

        val allAppleTargets: Set<KmpTarget> = iosTargets + setOf(
            KmpTarget.Native.WatchosArm64,
            KmpTarget.Native.WatchosSimulatorArm64,
            KmpTarget.Native.TvosArm64,
            KmpTarget.Native.TvosSimulatorArm64
        ) + macosTargets

        val linuxTargets: Set<KmpTarget> = setOf(
            KmpTarget.Native.LinuxX64,
            KmpTarget.Native.LinuxArm64
        )

        val desktopTargets: Set<KmpTarget> = macosTargets + linuxTargets + setOf(
            KmpTarget.Native.MingwX64
        )

        val androidNativeTargets: Set<KmpTarget> = setOf(
            KmpTarget.Native.AndroidNativeArm64,
            KmpTarget.Native.AndroidNativeArm32,
            KmpTarget.Native.AndroidNativeX64,
            KmpTarget.Native.AndroidNativeX86
        )

        val allNativeTargets: Set<KmpTarget> = allAppleTargets + linuxTargets + androidNativeTargets + setOf(
            KmpTarget.Native.MingwX64
        )

        /**
         * All supported targets: JVM, JS, and all native platforms.
         */
        val allTargets: Set<KmpTarget> = setOf(KmpTarget.Jvm, KmpTarget.Js) + allNativeTargets
    }
}

// Extension to capitalize for Kotlin < 1.5 compatibility
private fun String.capitalize(): String = replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
