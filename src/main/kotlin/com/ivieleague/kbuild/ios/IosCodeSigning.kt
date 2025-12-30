package com.ivieleague.kbuild.ios

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Manages iOS code signing, including finding signing identities,
 * development teams, and generating export options for IPA creation.
 *
 * Code signing is required for:
 * - Running on physical iOS devices
 * - Distributing via TestFlight or App Store
 * - Ad-hoc distribution
 *
 * Example usage:
 * ```kotlin
 * val team = IosCodeSigning.autoSelectTeam()
 * if (team != null) {
 *     val exportOptions = IosCodeSigning.generateExportOptionsPlist(
 *         outputDir = buildDir,
 *         teamId = team.id,
 *         method = ExportMethod.DEVELOPMENT
 *     )
 * }
 * ```
 */
object IosCodeSigning {

    /**
     * Represents a signing identity (certificate) in the keychain.
     */
    data class SigningIdentity(
        val hash: String,
        val name: String,
        val teamId: String?,
        val type: IdentityType
    ) {
        enum class IdentityType {
            DEVELOPMENT,      // "Apple Development:" or "iPhone Developer:"
            DISTRIBUTION,     // "Apple Distribution:" or "iPhone Distribution:"
            MAC_DEVELOPMENT,  // "Mac Developer:"
            UNKNOWN
        }
    }

    /**
     * Represents an Apple Developer team.
     */
    data class DevelopmentTeam(
        val id: String,
        val name: String,
        val isDevelopment: Boolean = true
    )

    /**
     * Export method for IPA generation.
     */
    enum class ExportMethod(val value: String) {
        /** For development devices registered in your account */
        DEVELOPMENT("development"),
        /** For distribution to specific devices */
        AD_HOC("ad-hoc"),
        /** For App Store and TestFlight distribution */
        APP_STORE("app-store"),
        /** For enterprise in-house distribution */
        ENTERPRISE("enterprise")
    }

    /**
     * Check if code signing is available on this system.
     */
    fun isAvailable(): Boolean {
        return isMacOS() && hasSecurityTool()
    }

    private fun isMacOS(): Boolean {
        return System.getProperty("os.name").lowercase().contains("mac")
    }

    private fun hasSecurityTool(): Boolean {
        return try {
            val process = ProcessBuilder("which", "security").start()
            process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Find all valid code signing identities in the keychain.
     *
     * Uses `security find-identity -v -p codesigning` to list certificates.
     *
     * @return List of signing identities, empty if none found or not on macOS
     */
    fun findSigningIdentities(): List<SigningIdentity> {
        if (!isAvailable()) return emptyList()

        val result = runCommand("security", "find-identity", "-v", "-p", "codesigning")
        if (!result.success) return emptyList()

        return parseSigningIdentities(result.output)
    }

    /**
     * Find all development teams from the available signing identities.
     *
     * @return List of unique development teams
     */
    fun findDevelopmentTeams(): List<DevelopmentTeam> {
        val identities = findSigningIdentities()

        return identities
            .filter { it.teamId != null }
            .groupBy { it.teamId }
            .map { (teamId, identities) ->
                val name = extractTeamName(identities.first().name)
                val isDevelopment = identities.any {
                    it.type == SigningIdentity.IdentityType.DEVELOPMENT
                }
                DevelopmentTeam(teamId!!, name, isDevelopment)
            }
            .distinctBy { it.id }
    }

    /**
     * Automatically select a development team for signing.
     *
     * Prefers development certificates over distribution certificates.
     * If multiple teams are available, selects the first one.
     *
     * @return The selected team, or null if none available
     */
    fun autoSelectTeam(): DevelopmentTeam? {
        val teams = findDevelopmentTeams()

        // Prefer teams with development certificates
        return teams.find { it.isDevelopment } ?: teams.firstOrNull()
    }

    /**
     * Generate an ExportOptions.plist file for xcodebuild -exportArchive.
     *
     * @param outputDir Directory to write the plist file
     * @param teamId The development team ID
     * @param method Export method (development, ad-hoc, app-store, enterprise)
     * @param bundleId Optional bundle ID for provisioning profile mapping
     * @return The generated plist file
     */
    fun generateExportOptionsPlist(
        outputDir: File,
        teamId: String,
        method: ExportMethod = ExportMethod.DEVELOPMENT,
        bundleId: String? = null
    ): File {
        outputDir.mkdirs()
        val plistFile = outputDir.resolve("ExportOptions.plist")

        val provisioningProfilesSection = if (bundleId != null) {
            """
            <key>provisioningProfiles</key>
            <dict>
                <key>$bundleId</key>
                <string>Automatic</string>
            </dict>
            """
        } else {
            ""
        }

        // For development, we use automatic signing
        val signingStyle = when (method) {
            ExportMethod.DEVELOPMENT -> "automatic"
            else -> "manual"
        }

        plistFile.writeText("""
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
            <plist version="1.0">
            <dict>
                <key>method</key>
                <string>${method.value}</string>
                <key>teamID</key>
                <string>$teamId</string>
                <key>signingStyle</key>
                <string>$signingStyle</string>
                <key>stripSwiftSymbols</key>
                <true/>
                <key>uploadSymbols</key>
                <true/>
                <key>compileBitcode</key>
                <false/>
                $provisioningProfilesSection
            </dict>
            </plist>
        """.trimIndent())

        return plistFile
    }

    /**
     * Check if a specific team ID is valid (has signing identities).
     */
    fun isValidTeam(teamId: String): Boolean {
        return findDevelopmentTeams().any { it.id == teamId }
    }

    /**
     * Get a signing identity for a specific team.
     *
     * @param teamId The team ID
     * @param preferDevelopment Whether to prefer development certificates
     * @return The signing identity, or null if not found
     */
    fun getIdentityForTeam(teamId: String, preferDevelopment: Boolean = true): SigningIdentity? {
        val identities = findSigningIdentities().filter { it.teamId == teamId }

        return if (preferDevelopment) {
            identities.find { it.type == SigningIdentity.IdentityType.DEVELOPMENT }
                ?: identities.firstOrNull()
        } else {
            identities.find { it.type == SigningIdentity.IdentityType.DISTRIBUTION }
                ?: identities.firstOrNull()
        }
    }

    // ============== Parsing ==============

    private fun parseSigningIdentities(output: String): List<SigningIdentity> {
        val identities = mutableListOf<SigningIdentity>()

        // Pattern: 1) HASH "Certificate Name"
        val pattern = Regex("""^\s*\d+\)\s+([A-F0-9]+)\s+"(.+)"$""", RegexOption.MULTILINE)

        for (match in pattern.findAll(output)) {
            val hash = match.groupValues[1]
            val name = match.groupValues[2]

            val teamId = extractTeamId(name)
            val type = determineIdentityType(name)

            identities.add(SigningIdentity(hash, name, teamId, type))
        }

        return identities
    }

    private fun extractTeamId(certificateName: String): String? {
        // Team ID is usually in parentheses at the end: "Apple Development: Name (TEAMID)"
        val pattern = Regex("""\(([A-Z0-9]{10})\)$""")
        return pattern.find(certificateName)?.groupValues?.get(1)
    }

    private fun extractTeamName(certificateName: String): String {
        // Extract name between the colon and the team ID
        // "Apple Development: John Doe (TEAMID)" -> "John Doe"
        val withoutPrefix = certificateName
            .replace(Regex("^(Apple Development|Apple Distribution|iPhone Developer|iPhone Distribution|Mac Developer):\\s*"), "")

        return withoutPrefix
            .replace(Regex("""\s*\([A-Z0-9]+\)$"""), "")
            .trim()
    }

    private fun determineIdentityType(name: String): SigningIdentity.IdentityType {
        return when {
            name.startsWith("Apple Development:") -> SigningIdentity.IdentityType.DEVELOPMENT
            name.startsWith("iPhone Developer:") -> SigningIdentity.IdentityType.DEVELOPMENT
            name.startsWith("Apple Distribution:") -> SigningIdentity.IdentityType.DISTRIBUTION
            name.startsWith("iPhone Distribution:") -> SigningIdentity.IdentityType.DISTRIBUTION
            name.startsWith("Mac Developer:") -> SigningIdentity.IdentityType.MAC_DEVELOPMENT
            else -> SigningIdentity.IdentityType.UNKNOWN
        }
    }

    // ============== Command Execution ==============

    private data class CommandResult(
        val success: Boolean,
        val output: String,
        val exitCode: Int
    )

    private fun runCommand(vararg command: String, timeoutSeconds: Long = 30): CommandResult {
        return try {
            val process = ProcessBuilder(*command)
                .redirectErrorStream(true)
                .start()

            val output = process.inputStream.bufferedReader().readText()
            val completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)

            if (!completed) {
                process.destroyForcibly()
                CommandResult(false, "Command timed out", -1)
            } else {
                CommandResult(
                    success = process.exitValue() == 0,
                    output = output,
                    exitCode = process.exitValue()
                )
            }
        } catch (e: Exception) {
            CommandResult(false, e.message ?: "Unknown error", -1)
        }
    }
}
