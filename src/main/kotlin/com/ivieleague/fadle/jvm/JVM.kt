package com.ivieleague.fadle.jvm

import java.io.File
import java.lang.reflect.Method
import java.net.URLClassLoader

object JVM {
    class JarFileLoader() : URLClassLoader(arrayOf()) {
        fun addFile(file: File) {
            addURL(file.toURI().toURL())
        }
    }

    fun runMain(jars: List<File>, mainClass: String, arguments: Array<*>) {
        val jarLoader = JarFileLoader()
        for (jar in jars) {
            jarLoader.addFile(jar)
        }
        jarLoader.loadClass(mainClass).let {
            it.getDeclaredMethodOrNull("main", Array<Any?>::class.java)
                ?.apply { invoke(jarLoader, *arguments) } ?: it.getDeclaredMethodOrNull("main")
                ?.apply { invoke(jarLoader) }
            ?: println("Could not find main function.")
        }
    }

    fun Class<*>.getDeclaredMethodOrNull(name: String, vararg parameterTypes: Class<*>): Method? = try {
        getDeclaredMethod(name, *parameterTypes)
    } catch (e: NoSuchMethodException) {
        null
    }
}