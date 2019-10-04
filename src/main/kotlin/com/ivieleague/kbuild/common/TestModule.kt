package com.ivieleague.kbuild.common

interface TestModule : Module {
    val tests: Set<String>
    fun test(tests: Set<String> = this.tests): Map<String, TestResult>
}

