package com.ivieleague.kbuild.kotlin

import com.ivieleague.kbuild.Settings
import org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi
import org.jetbrains.kotlin.buildtools.api.KotlinLogger
import org.jetbrains.kotlin.buildtools.api.KotlinToolchains
import org.jetbrains.kotlin.buildtools.api.jvm.JvmPlatformToolchain
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity

/**
 * Shared entry point for the Kotlin Build Tools API (BTA).
 *
 * The BTA is the public, stable replacement for the internal compiler-driver classes.
 * Loading the implementation is comparatively expensive, so the toolchain instances are
 * resolved once and reused for the lifetime of the process.
 */
@OptIn(ExperimentalBuildToolsApi::class)
internal object BuildToolsApi {
    val toolchains: KotlinToolchains by lazy {
        KotlinToolchains.loadImplementation(BuildToolsApi::class.java.classLoader)
    }
    val jvm: JvmPlatformToolchain by lazy {
        toolchains.getToolchain(JvmPlatformToolchain::class.java)
    }
}

/**
 * Captures compiler diagnostics emitted through the BTA logger so they can be surfaced
 * in a [Kotlin.CompilationException].
 *
 * The BTA reports diagnostics as plain strings rather than the structured
 * [org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation] used by the
 * legacy [Kotlin.CompilationMessageCollector], so error/warning text is preserved without
 * a parsed location.
 */
@OptIn(ExperimentalBuildToolsApi::class)
internal class BtaMessageLogger : KotlinLogger {
    val messages = ArrayList<Kotlin.CompilationMessage>()

    override val isDebugEnabled: Boolean
        get() = Settings.outputLevel <= Settings.OutputLevel.Debug

    override fun error(msg: String, throwable: Throwable?) {
        messages.add(Kotlin.CompilationMessage(CompilerMessageSeverity.ERROR, msg))
        System.err.println(msg)
        throwable?.let { if (isDebugEnabled) it.printStackTrace() }
    }

    override fun warn(msg: String, throwable: Throwable?) {
        messages.add(Kotlin.CompilationMessage(CompilerMessageSeverity.WARNING, msg))
        if (Settings.outputLevel <= Settings.OutputLevel.Normal) println(msg)
    }

    override fun info(msg: String) {
        if (Settings.outputLevel <= Settings.OutputLevel.Normal) println(msg)
    }

    override fun debug(msg: String) {
        if (isDebugEnabled) println(msg)
    }

    override fun lifecycle(msg: String) {
        if (Settings.outputLevel <= Settings.OutputLevel.Normal) println(msg)
    }
}
