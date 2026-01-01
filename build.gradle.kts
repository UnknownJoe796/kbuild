import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask

plugins {
    kotlin("jvm") version "2.2.0"
    kotlin("plugin.serialization") version "2.2.0"
}

group = "com.ivieleague"
version = "1.0-SNAPSHOT"

repositories {
    mavenLocal()
    maven("https://lightningkite-maven.s3.us-west-2.amazonaws.com")
    mavenCentral()
}

dependencies {
    implementation(kotlin("reflect"))
    api("com.lightningkite:reactive-jvm:6.0.0-prerelease-26")
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    api("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.2.0")

    // ByteBuddy for runtime bytecode patching (K2 JS incremental compiler bug workaround)
    api("net.bytebuddy:byte-buddy:1.14.11")
    api("net.bytebuddy:byte-buddy-agent:1.14.11")
    api("org.jetbrains.kotlin:kotlin-scripting-jsr223:2.2.0")
    api("org.jetbrains.kotlin:kotlin-native-utils:2.2.0")

    // KSP (Kotlin Symbol Processing)
    api("com.google.devtools.ksp:symbol-processing-aa-embeddable:2.2.0-2.0.2")
    api("com.google.devtools.ksp:symbol-processing-api:2.2.0-2.0.2")
    api("com.google.devtools.ksp:symbol-processing-common-deps:2.2.0-2.0.2")

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

    api("org.junit.jupiter:junit-jupiter-api:5.8.1")
    api("org.junit.jupiter:junit-jupiter-engine:5.8.1")
    api("org.junit.platform:junit-platform-launcher:1.10.2")

    testImplementation("org.jetbrains.kotlin:kotlin-test")
}

tasks.withType(KotlinCompilationTask::class) {
    compilerOptions {
        freeCompilerArgs.add("-Xcontext-parameters")
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