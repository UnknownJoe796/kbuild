package com.ivieleague.kbuild.common

import java.io.File


fun Map<String, TestResult>.toHtml(filesToLink: Map<String, File> = mapOf(), base: File = File(".")) = """
 <!DOCTYPE html>
<html>
<head>
<meta charset="UTF-8">
<title>Test Results</title>
</head>

<body>
${values.sortedWith(compareBy<TestResult> { it.passed }.thenBy { it.identifier }).joinToString("\n") {
    it.toHtml(
        filesToLink,
        base
    )
}}
</body>

</html> 
""".trimIndent()

fun Map<String, TestResult>.record(into: File, filesToLink: Map<String, File>): File {
    return into.apply { parentFile.mkdirs(); writeText(toHtml(filesToLink, into.parentFile)) }
}