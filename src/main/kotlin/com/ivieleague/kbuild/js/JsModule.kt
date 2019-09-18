package com.ivieleague.kbuild.js

import com.ivieleague.kbuild.common.HasLibraries
import java.io.File

interface JsModule : HasLibraries {
    val jsFiles: Sequence<File>
        get() = libraries.asSequence().flatMap {
            it.default.walkTopDown().filter { it.extension == "js" }
        }
}