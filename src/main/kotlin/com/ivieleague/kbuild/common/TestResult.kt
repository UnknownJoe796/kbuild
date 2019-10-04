package com.ivieleague.kbuild.common

import java.net.InetAddress
import java.text.SimpleDateFormat
import java.util.*

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
    ).format(runAt)}" hostname="${runOn}" time="$durationSeconds">
<properties/>
<testcase name="Test" classname="$identifier" time="$durationSeconds"/>
<system-out><![CDATA[$standardOutput]]></system-out>
<system-err><![CDATA[$standardError]]></system-err>
</testsuite>

    """.trimIndent()
}