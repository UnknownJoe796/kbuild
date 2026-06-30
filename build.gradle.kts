// ============================================================================
// SECONDARY build definition — Gradle is an ESCAPE HATCH, not the canonical build.
//
// kbuild builds itself. The canonical build is Build.kt, driven via:
//   ./run-kbuild.sh Build.compile | Build.test | Build.jar | Build.ide
// (which compiles kbuild from source with no Gradle; see bootstrap/bootstrap.sh).
//
// This file is kept FUNCTIONAL for two reasons only:
//   1. An escape hatch when the self-host is broken (./gradlew build / test).
//   2. To regenerate the from-source bootstrap manifest, bootstrap/classpath.txt,
//      after dependencies change. Regeneration process:
//        a. ./gradlew printClasspath   (prints the resolved runtime classpath)
//        b. Map each jar back to its "group:artifact:version[:classifier] repo-url"
//           line (see the header in bootstrap/classpath.txt for the exact format;
//           note sisu-guice's required 'no_aop' classifier).
//   A kbuild-native regenerator is intentionally deferred.
//
// Direct dependencies are NOT declared here — they live in bootstrap/dependencies.txt (the single
// source of truth, read below and by Build.kt). Edit versions there. Do NOT add features here that
// the canonical Build.kt lacks; keep the two in sync.
// ============================================================================
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask

plugins {
    kotlin("jvm") version "2.3.20"
    kotlin("plugin.serialization") version "2.3.20"
    application
    `maven-publish`
}

group = "com.ivieleague"
version = "1.0-SNAPSHOT"

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            groupId = "com.ivieleague"
            artifactId = "kbuild"
            version = project.version.toString()
        }
    }
}

application {
    mainClass.set("com.ivieleague.kbuild.cli.KBuildCliKt")
}

repositories {
    mavenLocal()
    maven("https://lightningkite-maven.s3.us-west-2.amazonaws.com")
    mavenCentral()
}

dependencies {
    // Single source of truth: bootstrap/dependencies.txt (also read by the canonical Build.kt).
    // Edit dependency versions there, not here, so the Gradle escape hatch and the self-build
    // can never drift apart. Repos (Central + LK S3 + mavenLocal) are declared above.
    file("bootstrap/dependencies.txt").readLines()
        .map { it.substringBefore('#').trim() }
        .filter { it.isNotEmpty() }
        .forEach { line ->
            val parts = line.split(Regex("\\s+"))
            val scope = parts[0]
            val coord = parts[1]
            when (scope) {
                "core" -> add("api", coord)
                "runtime" -> add("runtimeOnly", coord)
                "test" -> add("testImplementation", coord)
                else -> error("Unknown dependency scope '$scope' in bootstrap/dependencies.txt: $line")
            }
        }
}

tasks.withType(KotlinCompilationTask::class) {
    compilerOptions {
        freeCompilerArgs.add("-Xcontext-parameters")
        optIn.add("kotlin.reflect.ExperimentalContextParametersApi")
    }
}

tasks.test {
    useJUnitPlatform()
}

// Task to print runtime classpath for the CLI runner
tasks.register("printClasspath") {
    doLast {
        val classpath = sourceSets.main.get().runtimeClasspath.files.joinToString(":")
        println(classpath)
    }
}
kotlin {
    jvmToolchain(17)
}
