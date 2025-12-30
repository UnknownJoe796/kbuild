package com.ivieleague.kbuild.ios

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Generates iOS asset catalogs for app icons and other resources.
 *
 * Modern Xcode (14+) only requires a single 1024x1024 icon for iOS apps -
 * all other sizes are generated automatically. This class creates the minimal
 * asset catalog structure needed for a valid iOS app.
 *
 * Example usage:
 * ```kotlin
 * val catalog = IosAssetCatalog()
 * catalog.generateAppIcon(appDir.resolve("Assets.xcassets"), "#007AFF")
 * ```
 */
object IosAssetCatalog {

    /**
     * iOS app icon sizes required for the asset catalog.
     * Modern Xcode only requires 1024x1024 but we include common sizes for compatibility.
     */
    data class IconSize(
        val size: Int,
        val scale: Int,
        val idiom: String,
        val filename: String = "icon_${size}x${size}@${scale}x.png"
    ) {
        val actualSize: Int get() = size * scale
    }

    /**
     * Standard iOS app icon sizes.
     * For iOS 12+, only the 1024pt icon is strictly required.
     */
    val standardIconSizes = listOf(
        // App Store
        IconSize(1024, 1, "ios-marketing"),
        // iPhone
        IconSize(60, 2, "iphone"),
        IconSize(60, 3, "iphone"),
        // iPad
        IconSize(76, 1, "ipad"),
        IconSize(76, 2, "ipad"),
        IconSize(83, 2, "ipad"), // 83.5pt actually, but we use 167px
        // Settings
        IconSize(29, 2, "iphone"),
        IconSize(29, 3, "iphone"),
        IconSize(29, 1, "ipad"),
        IconSize(29, 2, "ipad"),
        // Notifications
        IconSize(20, 2, "iphone"),
        IconSize(20, 3, "iphone"),
        IconSize(20, 1, "ipad"),
        IconSize(20, 2, "ipad"),
        // Spotlight
        IconSize(40, 2, "iphone"),
        IconSize(40, 3, "iphone"),
        IconSize(40, 1, "ipad"),
        IconSize(40, 2, "ipad")
    )

    /**
     * Generate a complete Assets.xcassets directory with app icon.
     *
     * @param assetsDir The Assets.xcassets directory to create/populate
     * @param iconColor Hex color for the placeholder icon (e.g., "#007AFF")
     * @return The created Assets.xcassets directory
     */
    fun generate(
        assetsDir: File,
        iconColor: String = "#007AFF"
    ): File {
        assetsDir.mkdirs()

        // Create root Contents.json
        assetsDir.resolve("Contents.json").writeText("""
            {
              "info" : {
                "author" : "kbuild",
                "version" : 1
              }
            }
        """.trimIndent())

        // Generate app icon
        generateAppIcon(assetsDir, iconColor)

        return assetsDir
    }

    /**
     * Generate the AppIcon.appiconset with placeholder icons.
     *
     * @param assetsDir The Assets.xcassets directory
     * @param color Hex color for the icon background (e.g., "#007AFF")
     * @return The created AppIcon.appiconset directory
     */
    fun generateAppIcon(
        assetsDir: File,
        color: String = "#007AFF"
    ): File {
        val iconsetDir = assetsDir.resolve("AppIcon.appiconset")
        iconsetDir.mkdirs()

        val parsedColor = parseHexColor(color)

        // Generate icons at all required sizes
        val images = mutableListOf<Map<String, Any>>()

        for (iconSize in standardIconSizes) {
            val filename = iconSize.filename
            val imageFile = iconsetDir.resolve(filename)

            // Generate the icon image
            generateIcon(imageFile, iconSize.actualSize, parsedColor)

            // Add to manifest
            images.add(mapOf(
                "filename" to filename,
                "idiom" to iconSize.idiom,
                "scale" to "${iconSize.scale}x",
                "size" to "${iconSize.size}x${iconSize.size}"
            ))
        }

        // Write Contents.json
        val contentsJson = buildContentsJson(images)
        iconsetDir.resolve("Contents.json").writeText(contentsJson)

        return iconsetDir
    }

    /**
     * Generate a single icon image file.
     *
     * Creates a simple solid-color square icon. For production apps,
     * users should replace these with actual app icons.
     *
     * @param outputFile The output PNG file
     * @param size The icon size in pixels
     * @param color The background color
     */
    fun generateIcon(outputFile: File, size: Int, color: Color) {
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()

        try {
            // Fill with the specified color
            graphics.color = color
            graphics.fillRect(0, 0, size, size)

            // Add a simple "K" letter for kbuild branding (optional)
            if (size >= 60) {
                graphics.color = Color.WHITE
                val fontSize = (size * 0.6).toInt()
                graphics.font = graphics.font.deriveFont(fontSize.toFloat())
                val metrics = graphics.fontMetrics
                val text = "K"
                val x = (size - metrics.stringWidth(text)) / 2
                val y = (size - metrics.height) / 2 + metrics.ascent
                graphics.drawString(text, x, y)
            }
        } finally {
            graphics.dispose()
        }

        outputFile.parentFile?.mkdirs()
        ImageIO.write(image, "PNG", outputFile)
    }

    /**
     * Generate AccentColor.colorset for the app's accent color.
     *
     * @param assetsDir The Assets.xcassets directory
     * @param color Hex color (e.g., "#007AFF")
     * @return The created AccentColor.colorset directory
     */
    fun generateAccentColor(
        assetsDir: File,
        color: String = "#007AFF"
    ): File {
        val colorsetDir = assetsDir.resolve("AccentColor.colorset")
        colorsetDir.mkdirs()

        val parsedColor = parseHexColor(color)
        val r = parsedColor.red / 255.0
        val g = parsedColor.green / 255.0
        val b = parsedColor.blue / 255.0

        colorsetDir.resolve("Contents.json").writeText("""
            {
              "colors" : [
                {
                  "color" : {
                    "color-space" : "srgb",
                    "components" : {
                      "alpha" : "1.000",
                      "blue" : "${String.format("%.3f", b)}",
                      "green" : "${String.format("%.3f", g)}",
                      "red" : "${String.format("%.3f", r)}"
                    }
                  },
                  "idiom" : "universal"
                }
              ],
              "info" : {
                "author" : "kbuild",
                "version" : 1
              }
            }
        """.trimIndent())

        return colorsetDir
    }

    private fun buildContentsJson(images: List<Map<String, Any>>): String {
        val imagesJson = images.joinToString(",\n    ") { image ->
            val entries = image.entries.joinToString(", ") { (k, v) ->
                "\"$k\" : \"$v\""
            }
            "{ $entries }"
        }

        return """
            {
              "images" : [
                $imagesJson
              ],
              "info" : {
                "author" : "kbuild",
                "version" : 1
              }
            }
        """.trimIndent()
    }

    private fun parseHexColor(hex: String): Color {
        val cleanHex = hex.removePrefix("#")
        return when (cleanHex.length) {
            6 -> Color(
                Integer.parseInt(cleanHex.substring(0, 2), 16),
                Integer.parseInt(cleanHex.substring(2, 4), 16),
                Integer.parseInt(cleanHex.substring(4, 6), 16)
            )
            8 -> Color(
                Integer.parseInt(cleanHex.substring(0, 2), 16),
                Integer.parseInt(cleanHex.substring(2, 4), 16),
                Integer.parseInt(cleanHex.substring(4, 6), 16),
                Integer.parseInt(cleanHex.substring(6, 8), 16)
            )
            else -> Color(0, 122, 255) // Default iOS blue
        }
    }
}
