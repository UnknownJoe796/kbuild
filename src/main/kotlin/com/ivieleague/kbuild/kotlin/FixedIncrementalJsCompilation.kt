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
 * Enhanced incremental JS compilation with comprehensive patching and debugging.
 *
 * This patches multiple points in the incremental compilation pipeline to:
 * 1. Fix the NPE in TranslationResultMap.remove()
 * 2. Add debug output to understand the "Conflicting overloads" issue
 */
object EnhancedIncrementalJsPatcher {
    private val patched = AtomicBoolean(false)
    var patchingSucceeded = false
        private set

    fun ensurePatched(): Boolean {
        if (patched.compareAndSet(false, true)) {
            try {
                ByteBuddyAgent.install()
                applyPatches()
                patchingSucceeded = true
            } catch (e: Exception) {
                patchingSucceeded = false
                System.err.println("[IC] WARNING: Failed to apply JS incremental patches: ${e.message}")
            }
        }
        return patchingSucceeded
    }

    private fun applyPatches() {
        // Patch 1: Fix NPE in TranslationResultMap.remove()
        patchTranslationResultMapRemove()

        // Patch 2: Add debug to IncrementalDataProviderFromCache to see what data is provided
        patchIncrementalDataProvider()

        // Patch 3: All IncrementalJsCache patches combined (to avoid overwriting)
        patchIncrementalJsCache()
    }

    private fun patchTranslationResultMapRemove() {
        val translationResultMapClass = Class.forName("org.jetbrains.kotlin.incremental.TranslationResultMap")

        ByteBuddy()
            .redefine(translationResultMapClass)
            .visit(
                Advice.to(TranslationResultMapRemoveAdvice::class.java)
                    .on(named<net.bytebuddy.description.method.MethodDescription>("remove")
                        .and(takesArguments<net.bytebuddy.description.method.MethodDescription>(2)))
            )
            .make()
            .load(translationResultMapClass.classLoader, ClassReloadingStrategy.fromInstalledAgent())
    }

    private fun patchIncrementalDataProvider() {
        // No-op: Was for debugging, no longer needed
    }

    private fun patchIncrementalJsCache() {
        val cacheClass = Class.forName("org.jetbrains.kotlin.incremental.IncrementalJsCache")

        // Apply all patches to IncrementalJsCache in one go to avoid overwriting
        ByteBuddy()
            .redefine(cacheClass)
            .visit(
                Advice.to(NonDirtyPackagePartsAdvice::class.java)
                    .on(named<net.bytebuddy.description.method.MethodDescription>("nonDirtyPackageParts"))
            )
            .visit(
                Advice.to(NonDirtyIrPartsAdvice::class.java)
                    .on(named<net.bytebuddy.description.method.MethodDescription>("nonDirtyIrParts"))
            )
            .make()
            .load(cacheClass.classLoader, ClassReloadingStrategy.fromInstalledAgent())
    }

    // Advice classes
    object TranslationResultMapRemoveAdvice {
        @JvmStatic
        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue::class)
        fun onEnter(
            @Advice.This map: Any,
            @Advice.Argument(0) sourceFile: File
        ): Boolean {
            // FIX: Check if file exists in map before calling remove to avoid NPE
            // The original code does: val protoBytes = this[sourceFile]!!.metadata
            // which throws NPE if the file is not in the map
            return try {
                val getMethod = map.javaClass.getMethod("get", Any::class.java)
                val result = getMethod.invoke(map, sourceFile)
                result == null  // Skip method if file not in map to avoid NPE
            } catch (e: Exception) {
                false  // Proceed with original method on error
            }
        }
    }

    object NonDirtyPackagePartsAdvice {
        @JvmStatic
        @Advice.OnMethodEnter
        fun onEnter(@Advice.This cache: Any) {
            try {
                val dirtySourcesField = cache.javaClass.getDeclaredField("dirtySources")
                dirtySourcesField.isAccessible = true
                @Suppress("UNCHECKED_CAST")
                val dirtySources = dirtySourcesField.get(cache) as MutableSet<File>

                // FIX: Convert relative paths to absolute paths in dirtySources
                // This fixes the K2 JS incremental compiler bug where markDirty() stores relative
                // paths but translationResults uses absolute paths, causing the filter to fail
                val hasRelativePaths = dirtySources.any { !it.isAbsolute }
                if (hasRelativePaths) {
                    val absolutePaths = dirtySources.map { it.absoluteFile }.toSet()
                    dirtySources.clear()
                    dirtySources.addAll(absolutePaths)
                }
            } catch (e: Exception) {
                System.err.println("[IC] nonDirtyPackageParts path fix failed: ${e.message}")
            }
        }
    }

    object NonDirtyIrPartsAdvice {
        @JvmStatic
        @Advice.OnMethodEnter
        fun onEnter(@Advice.This cache: Any) {
            try {
                val dirtySourcesField = cache.javaClass.getDeclaredField("dirtySources")
                dirtySourcesField.isAccessible = true
                @Suppress("UNCHECKED_CAST")
                val dirtySources = dirtySourcesField.get(cache) as MutableSet<File>

                // FIX: Same fix for nonDirtyIrParts - convert relative to absolute paths
                val hasRelativePaths = dirtySources.any { !it.isAbsolute }
                if (hasRelativePaths) {
                    val absolutePaths = dirtySources.map { it.absoluteFile }.toSet()
                    dirtySources.clear()
                    dirtySources.addAll(absolutePaths)
                }
            } catch (e: Exception) {
                System.err.println("[IC] nonDirtyIrParts path fix failed: ${e.message}")
            }
        }
    }
}

/**
 * Entry point for enhanced incremental JS compilation with ByteBuddy patches.
 *
 * This fixes two bugs in the K2 JS incremental compiler:
 * 1. NPE in TranslationResultMap.remove() - fixed by skipping if file not in map
 * 2. Path mismatch in dirty source filtering - fixed by normalizing relative paths to absolute
 */
fun makeJsIncrementallyEnhanced(
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
    // Apply all patches
    EnhancedIncrementalJsPatcher.ensurePatched()

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
        compiler.compile(allKotlinFiles, args, messageCollector, providedChangedFiles ?: ChangedFiles.DeterminableFiles.ToBeComputed)
    }
}
