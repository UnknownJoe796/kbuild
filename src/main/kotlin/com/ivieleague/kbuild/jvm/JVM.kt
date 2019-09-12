package com.ivieleague.kbuild.jvm

import java.io.File
import java.lang.reflect.Method
import java.net.URLClassLoader

object JVM {
    class JarFileLoader() : URLClassLoader(arrayOf()) {
        fun addFile(file: File) {
            addURL(file.toURI().toURL())
        }
    }

    fun runMain(jars: List<File>, mainClass: String, arguments: Array<*>): Int {
        val jarLoader = JarFileLoader()
        for (jar in jars) {
            jarLoader.addFile(jar)
        }
        val mainMethod = jarLoader.loadClass(mainClass).let {
            it.getDeclaredMethodOrNull("main", Array<Any?>::class.java)
                ?: it.getDeclaredMethodOrNull("main")
                ?: run {
                    throw IllegalArgumentException("Could not find main function.")
                }
        }
        return mainMethod.invoke(jarLoader, *arguments).let { it as? Int } ?: 0
    }

    fun Class<*>.getDeclaredMethodOrNull(name: String, vararg parameterTypes: Class<*>): Method? = try {
        getDeclaredMethod(name, *parameterTypes)
    } catch (e: NoSuchMethodException) {
        null
    }
}