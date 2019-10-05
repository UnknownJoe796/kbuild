package com.ivieleague.kbuild.common

import org.apache.commons.text.StringEscapeUtils
import java.io.File
import java.net.InetAddress
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.roundToInt

data class TestResult(
    val identifier: String,
    val passed: Boolean,
    val standardOutput: String,
    val standardError: String,
    val error: String? = null,
    val durationSeconds: Double,
    val runAt: Date,
    val runOn: String = System.getProperty(InetAddress.getLocalHost().hostName) ?: "Unknown Host"
) {
    fun toXml() = """
<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="$identifier" tests="1" skipped="0" failures="${if (passed) 0 else 1}" errors="0" timestamp="${SimpleDateFormat(
        "yyyy-MM-ddTHH:mm:ss"
    ).format(runAt)}" hostname="$runOn" time="$durationSeconds">
<properties/>
<testcase name="Test" classname="$identifier" time="$durationSeconds"/>
<system-out><![CDATA[$standardOutput]]></system-out>
<system-err><![CDATA[$standardError]]></system-err>
</testsuite>

    """.trimIndent()

    companion object {
        val linkRegEx = Regex("\\(([a-zA-Z0-9.]+):([0-9]+)\\)")
    }

    private fun String.linkify(filesToLink: Map<String, File> = mapOf(), base: File = File(".")): String {
        return this.replace(linkRegEx) { result ->
            val fileLocation = filesToLink[result.groupValues[1]] ?: return@replace result.value
            val relative = fileLocation.absoluteFile.relativeTo(base.absoluteFile)
            println("${fileLocation.absoluteFile} relative to ${base.absoluteFile} is ${relative}")
            """<a href=${relative}>${result.value}</a>"""
        }
    }

    fun toHtml(filesToLink: Map<String, File> = mapOf(), base: File = File(".")) = """
        <details>
        ${
    if (passed)
        """<summary><font color="green">✓ $identifier</font></summary>"""
    else
        """<summary><span style="background-color: red"><font color="white">✗ $identifier</font></span></summary>"""
    }
        <h3>Meta</h3>
        <table>
        <tr>
            <td>Run at</td>
            <td>${DateFormat.getDateTimeInstance().format(runAt)}</td>
        </tr>
        <tr>
            <td>Run by</td>
            <td>$runOn</td>
        </tr>
        <tr>
            <td>Took</td>
            <td>${
    when (durationSeconds) {
        in 0.0..0.001 -> durationSeconds.times(1_000_000).roundToInt().toString() + " microseconds"
        in 0.001..1.0 -> durationSeconds.times(1_000).roundToInt().toString() + " milliseconds"
        in 1.0..60.0 -> durationSeconds.times(1_000).roundToInt().div(1000.0).toString() + " seconds"
        else -> "${durationSeconds / 60} minutes and ${durationSeconds % 60} seconds"
    }
    }</td>
        </tr>
        </table>
        ${error?.let {
        "<h3>Error</h3><code>${StringEscapeUtils.escapeHtml4(it).replace("\n", "<br>").linkify(
            filesToLink,
            base
        )}</code>"
    } ?: ""}
        <h3>Standard Out</h3><code>${StringEscapeUtils.escapeHtml4(standardOutput).replace("\n", "<br>").linkify(
        filesToLink,
        base
    )}</code>
        <h3>Standard Error</h3><code>${StringEscapeUtils.escapeHtml4(standardError).replace("\n", "<br>").linkify(
        filesToLink,
        base
    )}</code>
        </details>
    """.trimIndent()
}