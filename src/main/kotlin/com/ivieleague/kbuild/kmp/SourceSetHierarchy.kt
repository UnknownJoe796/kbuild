package com.ivieleague.kbuild.kmp

import java.io.File

/**
 * Creates a standard Kotlin Multiplatform source set hierarchy.
 *
 * The hierarchy follows Kotlin's default structure:
 * ```
 * commonMain ──┬── jvmMain
 *              ├── jsMain
 *              ├── wasmJsMain
 *              └── nativeMain ──┬── appleMain ──┬── iosMain ──┬── iosArm64Main
 *                               │               │             ├── iosSimulatorArm64Main
 *                               │               │             └── iosX64Main
 *                               │               ├── macosMain ──┬── macosX64Main
 *                               │               │               └── macosArm64Main
 *                               │               ├── watchosMain
 *                               │               └── tvosMain
 *                               ├── linuxMain ──┬── linuxX64Main
 *                               │               └── linuxArm64Main
 *                               └── mingwMain ── mingwX64Main
 * ```
 *
 * @param projectRoot The project root directory
 * @param enabledTargets The targets to enable (source sets for disabled targets won't be created)
 * @param useStandardLayout Whether to use standard src/{sourceSet}/kotlin layout
 */
class SourceSetHierarchy(
    val projectRoot: File,
    val enabledTargets: Set<KmpTarget> = emptySet(),
    val useStandardLayout: Boolean = true
) {
    private val sourceSets = mutableMapOf<String, SourceSet>()

    // Common
    val commonMain: SourceSet by lazy { createSourceSet("commonMain") }
    val commonTest: SourceSet by lazy { createSourceSet("commonTest", dependsOn = commonMain) }

    // JVM
    val jvmMain: SourceSet by lazy { createSourceSet("jvmMain", dependsOn = commonMain, target = KmpTarget.Jvm) }
    val jvmTest: SourceSet by lazy { createSourceSet("jvmTest", dependsOn = commonTest, jvmMain) }

    // JS
    val jsMain: SourceSet by lazy { createSourceSet("jsMain", dependsOn = commonMain, target = KmpTarget.Js) }
    val jsTest: SourceSet by lazy { createSourceSet("jsTest", dependsOn = commonTest, jsMain) }

    // Wasm
    val wasmJsMain: SourceSet by lazy { createSourceSet("wasmJsMain", dependsOn = commonMain, target = KmpTarget.Wasm.Js) }
    val wasmJsTest: SourceSet by lazy { createSourceSet("wasmJsTest", dependsOn = commonTest, wasmJsMain) }

    // Native (intermediate - no direct target)
    val nativeMain: SourceSet by lazy { createSourceSet("nativeMain", dependsOn = commonMain) }
    val nativeTest: SourceSet by lazy { createSourceSet("nativeTest", dependsOn = commonTest, nativeMain) }

    // Apple (intermediate)
    val appleMain: SourceSet by lazy { createSourceSet("appleMain", dependsOn = nativeMain) }
    val appleTest: SourceSet by lazy { createSourceSet("appleTest", dependsOn = nativeTest, appleMain) }

    // macOS
    val macosMain: SourceSet by lazy { createSourceSet("macosMain", dependsOn = appleMain) }
    val macosTest: SourceSet by lazy { createSourceSet("macosTest", dependsOn = appleTest, macosMain) }
    val macosX64Main: SourceSet by lazy { createSourceSet("macosX64Main", dependsOn = macosMain, target = KmpTarget.Native.MacosX64) }
    val macosX64Test: SourceSet by lazy { createSourceSet("macosX64Test", dependsOn = macosTest, macosX64Main) }
    val macosArm64Main: SourceSet by lazy { createSourceSet("macosArm64Main", dependsOn = macosMain, target = KmpTarget.Native.MacosArm64) }
    val macosArm64Test: SourceSet by lazy { createSourceSet("macosArm64Test", dependsOn = macosTest, macosArm64Main) }

    // iOS
    val iosMain: SourceSet by lazy { createSourceSet("iosMain", dependsOn = appleMain) }
    val iosTest: SourceSet by lazy { createSourceSet("iosTest", dependsOn = appleTest, iosMain) }
    val iosArm64Main: SourceSet by lazy { createSourceSet("iosArm64Main", dependsOn = iosMain, target = KmpTarget.Native.IosArm64) }
    val iosArm64Test: SourceSet by lazy { createSourceSet("iosArm64Test", dependsOn = iosTest, iosArm64Main) }
    val iosSimulatorArm64Main: SourceSet by lazy { createSourceSet("iosSimulatorArm64Main", dependsOn = iosMain, target = KmpTarget.Native.IosSimulatorArm64) }
    val iosSimulatorArm64Test: SourceSet by lazy { createSourceSet("iosSimulatorArm64Test", dependsOn = iosTest, iosSimulatorArm64Main) }
    val iosX64Main: SourceSet by lazy { createSourceSet("iosX64Main", dependsOn = iosMain, target = KmpTarget.Native.IosX64) }
    val iosX64Test: SourceSet by lazy { createSourceSet("iosX64Test", dependsOn = iosTest, iosX64Main) }

    // watchOS
    val watchosMain: SourceSet by lazy { createSourceSet("watchosMain", dependsOn = appleMain) }
    val watchosTest: SourceSet by lazy { createSourceSet("watchosTest", dependsOn = appleTest, watchosMain) }
    val watchosArm64Main: SourceSet by lazy { createSourceSet("watchosArm64Main", dependsOn = watchosMain, target = KmpTarget.Native.WatchosArm64) }
    val watchosArm64Test: SourceSet by lazy { createSourceSet("watchosArm64Test", dependsOn = watchosTest, watchosArm64Main) }
    val watchosSimulatorArm64Main: SourceSet by lazy { createSourceSet("watchosSimulatorArm64Main", dependsOn = watchosMain, target = KmpTarget.Native.WatchosSimulatorArm64) }
    val watchosSimulatorArm64Test: SourceSet by lazy { createSourceSet("watchosSimulatorArm64Test", dependsOn = watchosTest, watchosSimulatorArm64Main) }

    // tvOS
    val tvosMain: SourceSet by lazy { createSourceSet("tvosMain", dependsOn = appleMain) }
    val tvosTest: SourceSet by lazy { createSourceSet("tvosTest", dependsOn = appleTest, tvosMain) }
    val tvosArm64Main: SourceSet by lazy { createSourceSet("tvosArm64Main", dependsOn = tvosMain, target = KmpTarget.Native.TvosArm64) }
    val tvosArm64Test: SourceSet by lazy { createSourceSet("tvosArm64Test", dependsOn = tvosTest, tvosArm64Main) }
    val tvosSimulatorArm64Main: SourceSet by lazy { createSourceSet("tvosSimulatorArm64Main", dependsOn = tvosMain, target = KmpTarget.Native.TvosSimulatorArm64) }
    val tvosSimulatorArm64Test: SourceSet by lazy { createSourceSet("tvosSimulatorArm64Test", dependsOn = tvosTest, tvosSimulatorArm64Main) }

    // Linux
    val linuxMain: SourceSet by lazy { createSourceSet("linuxMain", dependsOn = nativeMain) }
    val linuxTest: SourceSet by lazy { createSourceSet("linuxTest", dependsOn = nativeTest, linuxMain) }
    val linuxX64Main: SourceSet by lazy { createSourceSet("linuxX64Main", dependsOn = linuxMain, target = KmpTarget.Native.LinuxX64) }
    val linuxX64Test: SourceSet by lazy { createSourceSet("linuxX64Test", dependsOn = linuxTest, linuxX64Main) }
    val linuxArm64Main: SourceSet by lazy { createSourceSet("linuxArm64Main", dependsOn = linuxMain, target = KmpTarget.Native.LinuxArm64) }
    val linuxArm64Test: SourceSet by lazy { createSourceSet("linuxArm64Test", dependsOn = linuxTest, linuxArm64Main) }

    // Windows (MinGW)
    val mingwMain: SourceSet by lazy { createSourceSet("mingwMain", dependsOn = nativeMain) }
    val mingwTest: SourceSet by lazy { createSourceSet("mingwTest", dependsOn = nativeTest, mingwMain) }
    val mingwX64Main: SourceSet by lazy { createSourceSet("mingwX64Main", dependsOn = mingwMain, target = KmpTarget.Native.MingwX64) }
    val mingwX64Test: SourceSet by lazy { createSourceSet("mingwX64Test", dependsOn = mingwTest, mingwX64Main) }

    private fun createSourceSet(
        name: String,
        dependsOn: SourceSet? = null,
        vararg additionalDependsOn: SourceSet,
        target: KmpTarget? = null
    ): SourceSet {
        val allDependsOn = listOfNotNull(dependsOn) + additionalDependsOn.toList()

        val sourceSet = SourceSet(
            name = name,
            sourceDirectories = if (useStandardLayout) {
                setOf(projectRoot.resolve("src/$name/kotlin"))
            } else emptySet(),
            resourceDirectories = if (useStandardLayout) {
                setOf(projectRoot.resolve("src/$name/resources"))
            } else emptySet(),
            dependsOn = allDependsOn.toSet(),
            targets = if (target != null) setOf(target) else emptySet()
        )

        sourceSets[name] = sourceSet
        return sourceSet
    }

    /**
     * Get a source set by name.
     */
    operator fun get(name: String): SourceSet? = sourceSets[name]

    /**
     * Get all main source sets for enabled targets.
     */
    fun getMainSourceSets(): Set<SourceSet> {
        val result = mutableSetOf<SourceSet>()

        // Always include common
        result.add(commonMain)

        // Add target-specific source sets
        for (target in enabledTargets) {
            when (target) {
                KmpTarget.Jvm -> result.addAll(listOf(jvmMain))
                KmpTarget.Js, KmpTarget.Js.Browser, KmpTarget.Js.Node -> result.addAll(listOf(jsMain))
                KmpTarget.Wasm.Js -> result.addAll(listOf(wasmJsMain))
                KmpTarget.Wasm.Wasi -> {} // Not yet implemented
                is KmpTarget.Wasm -> {}

                KmpTarget.Native.MacosX64 -> result.addAll(listOf(nativeMain, appleMain, macosMain, macosX64Main))
                KmpTarget.Native.MacosArm64 -> result.addAll(listOf(nativeMain, appleMain, macosMain, macosArm64Main))

                KmpTarget.Native.IosArm64 -> result.addAll(listOf(nativeMain, appleMain, iosMain, iosArm64Main))
                KmpTarget.Native.IosSimulatorArm64 -> result.addAll(listOf(nativeMain, appleMain, iosMain, iosSimulatorArm64Main))
                KmpTarget.Native.IosX64 -> result.addAll(listOf(nativeMain, appleMain, iosMain, iosX64Main))

                KmpTarget.Native.WatchosArm64 -> result.addAll(listOf(nativeMain, appleMain, watchosMain, watchosArm64Main))
                KmpTarget.Native.WatchosSimulatorArm64 -> result.addAll(listOf(nativeMain, appleMain, watchosMain, watchosSimulatorArm64Main))

                KmpTarget.Native.TvosArm64 -> result.addAll(listOf(nativeMain, appleMain, tvosMain, tvosArm64Main))
                KmpTarget.Native.TvosSimulatorArm64 -> result.addAll(listOf(nativeMain, appleMain, tvosMain, tvosSimulatorArm64Main))

                KmpTarget.Native.LinuxX64 -> result.addAll(listOf(nativeMain, linuxMain, linuxX64Main))
                KmpTarget.Native.LinuxArm64 -> result.addAll(listOf(nativeMain, linuxMain, linuxArm64Main))

                KmpTarget.Native.MingwX64 -> result.addAll(listOf(nativeMain, mingwMain, mingwX64Main))

                KmpTarget.Native.AndroidNativeArm64,
                KmpTarget.Native.AndroidNativeArm32,
                KmpTarget.Native.AndroidNativeX64,
                KmpTarget.Native.AndroidNativeX86 -> result.add(nativeMain) // TODO: Add androidNativeMain hierarchy
            }
        }

        return result
    }

    /**
     * Get all test source sets for enabled targets.
     */
    fun getTestSourceSets(): Set<SourceSet> {
        val result = mutableSetOf<SourceSet>()

        // Always include common test
        result.add(commonTest)

        // Add target-specific test source sets
        for (target in enabledTargets) {
            when (target) {
                KmpTarget.Jvm -> result.add(jvmTest)
                KmpTarget.Js, KmpTarget.Js.Browser, KmpTarget.Js.Node -> result.add(jsTest)
                KmpTarget.Wasm.Js -> result.add(wasmJsTest)
                is KmpTarget.Native -> {
                    result.add(nativeTest)
                    // Could add more specific test source sets if needed
                }
                else -> {}
            }
        }

        return result
    }

    /**
     * Get the source set for a specific target.
     */
    fun getSourceSetForTarget(target: KmpTarget): SourceSet? {
        return when (target) {
            KmpTarget.Jvm -> jvmMain
            KmpTarget.Js, KmpTarget.Js.Browser, KmpTarget.Js.Node -> jsMain
            KmpTarget.Wasm.Js -> wasmJsMain
            KmpTarget.Native.MacosX64 -> macosX64Main
            KmpTarget.Native.MacosArm64 -> macosArm64Main
            KmpTarget.Native.IosArm64 -> iosArm64Main
            KmpTarget.Native.IosSimulatorArm64 -> iosSimulatorArm64Main
            KmpTarget.Native.IosX64 -> iosX64Main
            KmpTarget.Native.WatchosArm64 -> watchosArm64Main
            KmpTarget.Native.WatchosSimulatorArm64 -> watchosSimulatorArm64Main
            KmpTarget.Native.TvosArm64 -> tvosArm64Main
            KmpTarget.Native.TvosSimulatorArm64 -> tvosSimulatorArm64Main
            KmpTarget.Native.LinuxX64 -> linuxX64Main
            KmpTarget.Native.LinuxArm64 -> linuxArm64Main
            KmpTarget.Native.MingwX64 -> mingwX64Main
            else -> null
        }
    }

    /**
     * Get the test source set for a specific target.
     */
    fun getTestSourceSetForTarget(target: KmpTarget): SourceSet? {
        return when (target) {
            KmpTarget.Jvm -> jvmTest
            KmpTarget.Js, KmpTarget.Js.Browser, KmpTarget.Js.Node -> jsTest
            KmpTarget.Wasm.Js -> wasmJsTest
            KmpTarget.Native.MacosX64 -> macosX64Test
            KmpTarget.Native.MacosArm64 -> macosArm64Test
            KmpTarget.Native.IosArm64 -> iosArm64Test
            KmpTarget.Native.IosSimulatorArm64 -> iosSimulatorArm64Test
            KmpTarget.Native.IosX64 -> iosX64Test
            KmpTarget.Native.WatchosArm64 -> watchosArm64Test
            KmpTarget.Native.WatchosSimulatorArm64 -> watchosSimulatorArm64Test
            KmpTarget.Native.TvosArm64 -> tvosArm64Test
            KmpTarget.Native.TvosSimulatorArm64 -> tvosSimulatorArm64Test
            KmpTarget.Native.LinuxX64 -> linuxX64Test
            KmpTarget.Native.LinuxArm64 -> linuxArm64Test
            KmpTarget.Native.MingwX64 -> mingwX64Test
            else -> null
        }
    }
}

/**
 * Create a standard KMP source set hierarchy.
 */
fun kmpSourceSets(
    projectRoot: File,
    enabledTargets: Set<KmpTarget>,
    useStandardLayout: Boolean = true
): SourceSetHierarchy = SourceSetHierarchy(projectRoot, enabledTargets, useStandardLayout)
