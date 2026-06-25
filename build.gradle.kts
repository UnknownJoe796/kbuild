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
//      after dependencies change here. Regeneration process:
//        a. ./gradlew printClasspath   (prints the resolved runtime classpath)
//        b. Map each jar back to its "group:artifact:version[:classifier] repo-url"
//           line (see the header in bootstrap/classpath.txt for the exact format;
//           note sisu-guice's required 'no_aop' classifier).
//   A kbuild-native regenerator is intentionally deferred.
//
// Do NOT add features here that the canonical Build.kt lacks; keep the two in sync.
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
    implementation(kotlin("reflect"))
    api("com.lightningkite:reactive-jvm:6.0.0-prerelease-26")
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    api("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.3.20")

    // Build Tools API: the public, stable compiler entry point used for JVM compilation.
    api("org.jetbrains.kotlin:kotlin-build-tools-api:2.3.20")
    runtimeOnly("org.jetbrains.kotlin:kotlin-build-tools-impl:2.3.20")

    // ByteBuddy for runtime bytecode patching (K2 JS incremental compiler bug workaround)
    api("net.bytebuddy:byte-buddy:1.14.11")
    api("net.bytebuddy:byte-buddy-agent:1.14.11")
    api("org.jetbrains.kotlin:kotlin-scripting-jsr223:2.3.20")
    api("org.jetbrains.kotlin:kotlin-native-utils:2.3.20")

    // KSP (Kotlin Symbol Processing)
    api("com.google.devtools.ksp:symbol-processing-aa-embeddable:2.3.9")
    api("com.google.devtools.ksp:symbol-processing-api:2.3.9")
    api("com.google.devtools.ksp:symbol-processing-common-deps:2.3.9")

    // Interactive REPL
    api("org.jline:jline:3.26.3")

    api("org.eclipse.aether:aether-api:1.0.0.v20140518")
    api("org.eclipse.aether:aether-impl:1.0.0.v20140518")
    api("org.eclipse.aether:aether-util:1.0.0.v20140518")
    api("org.eclipse.aether:aether-connector-basic:1.0.0.v20140518")
    api("org.eclipse.aether:aether-transport-file:1.0.0.v20140518")
    api("org.eclipse.aether:aether-transport-http:1.0.0.v20140518")
    api("org.apache.maven:maven-aether-provider:3.1.0")
    api("org.redundent:kotlin-xml-builder:1.9.1")
    api("org.apache.commons:commons-text:1.11.0")
    api("org.jasypt:jasypt:1.9.3")

    // Native file watching (uses FSEvents on macOS, inotify on Linux)
    api("io.methvin:directory-watcher:0.18.0")

    api("org.junit.jupiter:junit-jupiter-api:5.8.1")
    api("org.junit.jupiter:junit-jupiter-engine:5.8.1")
    api("org.junit.platform:junit-platform-launcher:1.10.2")

    testImplementation("org.jetbrains.kotlin:kotlin-test")
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
