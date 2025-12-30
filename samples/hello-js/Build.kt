package samples.hellojs

import com.ivieleague.kbuild.kotlin.*
import com.ivieleague.kbuild.maven.KlibDependency
import com.ivieleague.kbuild.maven.MavenAether
import com.ivieleague.kbuild.maven.aether
import com.ivieleague.kbuild.npm.NpmProject
import com.ivieleague.kbuild.npm.PackageJson
import com.ivieleague.kbuild.watch.DirectoryWatch
import java.io.File

/**
 * Example build script for Kotlin/JS.
 *
 * This demonstrates:
 * - Compiling Kotlin to JavaScript (ES modules)
 * - Reactive compilation with file watching
 * - npm project setup
 */
object Build {

    val root = File("samples/hello-js")
    val buildDir = root.resolve("build")
    val srcDir = root.resolve("src")
    val outputDir = buildDir.resolve("js")

    // Kotlin/JS standard library
    val jsStdlib = MavenAether.libraries(
        listOf(KlibDependency(Kotlin.standardLibraryJsId).aether())
    ).map { it.default }.toSet()

    // File watching for reactive builds
    val sources = DirectoryWatch(srcDir, "**/*.kt")

    // Reactive compilation
    val compile = ReactiveKotlinJsCompile(
        name = "hello-js",
        sources = sources,
        libraries = jsStdlib.asReactive(),
        outputMode = JsOutputMode.JS,
        moduleKind = JsModuleKind.ES,
        sourceMap = true,
        outputDir = outputDir
    )

    /**
     * Build the project once.
     */
    fun build(): File {
        println("Building Kotlin/JS project...")

        val jsCompiler = KotlinJsCompile(
            name = "hello-js",
            sourceRoots = { setOf(srcDir) },
            libraries = { jsStdlib },
            outputMode = JsOutputMode.JS,
            moduleKind = JsModuleKind.ES,
            sourceMap = true,
            outputDir = outputDir
        )

        val result = jsCompiler()

        println("Build complete! Output: $result")
        println("Generated files:")
        result.walkTopDown().filter { it.isFile }.forEach {
            println("  ${it.relativeTo(result)}")
        }

        return result
    }

    /**
     * Set up npm project for running the output.
     */
    fun setupNpm(): File {
        val npmDir = buildDir.resolve("npm")
        npmDir.mkdirs()

        val packageJson = PackageJson(
            name = "hello-js",
            version = "1.0.0",
            description = "Hello Kotlin/JS example",
            type = "module",
            main = "hello-js.mjs",
            scripts = mapOf(
                "start" to "node hello-js.mjs"
            )
        )

        packageJson.writeTo(npmDir.resolve("package.json"))

        // Copy compiled JS to npm directory
        val jsFile = outputDir.resolve("hello-js.mjs")
        if (jsFile.exists()) {
            jsFile.copyTo(npmDir.resolve("hello-js.mjs"), overwrite = true)
        }
        val mapFile = outputDir.resolve("hello-js.mjs.map")
        if (mapFile.exists()) {
            mapFile.copyTo(npmDir.resolve("hello-js.mjs.map"), overwrite = true)
        }

        println("npm project set up in: $npmDir")
        return npmDir
    }

    /**
     * Run the compiled JavaScript with Node.js.
     */
    fun run() {
        if (!NpmProject.isNodeAvailable()) {
            println("Node.js is not available. Please install Node.js to run.")
            return
        }

        val jsFile = outputDir.resolve("hello-js.mjs")
        if (!jsFile.exists()) {
            println("JS file not found. Running build first...")
            build()
        }

        println("\nRunning with Node.js:")
        println("=" .repeat(40))

        val process = ProcessBuilder("node", jsFile.absolutePath)
            .directory(outputDir)
            .inheritIO()
            .start()

        process.waitFor()
    }

    /**
     * Watch for changes and rebuild automatically.
     */
    fun watch() {
        println("Watching for changes in $srcDir...")
        println("Press Ctrl+C to stop.")

        compile.onCompileStart = { println("\nRecompiling...") }
        compile.onCompileSuccess = { result ->
            println("Compiled successfully!")
            result.walkTopDown().filter { it.isFile }.forEach {
                println("  ${it.relativeTo(result)}")
            }
        }
        compile.onCompileError = { error ->
            println("Compilation failed: ${error.message}")
        }

        val removeListener = compile.addListener { }

        // Keep running
        Thread.currentThread().join()
    }
}

fun main(args: Array<String>) {
    when (args.getOrNull(0)) {
        "build" -> Build.build()
        "run" -> {
            Build.build()
            Build.run()
        }
        "watch" -> Build.watch()
        "npm" -> {
            Build.build()
            Build.setupNpm()
        }
        else -> {
            println("Usage: Build.kt <command>")
            println()
            println("Commands:")
            println("  build  - Compile Kotlin to JavaScript")
            println("  run    - Build and run with Node.js")
            println("  watch  - Watch for changes and rebuild")
            println("  npm    - Set up npm project")
        }
    }
}
