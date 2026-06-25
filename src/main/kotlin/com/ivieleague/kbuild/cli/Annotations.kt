package com.ivieleague.kbuild.cli

/**
 * Annotation to specify a Maven repository for build script dependencies.
 * This is parsed by the CLI but also needs to compile, so we define stub annotations.
 *
 * by Claude
 */
@Target(AnnotationTarget.FILE)
@Retention(AnnotationRetention.SOURCE)
@Repeatable
annotation class Repository(val url: String)

/**
 * Annotation to specify a Maven dependency for the build script.
 * This is parsed by the CLI but also needs to compile, so we define stub annotations.
 *
 * by Claude
 */
@Target(AnnotationTarget.FILE)
@Retention(AnnotationRetention.SOURCE)
@Repeatable
annotation class DependsOn(val maven: String)
