package com.ivieleague.kbuild.ios

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for iOS code signing utilities.
 */
class IosCodeSigningTest {

    @Test
    fun `ExportMethod enum has expected values`() {
        val methods = IosCodeSigning.ExportMethod.entries

        assertEquals(4, methods.size)
        assertTrue(IosCodeSigning.ExportMethod.DEVELOPMENT in methods)
        assertTrue(IosCodeSigning.ExportMethod.AD_HOC in methods)
        assertTrue(IosCodeSigning.ExportMethod.APP_STORE in methods)
        assertTrue(IosCodeSigning.ExportMethod.ENTERPRISE in methods)
    }

    @Test
    fun `ExportMethod values are correct`() {
        assertEquals("development", IosCodeSigning.ExportMethod.DEVELOPMENT.value)
        assertEquals("ad-hoc", IosCodeSigning.ExportMethod.AD_HOC.value)
        assertEquals("app-store", IosCodeSigning.ExportMethod.APP_STORE.value)
        assertEquals("enterprise", IosCodeSigning.ExportMethod.ENTERPRISE.value)
    }

    @Test
    fun `SigningIdentity IdentityType enum has expected values`() {
        val types = IosCodeSigning.SigningIdentity.IdentityType.entries

        assertEquals(4, types.size)
        assertTrue(IosCodeSigning.SigningIdentity.IdentityType.DEVELOPMENT in types)
        assertTrue(IosCodeSigning.SigningIdentity.IdentityType.DISTRIBUTION in types)
        assertTrue(IosCodeSigning.SigningIdentity.IdentityType.MAC_DEVELOPMENT in types)
        assertTrue(IosCodeSigning.SigningIdentity.IdentityType.UNKNOWN in types)
    }

    @Test
    fun `generateExportOptionsPlist creates valid plist for development`() {
        val root = File("build/run/IosCodeSigningTest/exportoptions")
        root.deleteRecursively()
        root.mkdirs()

        val plist = IosCodeSigning.generateExportOptionsPlist(
            outputDir = root,
            teamId = "ABC123XYZ9",
            method = IosCodeSigning.ExportMethod.DEVELOPMENT,
            bundleId = "com.example.testapp"
        )

        assertTrue(plist.exists(), "ExportOptions.plist should exist")
        assertEquals("ExportOptions.plist", plist.name)

        val content = plist.readText()
        assertTrue(content.contains("<?xml version=\"1.0\""), "Should be valid XML")
        assertTrue(content.contains("<plist version=\"1.0\">"), "Should be valid plist")
        assertTrue(content.contains("<key>method</key>"), "Should have method key")
        assertTrue(content.contains("<string>development</string>"), "Should have development method")
        assertTrue(content.contains("<key>teamID</key>"), "Should have teamID key")
        assertTrue(content.contains("<string>ABC123XYZ9</string>"), "Should have correct team ID")
        assertTrue(content.contains("<key>signingStyle</key>"), "Should have signingStyle key")
        assertTrue(content.contains("<string>automatic</string>"), "Development should use automatic signing")
    }

    @Test
    fun `generateExportOptionsPlist creates valid plist for app-store`() {
        val root = File("build/run/IosCodeSigningTest/exportoptions-appstore")
        root.deleteRecursively()
        root.mkdirs()

        val plist = IosCodeSigning.generateExportOptionsPlist(
            outputDir = root,
            teamId = "XYZ987ABC0",
            method = IosCodeSigning.ExportMethod.APP_STORE
        )

        val content = plist.readText()
        assertTrue(content.contains("<string>app-store</string>"), "Should have app-store method")
        assertTrue(content.contains("<string>manual</string>"), "App Store should use manual signing")
    }

    @Test
    fun `generateExportOptionsPlist includes bundle ID when provided`() {
        val root = File("build/run/IosCodeSigningTest/exportoptions-bundleid")
        root.deleteRecursively()
        root.mkdirs()

        val plist = IosCodeSigning.generateExportOptionsPlist(
            outputDir = root,
            teamId = "TEST123456",
            method = IosCodeSigning.ExportMethod.DEVELOPMENT,
            bundleId = "com.mycompany.myapp"
        )

        val content = plist.readText()
        assertTrue(content.contains("<key>provisioningProfiles</key>"), "Should have provisioning profiles")
        assertTrue(content.contains("<key>com.mycompany.myapp</key>"), "Should have bundle ID in profiles")
    }

    @Test
    fun `generateExportOptionsPlist without bundle ID omits provisioning profiles`() {
        val root = File("build/run/IosCodeSigningTest/exportoptions-nobundleid")
        root.deleteRecursively()
        root.mkdirs()

        val plist = IosCodeSigning.generateExportOptionsPlist(
            outputDir = root,
            teamId = "TEST123456",
            method = IosCodeSigning.ExportMethod.DEVELOPMENT,
            bundleId = null
        )

        val content = plist.readText()
        assertFalse(content.contains("<key>provisioningProfiles</key>"),
            "Should not have provisioning profiles when bundle ID is null")
    }

    @Test
    fun `isAvailable returns false on non-macOS`() {
        val isMac = System.getProperty("os.name").lowercase().contains("mac")
        if (!isMac) {
            assertFalse(IosCodeSigning.isAvailable(),
                "Code signing should not be available on non-macOS")
        }
        // On macOS, we just verify it doesn't throw
        IosCodeSigning.isAvailable()
    }

    @Test
    fun `findSigningIdentities returns empty list on non-macOS`() {
        val isMac = System.getProperty("os.name").lowercase().contains("mac")
        if (!isMac) {
            val identities = IosCodeSigning.findSigningIdentities()
            assertTrue(identities.isEmpty(),
                "Should return empty list on non-macOS")
        }
    }

    @Test
    fun `findDevelopmentTeams returns empty list on non-macOS`() {
        val isMac = System.getProperty("os.name").lowercase().contains("mac")
        if (!isMac) {
            val teams = IosCodeSigning.findDevelopmentTeams()
            assertTrue(teams.isEmpty(),
                "Should return empty list on non-macOS")
        }
    }

    @Test
    fun `autoSelectTeam returns null on non-macOS`() {
        val isMac = System.getProperty("os.name").lowercase().contains("mac")
        if (!isMac) {
            val team = IosCodeSigning.autoSelectTeam()
            assertEquals(null, team,
                "Should return null on non-macOS")
        }
    }

    @Test
    fun `SigningIdentity data class holds correct values`() {
        val identity = IosCodeSigning.SigningIdentity(
            hash = "ABCD1234EFGH5678",
            name = "Apple Development: Test User (TEAM123456)",
            teamId = "TEAM123456",
            type = IosCodeSigning.SigningIdentity.IdentityType.DEVELOPMENT
        )

        assertEquals("ABCD1234EFGH5678", identity.hash)
        assertEquals("Apple Development: Test User (TEAM123456)", identity.name)
        assertEquals("TEAM123456", identity.teamId)
        assertEquals(IosCodeSigning.SigningIdentity.IdentityType.DEVELOPMENT, identity.type)
    }

    @Test
    fun `DevelopmentTeam data class holds correct values`() {
        val team = IosCodeSigning.DevelopmentTeam(
            id = "TEAM123456",
            name = "Test User",
            isDevelopment = true
        )

        assertEquals("TEAM123456", team.id)
        assertEquals("Test User", team.name)
        assertTrue(team.isDevelopment)
    }

    // macOS-only tests that verify actual functionality
    @Test
    fun `findSigningIdentities does not throw on macOS`() {
        if (!IosCodeSigning.isAvailable()) {
            println("Skipping test: Not on macOS")
            return
        }

        // Should not throw, even if no certificates are installed
        val identities = IosCodeSigning.findSigningIdentities()
        println("Found ${identities.size} signing identities")
        identities.forEach { identity ->
            println("  ${identity.name} (${identity.type})")
        }
    }

    @Test
    fun `findDevelopmentTeams does not throw on macOS`() {
        if (!IosCodeSigning.isAvailable()) {
            println("Skipping test: Not on macOS")
            return
        }

        val teams = IosCodeSigning.findDevelopmentTeams()
        println("Found ${teams.size} development teams")
        teams.forEach { team ->
            println("  ${team.name} (${team.id}) - dev: ${team.isDevelopment}")
        }
    }

    @Test
    fun `autoSelectTeam does not throw on macOS`() {
        if (!IosCodeSigning.isAvailable()) {
            println("Skipping test: Not on macOS")
            return
        }

        val team = IosCodeSigning.autoSelectTeam()
        if (team != null) {
            println("Auto-selected team: ${team.name} (${team.id})")
        } else {
            println("No team available for auto-selection")
        }
    }

    @Test
    fun `isValidTeam works correctly on macOS`() {
        if (!IosCodeSigning.isAvailable()) {
            println("Skipping test: Not on macOS")
            return
        }

        // Invalid team ID should return false
        assertFalse(IosCodeSigning.isValidTeam("INVALID12345"),
            "Invalid team ID should return false")

        // If we have a valid team, check that it validates
        val team = IosCodeSigning.autoSelectTeam()
        if (team != null) {
            assertTrue(IosCodeSigning.isValidTeam(team.id),
                "Valid team ID should return true")
        }
    }

    @Test
    fun `getIdentityForTeam works correctly on macOS`() {
        if (!IosCodeSigning.isAvailable()) {
            println("Skipping test: Not on macOS")
            return
        }

        val team = IosCodeSigning.autoSelectTeam()
        if (team != null) {
            // Should find identity for valid team
            val identity = IosCodeSigning.getIdentityForTeam(team.id)
            assertNotNull(identity, "Should find identity for valid team")
            assertEquals(team.id, identity.teamId)
            println("Found identity: ${identity.name}")
        }

        // Invalid team should return null
        val invalidIdentity = IosCodeSigning.getIdentityForTeam("INVALID12345")
        assertEquals(null, invalidIdentity)
    }

    // ============== App Store Connect / TestFlight Tests ==============

    @Test
    fun `AppStoreConnectAuth AppleId holds correct values`() {
        val auth = SwiftCompiler.AppStoreConnectAuth.AppleId(
            appleId = "test@example.com",
            appSpecificPassword = "abcd-efgh-ijkl-mnop"
        )

        assertEquals("test@example.com", auth.appleId)
        assertEquals("abcd-efgh-ijkl-mnop", auth.appSpecificPassword)
    }

    @Test
    fun `AppStoreConnectAuth ApiKey holds correct values`() {
        val keyFile = File("/path/to/AuthKey_ABC123.p8")
        val auth = SwiftCompiler.AppStoreConnectAuth.ApiKey(
            keyId = "ABC123",
            issuerId = "12345678-1234-1234-1234-123456789012",
            privateKeyPath = keyFile
        )

        assertEquals("ABC123", auth.keyId)
        assertEquals("12345678-1234-1234-1234-123456789012", auth.issuerId)
        assertEquals(keyFile, auth.privateKeyPath)
    }

    @Test
    fun `UploadResult has correct structure`() {
        val successResult = SwiftCompiler.UploadResult(
            success = true,
            output = "Upload succeeded",
            errorOutput = "",
            requestId = "abc-123-def-456"
        )

        assertTrue(successResult.success)
        assertEquals("Upload succeeded", successResult.output)
        assertEquals("abc-123-def-456", successResult.requestId)

        val failResult = SwiftCompiler.UploadResult(
            success = false,
            output = "",
            errorOutput = "Authentication failed",
            requestId = null
        )

        assertFalse(failResult.success)
        assertEquals("Authentication failed", failResult.errorOutput)
        assertNull(failResult.requestId)
    }

    @Test
    fun `hasAltool returns false on non-macOS`() {
        val isMac = System.getProperty("os.name").lowercase().contains("mac")
        if (!isMac) {
            assertFalse(SwiftCompiler.hasAltool(),
                "altool should not be available on non-macOS")
        }
    }

    @Test
    fun `hasAltool does not throw on macOS`() {
        val isMac = System.getProperty("os.name").lowercase().contains("mac")
        if (isMac) {
            // Should not throw, just return true or false
            val hasIt = SwiftCompiler.hasAltool()
            println("altool available: $hasIt")
        }
    }
}
