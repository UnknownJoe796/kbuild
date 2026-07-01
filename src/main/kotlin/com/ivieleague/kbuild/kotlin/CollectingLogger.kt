@file:OptIn(ExperimentalBuildToolsApi::class)

package com.ivieleague.kbuild.kotlin

import org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi
import org.jetbrains.kotlin.buildtools.api.KotlinLogger

/**
 * A [KotlinLogger] that captures error diagnostics for the caller while mirroring lesser severities
 * to the console. Used by the BTA compile drivers ([DaemonJvmCompileDriver], [DaemonJsCompileDriver]),
 * which run in the isolated loader and hand [errors] back across the boundary as plain strings.
 */
internal class CollectingLogger(override val isDebugEnabled: Boolean) : KotlinLogger {
    val errors = ArrayList<String>()
    override fun error(msg: String, throwable: Throwable?) {
        errors.add(msg)
        System.err.println(msg)
        if (isDebugEnabled) throwable?.printStackTrace()
    }
    override fun warn(msg: String, throwable: Throwable?) = println(msg)
    override fun info(msg: String) { if (isDebugEnabled) println(msg) }
    override fun debug(msg: String) { if (isDebugEnabled) println(msg) }
    override fun lifecycle(msg: String) { if (isDebugEnabled) println(msg) }
}
