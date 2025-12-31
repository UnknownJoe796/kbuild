package com.ivieleague.kbuild.kotlin

import net.bytebuddy.ByteBuddy
import net.bytebuddy.agent.ByteBuddyAgent
import net.bytebuddy.asm.Advice
import net.bytebuddy.dynamic.loading.ClassReloadingStrategy
import net.bytebuddy.matcher.ElementMatchers.*
import org.jetbrains.kotlin.build.report.BuildReporter
import org.jetbrains.kotlin.build.report.DoNothingICReporter
import org.jetbrains.kotlin.build.report.ICReporter
import org.jetbrains.kotlin.build.report.metrics.DoNothingBuildMetricsReporter
import org.jetbrains.kotlin.cli.common.arguments.CommonCompilerArguments
import org.jetbrains.kotlin.cli.common.arguments.K2JSCompilerArguments
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.config.IncrementalCompilation
import org.jetbrains.kotlin.incremental.ChangedFiles
import org.jetbrains.kotlin.incremental.CompileScopeExpansionMode
import org.jetbrains.kotlin.incremental.IncrementalJsCompilerRunner
import org.jetbrains.kotlin.incremental.multiproject.EmptyModulesApiHistory
import org.jetbrains.kotlin.incremental.multiproject.ModulesApiHistory
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Patches the K2 JS incremental compiler to fix the NPE bug in
 * TranslationResultMap.remove() during cache updates.
 *
 * The bug occurs because clearCacheForRemovedClasses() iterates dirtySources and
 * assumes each file exists in translationResults, but on first build or cache miss,
 * translationResults[file] returns null, causing NPE at the `!!` operator.
 */
object IncrementalJsCompilerPatcher {
    private val patched = AtomicBoolean(false)
    private var patchingSucceeded = false

    /**
     * Attempts to apply a bytecode patch to fix the NPE bug.
     * Returns true if patching was successful.
     */
    fun ensurePatched(): Boolean {
        if (patched.compareAndSet(false, true)) {
            try {
                // Try to install the ByteBuddy agent dynamically
                ByteBuddyAgent.install()
                applyPatch()
                patchingSucceeded = true
                println("[IC] Successfully patched TranslationResultMap.remove")
            } catch (e: Exception) {
                patchingSucceeded = false
                System.err.println("[IC] WARNING: Failed to apply bytecode patch (will use fallback): ${e.message}")
                e.printStackTrace()
            }
        }
        return patchingSucceeded
    }

    private fun applyPatch() {
        // Find the TranslationResultMap class - it's a nested class in IncrementalJsCache
        // The class name in bytecode is org.jetbrains.kotlin.incremental.TranslationResultMap
        val translationResultMapClass = Class.forName("org.jetbrains.kotlin.incremental.TranslationResultMap")

        // Patch the remove method that takes (File, ChangesCollector)
        // The bug is: val protoBytes = this[sourceFile]!!.metadata
        // We add advice to skip the method body if the file doesn't exist
        ByteBuddy()
            .redefine(translationResultMapClass)
            .visit(
                Advice.to(TranslationResultMapRemoveAdvice::class.java)
                    .on(named<net.bytebuddy.description.method.MethodDescription>("remove").and(takesArguments<net.bytebuddy.description.method.MethodDescription>(2)))
            )
            .make()
            .load(translationResultMapClass.classLoader, ClassReloadingStrategy.fromInstalledAgent())
    }

    /**
     * Advice for TranslationResultMap.remove(File, ChangesCollector)
     * Skips the method body if the file doesn't exist in the map.
     */
    object TranslationResultMapRemoveAdvice {
        @JvmStatic
        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue::class)
        fun onEnter(
            @Advice.This map: Any,
            @Advice.Argument(0) sourceFile: File
        ): Boolean {
            // Check if the file exists in the map using the get method
            // If it returns null, skip the method to avoid NPE
            return try {
                val getMethod = map.javaClass.getMethod("get", Any::class.java)
                val result = getMethod.invoke(map, sourceFile)
                if (result == null) {
                    // File doesn't exist in map, skip method body to avoid NPE
                    true
                } else {
                    // File exists, proceed normally
                    false
                }
            } catch (e: Exception) {
                // If we can't check, proceed with original method
                false
            }
        }
    }
}

/**
 * Fixed version of makeJsIncrementally that applies a bytecode patch to fix
 * the NPE bug in TranslationResultMap.remove().
 *
 * Falls back to NPE-catching workaround if bytecode patching fails.
 */
fun makeJsIncrementallyFixed(
    cachesDir: File,
    sourceRoots: Iterable<File>,
    args: K2JSCompilerArguments,
    buildHistoryFile: File,
    messageCollector: MessageCollector = MessageCollector.NONE,
    reporter: ICReporter = DoNothingICReporter,
    scopeExpansion: CompileScopeExpansionMode = CompileScopeExpansionMode.NEVER,
    modulesApiHistory: ModulesApiHistory = EmptyModulesApiHistory,
    providedChangedFiles: ChangedFiles? = null
) {
    // Try to apply the bytecode patch
    val patchApplied = IncrementalJsCompilerPatcher.ensurePatched()

    val allKotlinFiles = sourceRoots.asSequence().flatMap { it.walk() }
        .filter { it.isFile && it.extension.equals("kt", ignoreCase = true) }.toList()

    val buildReporter = BuildReporter(icReporter = reporter, buildMetricsReporter = DoNothingBuildMetricsReporter)

    withJsIC(args) {
        val compiler = IncrementalJsCompilerRunner(
            cachesDir, buildReporter,
            buildHistoryFile = buildHistoryFile,
            modulesApiHistory = modulesApiHistory,
            scopeExpansion = scopeExpansion
        )

        if (patchApplied) {
            // Patch is applied, just run normally
            compiler.compile(allKotlinFiles, args, messageCollector, providedChangedFiles ?: ChangedFiles.DeterminableFiles.ToBeComputed)
        } else {
            // Fallback: catch the NPE if it occurs
            try {
                compiler.compile(allKotlinFiles, args, messageCollector, providedChangedFiles ?: ChangedFiles.DeterminableFiles.ToBeComputed)
            } catch (e: NullPointerException) {
                // Check if this is the known bug in clearCacheForRemovedClasses
                if (e.stackTrace.any { it.className.contains("TranslationResultMap") && it.methodName == "remove" }) {
                    println("[IC] Detected TranslationResultMap.remove NPE bug - compilation succeeded but cache update failed")
                    // The compilation actually succeeded; the NPE is in the cache update phase.
                    // The output file should exist.
                } else {
                    throw e
                }
            }
        }
    }
}

/**
 * Helper to enable JS incremental compilation for the duration of a block.
 */
@Suppress("DEPRECATION")
inline fun <R> withJsIC(args: CommonCompilerArguments, enabled: Boolean = true, fn: () -> R): R {
    val isJsEnabledBackup = IncrementalCompilation.isEnabledForJs()
    IncrementalCompilation.setIsEnabledForJs(true)

    try {
        if (args.incrementalCompilation == null) {
            args.incrementalCompilation = enabled
        }
        return fn()
    } finally {
        IncrementalCompilation.setIsEnabledForJs(isJsEnabledBackup)
    }
}
