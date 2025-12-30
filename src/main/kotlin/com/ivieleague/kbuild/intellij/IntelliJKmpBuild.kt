package com.ivieleague.kbuild.intellij

import com.ivieleague.kbuild.kmp.KmpProject
import com.ivieleague.kbuild.kmp.KmpTarget
import com.ivieleague.kbuild.kmp.SourceSet
import org.redundent.kotlin.xml.Node
import java.io.File

/**
 * Generates IntelliJ IDEA project files for Kotlin Multiplatform projects.
 *
 * Creates:
 * - .idea/modules.xml - Module registration
 * - .idea/kotlinc.xml - Kotlin compiler settings
 * - .idea/misc.xml - Project settings
 * - *.iml files for each source set
 * - Library files for dependencies
 *
 * Example:
 * ```
 * val intellij = IntelliJKmpBuild(project)
 * intellij() // Generate all IntelliJ files
 * ```
 */
class IntelliJKmpBuild(
    val project: KmpProject
) : () -> File {

    private val root = project.projectRoot
    private val ideaFolder = root.resolve(".idea")

    override fun invoke(): File {
        ideaFolder.mkdirs()

        // Generate module files
        val moduleFiles = generateModuleFiles()

        // Generate project files
        generateKotlincXml()
        generateModulesXml(moduleFiles)
        generateMiscXml()

        // Generate library files
        generateLibraryFiles()

        println("Generated IntelliJ project files in ${ideaFolder.absolutePath}")
        return root
    }

    /**
     * Generate .iml files for each source set.
     */
    private fun generateModuleFiles(): List<File> {
        val moduleFiles = mutableListOf<File>()

        // Create a module for each main source set with sources
        val mainSourceSets = project.sourceSets.getMainSourceSets()
            .filter { it.sourceDirectories.any { dir -> dir.exists() } }

        for (sourceSet in mainSourceSets) {
            val moduleFile = generateModuleFile(sourceSet)
            moduleFiles.add(moduleFile)
        }

        return moduleFiles
    }

    /**
     * Generate an .iml file for a source set.
     */
    private fun generateModuleFile(sourceSet: SourceSet): File {
        val moduleName = "${project.name}-${sourceSet.name}"
        val moduleFile = root.resolve("$moduleName.iml")

        moduleFile.writeText(Node("module").apply {
            val moduleRootVar = "\$MODULE_DIR\$"
            includeXmlProlog = true
            attribute("type", "JAVA_MODULE")
            attribute("version", "4")

            "component"("name" to "NewModuleRootManager", "inherit-compiler-output" to "true") {
                "exclude-output"()

                // Add content root with source folders
                "content"("url" to "file://$moduleRootVar") {
                    for (srcDir in sourceSet.allSourceDirectories) {
                        if (srcDir.exists()) {
                            val rel = srcDir.relativeTo(root).invariantSeparatorsPath
                            "sourceFolder"(
                                "url" to "file://$moduleRootVar/$rel",
                                "isTestSource" to sourceSet.isTest.toString()
                            )
                        }
                    }
                    for (resDir in sourceSet.allResourceDirectories) {
                        if (resDir.exists()) {
                            val rel = resDir.relativeTo(root).invariantSeparatorsPath
                            "sourceFolder"(
                                "url" to "file://$moduleRootVar/$rel",
                                "type" to if (sourceSet.isTest) "java-test-resource" else "java-resource"
                            )
                        }
                    }
                }

                "orderEntry"("type" to "inheritedJdk")
                "orderEntry"("type" to "sourceFolder", "forTests" to "false")

                // Add dependencies to other modules (for source set hierarchy)
                for (depSourceSet in sourceSet.dependsOn) {
                    val depModuleName = "${project.name}-${depSourceSet.name}"
                    "orderEntry"(
                        "type" to "module",
                        "module-name" to depModuleName,
                        "exported" to ""
                    )
                }

                // Add library dependencies
                val target = sourceSet.targets.firstOrNull() ?: inferTargetFromSourceSet(sourceSet)
                if (target != null) {
                    val libraries = project.dependencies.resolveForTarget(target)
                    for (lib in libraries) {
                        "orderEntry"(
                            "type" to "library",
                            "level" to "project",
                            "name" to lib.fileSafeName
                        )
                    }
                }
            }
        }.toString(prettyFormat = true))

        return moduleFile
    }

    /**
     * Infer a target from a source set name.
     */
    private fun inferTargetFromSourceSet(sourceSet: SourceSet): KmpTarget? {
        return when {
            sourceSet.name.startsWith("jvm") -> KmpTarget.Jvm
            sourceSet.name.startsWith("js") -> KmpTarget.Js
            sourceSet.name.startsWith("common") -> KmpTarget.Jvm // Use JVM for common
            sourceSet.name.startsWith("native") -> KmpTarget.Native.host()
            sourceSet.name.startsWith("macos") -> KmpTarget.Native.host().takeIf {
                it == KmpTarget.Native.MacosArm64 || it == KmpTarget.Native.MacosX64
            } ?: KmpTarget.Native.MacosArm64
            sourceSet.name.startsWith("ios") -> KmpTarget.Native.IosSimulatorArm64
            sourceSet.name.startsWith("linux") -> KmpTarget.Native.LinuxX64
            sourceSet.name.startsWith("mingw") -> KmpTarget.Native.MingwX64
            else -> null
        }
    }

    /**
     * Generate kotlinc.xml with Kotlin compiler settings.
     */
    private fun generateKotlincXml() {
        ideaFolder.resolve("kotlinc.xml").writeText(Node("project").apply {
            includeXmlProlog = true
            attribute("version", "4")

            "component"("name" to "Kotlin2JvmCompilerArguments") {
                "option"("name" to "jvmTarget", "value" to "17")
            }

            "component"("name" to "KotlinCommonCompilerArguments") {
                "option"("name" to "apiVersion", "value" to "2.1")
                "option"("name" to "languageVersion", "value" to "2.1")
            }

            // Kotlin Multiplatform facet
            "component"("name" to "KotlinMultiplatformCommonCompilerArguments") {
                "option"("name" to "multiPlatform", "value" to "true")
            }
        }.toString(prettyFormat = true))
    }

    /**
     * Generate modules.xml with module registration.
     */
    private fun generateModulesXml(moduleFiles: List<File>) {
        val projectRootIndicator = "\$PROJECT_DIR\$"

        ideaFolder.resolve("modules.xml").writeText(Node("project").apply {
            includeXmlProlog = true
            attribute("version", "4")

            "component"("name" to "ProjectModuleManager") {
                "modules"() {
                    for (moduleFile in moduleFiles) {
                        val rel = moduleFile.relativeTo(root).invariantSeparatorsPath
                        "module"(
                            "fileurl" to "file://$projectRootIndicator/$rel",
                            "filepath" to "$projectRootIndicator/$rel"
                        )
                    }
                }
            }
        }.toString(prettyFormat = true))
    }

    /**
     * Generate misc.xml with project settings.
     */
    private fun generateMiscXml() {
        val projectRootIndicator = "\$PROJECT_DIR\$"

        ideaFolder.resolve("misc.xml").writeText(Node("project").apply {
            includeXmlProlog = true
            attribute("version", "4")

            "component"(
                "name" to "ProjectRootManager",
                "version" to "2",
                "languageLevel" to "JDK_17",
                "default" to "true",
                "project-jdk-name" to "17",
                "project-jdk-type" to "JavaSDK"
            ) {
                "output"("url" to "file://$projectRootIndicator/build/out")
            }
        }.toString(prettyFormat = true))
    }

    /**
     * Generate library files for all dependencies.
     */
    private fun generateLibraryFiles() {
        val librariesDir = ideaFolder.resolve("libraries")
        librariesDir.mkdirs()

        val allLibraries = mutableSetOf<com.ivieleague.kbuild.common.Library>()

        // Collect libraries from all targets
        for (target in project.targets) {
            allLibraries.addAll(project.dependencies.resolveForTarget(target))
        }

        // Generate library files
        for (library in allLibraries) {
            library.intelliJLibraryFile(root)
        }
    }
}

/**
 * Extension function to create IntelliJ project files for a KMP project.
 */
fun KmpProject.intellij(): IntelliJKmpBuild = IntelliJKmpBuild(this)
