package com.ivieleague.kbuild.js

import com.ivieleague.skate.execute
import java.io.File

object Node {
    val npmExecutable = if (System.getProperty("os.name").contains("win")) "npm.cmd" else "npm"

    init {
        //Check if we have npm
        try {
            execute(npmExecutable)
        } catch (e: Exception) {
            //NPM is not installed
            println("Hey!  You haven't installed NPM - you should go do that.")
            println("You can find it here: https://www.npmjs.com/get-npm")
        }
    }

    fun npm(directory: File, vararg args: String) = execute(arrayOf(npmExecutable, *args), directory)
}