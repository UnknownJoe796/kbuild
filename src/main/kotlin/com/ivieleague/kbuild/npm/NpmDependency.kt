package com.ivieleague.kbuild.npm

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * An npm dependency.
 *
 * @param name Package name (e.g., "lodash", "@types/node")
 * @param version Version specification (e.g., "^4.17.21", "~1.0.0", "1.2.3")
 * @param dev Whether this is a dev dependency
 */
@Serializable
data class NpmDependency(
    val name: String,
    val version: String,
    val dev: Boolean = false
) {
    companion object {
        /**
         * Parse a dependency string like "lodash@^4.17.21" or "@types/node@^18.0.0".
         */
        fun parse(spec: String, dev: Boolean = false): NpmDependency {
            val atIndex = spec.lastIndexOf('@')
            return if (atIndex > 0) {
                NpmDependency(
                    name = spec.substring(0, atIndex),
                    version = spec.substring(atIndex + 1),
                    dev = dev
                )
            } else {
                NpmDependency(name = spec, version = "latest", dev = dev)
            }
        }
    }
}

/**
 * Package.json configuration for an npm project.
 */
@Serializable
data class PackageJson(
    val name: String,
    val version: String = "1.0.0",
    val description: String = "",
    val main: String? = null,
    val module: String? = null,
    val type: String = "module",
    val scripts: Map<String, String> = emptyMap(),
    val dependencies: Map<String, String> = emptyMap(),
    val devDependencies: Map<String, String> = emptyMap(),
    val keywords: List<String> = emptyList(),
    val author: String = "",
    val license: String = "MIT",
    val private: Boolean = true
) {
    companion object {
        private val json = Json {
            prettyPrint = true
            encodeDefaults = true
        }

        /**
         * Read package.json from a file.
         */
        fun read(file: File): PackageJson? {
            if (!file.exists()) return null
            return try {
                json.decodeFromString<PackageJson>(file.readText())
            } catch (e: Exception) {
                null
            }
        }
    }

    /**
     * Write this package.json to a file.
     */
    fun writeTo(file: File) {
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(this))
    }

    /**
     * Add dependencies to this package.json.
     */
    fun withDependencies(deps: List<NpmDependency>): PackageJson {
        val newDeps = dependencies.toMutableMap()
        val newDevDeps = devDependencies.toMutableMap()

        deps.forEach { dep ->
            if (dep.dev) {
                newDevDeps[dep.name] = dep.version
            } else {
                newDeps[dep.name] = dep.version
            }
        }

        return copy(
            dependencies = newDeps,
            devDependencies = newDevDeps
        )
    }
}

/**
 * Manages npm dependencies for a project.
 *
 * @param projectDir The project directory containing package.json
 */
class NpmProject(val projectDir: File) {

    val packageJsonFile: File = projectDir.resolve("package.json")
    val nodeModulesDir: File = projectDir.resolve("node_modules")

    /**
     * Initialize or read existing package.json.
     */
    fun getOrCreatePackageJson(
        name: String,
        version: String = "1.0.0"
    ): PackageJson {
        return PackageJson.read(packageJsonFile) ?: PackageJson(
            name = name,
            version = version
        )
    }

    /**
     * Write package.json with the given dependencies.
     */
    fun writePackageJson(packageJson: PackageJson) {
        packageJson.writeTo(packageJsonFile)
    }

    /**
     * Install dependencies using npm.
     *
     * @return true if installation succeeded
     */
    fun install(): Boolean {
        if (!packageJsonFile.exists()) {
            throw IllegalStateException("package.json not found in $projectDir")
        }

        val process = ProcessBuilder("npm", "install")
            .directory(projectDir)
            .inheritIO()
            .start()

        return process.waitFor() == 0
    }

    /**
     * Check if node_modules exists and is up to date.
     */
    fun isInstalled(): Boolean {
        if (!nodeModulesDir.exists()) return false
        if (!packageJsonFile.exists()) return false

        // Simple check: node_modules is newer than package.json
        return nodeModulesDir.lastModified() >= packageJsonFile.lastModified()
    }

    /**
     * Install dependencies if needed.
     *
     * @return true if already installed or installation succeeded
     */
    fun ensureInstalled(): Boolean {
        if (isInstalled()) return true
        return install()
    }

    /**
     * Run an npm script.
     *
     * @param script The script name defined in package.json
     * @return The process exit code
     */
    fun runScript(script: String): Int {
        val process = ProcessBuilder("npm", "run", script)
            .directory(projectDir)
            .inheritIO()
            .start()

        return process.waitFor()
    }

    /**
     * Run npx command.
     *
     * @param command The npx command to run
     * @param args Additional arguments
     * @return The process exit code
     */
    fun npx(command: String, vararg args: String): Int {
        val process = ProcessBuilder("npx", command, *args)
            .directory(projectDir)
            .inheritIO()
            .start()

        return process.waitFor()
    }

    companion object {
        /**
         * Check if npm is available.
         */
        fun isNpmAvailable(): Boolean {
            return try {
                val process = ProcessBuilder("npm", "--version")
                    .start()
                process.waitFor() == 0
            } catch (e: Exception) {
                false
            }
        }

        /**
         * Check if Node.js is available.
         */
        fun isNodeAvailable(): Boolean {
            return try {
                val process = ProcessBuilder("node", "--version")
                    .start()
                process.waitFor() == 0
            } catch (e: Exception) {
                false
            }
        }

        /**
         * Get the npm version.
         */
        fun getNpmVersion(): String? {
            return try {
                val process = ProcessBuilder("npm", "--version")
                    .start()
                if (process.waitFor() == 0) {
                    process.inputStream.bufferedReader().readText().trim()
                } else null
            } catch (e: Exception) {
                null
            }
        }

        /**
         * Get the Node.js version.
         */
        fun getNodeVersion(): String? {
            return try {
                val process = ProcessBuilder("node", "--version")
                    .start()
                if (process.waitFor() == 0) {
                    process.inputStream.bufferedReader().readText().trim()
                } else null
            } catch (e: Exception) {
                null
            }
        }
    }
}

/**
 * DSL for building npm dependencies.
 */
class NpmDependenciesBuilder {
    private val dependencies = mutableListOf<NpmDependency>()

    fun dependency(name: String, version: String) {
        dependencies.add(NpmDependency(name, version, dev = false))
    }

    fun devDependency(name: String, version: String) {
        dependencies.add(NpmDependency(name, version, dev = true))
    }

    fun build(): List<NpmDependency> = dependencies.toList()
}

/**
 * DSL for declaring npm dependencies.
 */
fun npmDependencies(block: NpmDependenciesBuilder.() -> Unit): List<NpmDependency> {
    return NpmDependenciesBuilder().apply(block).build()
}
