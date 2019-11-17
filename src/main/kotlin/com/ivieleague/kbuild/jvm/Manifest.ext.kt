package com.ivieleague.kbuild.jvm

import java.io.File
import java.util.jar.Manifest
import kotlin.reflect.KProperty

fun DefaultManifest(): Manifest = Manifest().also {
    it["Manifest-Version"] = "1.0"
    it["Created-By"] = System.getProperty("java.version") + " (KBuild)"
}

fun Manifest(vararg args: Pair<String, String>): Manifest = DefaultManifest().apply {
    for ((key, value) in args) {
        this[key] = value
    }
}

operator fun Manifest.get(key: String): String? = this.mainAttributes.getValue(key)
operator fun Manifest.set(key: String, value: String?) = this.mainAttributes.putValue(key, value)

class ManifestProperty(val key: String) {
    operator fun getValue(thisRef: Manifest, property: KProperty<*>): String? {
        return thisRef[key]
    }

    operator fun setValue(thisRef: Manifest, property: KProperty<*>, value: String?) {
        thisRef[key] = value
    }
}

var Manifest.mainClass: String? by ManifestProperty("Main-Class")
var Manifest.classpath: List<File>?
    get() = this["Class-Path"]?.split(" ")?.map { File(it) }
    set(value) {
        this["Class-Path"] = value?.joinToString(" ")
    }