package com.ivieleague.kbuild.common

interface TestModule : Module {
    fun test(testFilter: (String) -> Boolean = { true })
}

