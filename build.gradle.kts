import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask

plugins {
    kotlin("jvm") version "2.1.20"
    kotlin("plugin.serialization") version "2.1.20"
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
    api("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.1.20")
//    api("org.jetbrains.kotlin:kotlin-script-util:2.1.20")
//    api("org.jetbrains.kotlin:kotlin-script-runtime:2.1.20")
//    api("org.jetbrains.kotlin:kotlin-scripting-compiler-embeddable:2.1.20")
    api("org.jetbrains.kotlin:kotlin-native-utils:2.1.20")

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
        freeCompilerArgs.add("-Xcontext-receivers")
    }
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(17)
}