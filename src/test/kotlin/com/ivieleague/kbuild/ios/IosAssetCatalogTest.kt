package com.ivieleague.kbuild.ios

import java.awt.Color
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for iOS asset catalog generation.
 */
class IosAssetCatalogTest {

    @Test
    fun `generate creates Assets xcassets directory`() {
        val root = File("build/run/IosAssetCatalogTest/generate")
        root.deleteRecursively()
        root.mkdirs()

        val assetsDir = root.resolve("Assets.xcassets")
        IosAssetCatalog.generate(assetsDir)

        assertTrue(assetsDir.exists(), "Assets.xcassets should exist")
        assertTrue(assetsDir.isDirectory, "Assets.xcassets should be directory")

        // Check root Contents.json
        val rootContents = assetsDir.resolve("Contents.json")
        assertTrue(rootContents.exists(), "Root Contents.json should exist")
        assertTrue(rootContents.readText().contains("kbuild"), "Should have kbuild as author")
    }

    @Test
    fun `generateAppIcon creates AppIcon appiconset`() {
        val root = File("build/run/IosAssetCatalogTest/appicon")
        root.deleteRecursively()
        root.mkdirs()

        val assetsDir = root.resolve("Assets.xcassets")
        assetsDir.mkdirs()

        val iconsetDir = IosAssetCatalog.generateAppIcon(assetsDir, "#FF5500")

        assertTrue(iconsetDir.exists(), "AppIcon.appiconset should exist")
        assertEquals("AppIcon.appiconset", iconsetDir.name)

        // Check Contents.json
        val contentsJson = iconsetDir.resolve("Contents.json")
        assertTrue(contentsJson.exists(), "Contents.json should exist")

        val contents = contentsJson.readText()
        assertTrue(contents.contains("\"images\""), "Should have images array")
        assertTrue(contents.contains("ios-marketing"), "Should have ios-marketing idiom")
        assertTrue(contents.contains("iphone"), "Should have iphone idiom")
        assertTrue(contents.contains("ipad"), "Should have ipad idiom")

        // Check that icon files were created
        val icons = iconsetDir.listFiles { f -> f.name.endsWith(".png") }
        assertTrue(icons != null && icons.isNotEmpty(), "Should have icon PNG files")

        // Check 1024x1024 icon exists
        val marketingIcon = iconsetDir.resolve("icon_1024x1024@1x.png")
        assertTrue(marketingIcon.exists(), "1024x1024 icon should exist")

        // Verify the marketing icon dimensions
        val image = ImageIO.read(marketingIcon)
        assertEquals(1024, image.width, "Marketing icon width should be 1024")
        assertEquals(1024, image.height, "Marketing icon height should be 1024")
    }

    @Test
    fun `generateIcon creates correct size PNG`() {
        val root = File("build/run/IosAssetCatalogTest/singleicon")
        root.deleteRecursively()
        root.mkdirs()

        val iconFile = root.resolve("test-icon.png")
        IosAssetCatalog.generateIcon(iconFile, 120, Color.BLUE)

        assertTrue(iconFile.exists(), "Icon file should exist")

        val image = ImageIO.read(iconFile)
        assertEquals(120, image.width, "Icon width should be 120")
        assertEquals(120, image.height, "Icon height should be 120")
    }

    @Test
    fun `generateAccentColor creates colorset`() {
        val root = File("build/run/IosAssetCatalogTest/accentcolor")
        root.deleteRecursively()
        root.mkdirs()

        val assetsDir = root.resolve("Assets.xcassets")
        assetsDir.mkdirs()

        val colorsetDir = IosAssetCatalog.generateAccentColor(assetsDir, "#FF0000")

        assertTrue(colorsetDir.exists(), "AccentColor.colorset should exist")
        assertEquals("AccentColor.colorset", colorsetDir.name)

        val contentsJson = colorsetDir.resolve("Contents.json")
        assertTrue(contentsJson.exists(), "Contents.json should exist")

        val contents = contentsJson.readText()
        assertTrue(contents.contains("\"colors\""), "Should have colors array")
        assertTrue(contents.contains("srgb"), "Should specify sRGB color space")
        assertTrue(contents.contains("red"), "Should have red component")
        assertTrue(contents.contains("1.000"), "Red should be 1.0 for #FF0000")
    }

    @Test
    fun `standardIconSizes contains all required sizes`() {
        val sizes = IosAssetCatalog.standardIconSizes

        // Must have marketing icon
        assertTrue(sizes.any { it.size == 1024 && it.idiom == "ios-marketing" },
            "Should have 1024pt marketing icon")

        // Must have iPhone icons
        assertTrue(sizes.any { it.size == 60 && it.scale == 2 && it.idiom == "iphone" },
            "Should have 60@2x iPhone icon")
        assertTrue(sizes.any { it.size == 60 && it.scale == 3 && it.idiom == "iphone" },
            "Should have 60@3x iPhone icon")

        // Must have iPad icons
        assertTrue(sizes.any { it.size == 76 && it.idiom == "ipad" },
            "Should have 76pt iPad icon")

        // Must have Settings icons (29pt)
        assertTrue(sizes.any { it.size == 29 && it.idiom == "iphone" },
            "Should have 29pt settings icon for iPhone")
        assertTrue(sizes.any { it.size == 29 && it.idiom == "ipad" },
            "Should have 29pt settings icon for iPad")
    }

    @Test
    fun `IconSize actualSize calculates correctly`() {
        val size1 = IosAssetCatalog.IconSize(60, 2, "iphone")
        assertEquals(120, size1.actualSize, "60@2x should be 120px")

        val size2 = IosAssetCatalog.IconSize(60, 3, "iphone")
        assertEquals(180, size2.actualSize, "60@3x should be 180px")

        val size3 = IosAssetCatalog.IconSize(1024, 1, "ios-marketing")
        assertEquals(1024, size3.actualSize, "1024@1x should be 1024px")
    }

    @Test
    fun `generate with custom color applies to icons`() {
        val root = File("build/run/IosAssetCatalogTest/customcolor")
        root.deleteRecursively()
        root.mkdirs()

        val assetsDir = root.resolve("Assets.xcassets")
        IosAssetCatalog.generate(assetsDir, iconColor = "#00FF00")

        val iconsetDir = assetsDir.resolve("AppIcon.appiconset")
        assertTrue(iconsetDir.exists())

        // Read a generated icon and verify it has the custom color
        val iconFile = iconsetDir.resolve("icon_1024x1024@1x.png")
        assertTrue(iconFile.exists())

        val image = ImageIO.read(iconFile)
        // Get pixel at corner (should be solid color)
        val pixel = image.getRGB(0, 0)
        val color = Color(pixel)

        // Green component should be high
        assertTrue(color.green > 200, "Icon should have green color")
    }

    @Test
    fun `full asset catalog structure is valid`() {
        val root = File("build/run/IosAssetCatalogTest/fullstructure")
        root.deleteRecursively()
        root.mkdirs()

        val assetsDir = root.resolve("Assets.xcassets")
        IosAssetCatalog.generate(assetsDir, "#007AFF")
        IosAssetCatalog.generateAccentColor(assetsDir, "#007AFF")

        // Verify complete structure
        assertTrue(assetsDir.resolve("Contents.json").exists())
        assertTrue(assetsDir.resolve("AppIcon.appiconset").exists())
        assertTrue(assetsDir.resolve("AppIcon.appiconset/Contents.json").exists())
        assertTrue(assetsDir.resolve("AccentColor.colorset").exists())
        assertTrue(assetsDir.resolve("AccentColor.colorset/Contents.json").exists())

        // Verify icons were generated
        // Note: Some icon sizes share filenames (e.g., 29@2x for iphone and ipad use same file)
        // so the file count may be less than standardIconSizes.size
        val iconFiles = assetsDir.resolve("AppIcon.appiconset")
            .listFiles { f -> f.name.endsWith(".png") }
        assertTrue(iconFiles != null && iconFiles.isNotEmpty(),
            "Should have icon PNG files")
        assertTrue(iconFiles!!.size >= 10,
            "Should have at least 10 unique icon sizes")

        // Verify Contents.json references all standard sizes
        val contentsJson = assetsDir.resolve("AppIcon.appiconset/Contents.json").readText()
        assertEquals(IosAssetCatalog.standardIconSizes.size,
            contentsJson.split("filename").size - 1,
            "Contents.json should have entry for each standard size")
    }
}
