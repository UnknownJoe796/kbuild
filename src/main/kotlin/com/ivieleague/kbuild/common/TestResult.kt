package com.ivieleague.kbuild.common

data class TestResult(
    val testName: String,
    val passed: Boolean,
    val standardOutput: String,
    val standardError: String,
    val error: String
)