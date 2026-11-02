package com.dshm.bot

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 启动图标是产品交付物的一部分：尺寸、adaptive 描述、清单接线都要有测试兜底，
 * 避免换图标时只改了其中一层。
 *
 * Android 单元测试的 classpath 里没有 AWT / ImageIO，所以直接读 PNG 头（IHDR）。
 */
class AppIconResourcesTest {

    private val resDir = File("src/main/res")

    private data class PngHeader(val width: Int, val height: Int, val colorType: Int)

    @Test
    fun legacyLauncherIconsHaveExpectedSizes() {
        val expected = mapOf("mdpi" to 48, "hdpi" to 72, "xhdpi" to 96, "xxhdpi" to 144, "xxxhdpi" to 192)
        expected.forEach { (density, size) ->
            val file = File(resDir, "mipmap-$density/ic_launcher.png")
            assertTrue("missing ${file.path}", file.isFile)
            val header = pngHeader(file)
            assertEquals("$density width", size, header.width)
            assertEquals("$density height", size, header.height)
            assertEquals("$density RGBA", 6, header.colorType)
        }
    }

    @Test
    fun adaptiveIconDescriptorPointsAtGeneratedLayers() {
        val file = File(resDir, "mipmap-anydpi/ic_launcher.xml")
        assertTrue("missing ${file.path}", file.isFile)
        val xml = file.readText()
        assertTrue(xml.contains("<adaptive-icon"))
        assertTrue(xml.contains("@color/ic_launcher_background"))
        assertTrue(xml.contains("@mipmap/ic_launcher_foreground"))
        assertTrue(xml.contains("@mipmap/ic_launcher_monochrome"))
    }

    @Test
    fun adaptiveLayersExistForEveryDensity() {
        val expected = mapOf("mdpi" to 108, "hdpi" to 162, "xhdpi" to 216, "xxhdpi" to 324, "xxxhdpi" to 432)
        expected.forEach { (density, size) ->
            listOf("ic_launcher_foreground.png", "ic_launcher_monochrome.png").forEach { name ->
                val file = File(resDir, "mipmap-$density/$name")
                assertTrue("missing ${file.path}", file.isFile)
                val header = pngHeader(file)
                assertEquals(size, header.width)
                assertEquals(size, header.height)
                assertEquals(6, header.colorType)
            }
        }
    }

    @Test
    fun manifestWiresLabelAndLauncherIcon() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android:icon=\"@mipmap/ic_launcher\""))
        assertTrue(manifest.contains("android:label=\"@string/app_name\""))
        // 圆角由美术自带；不再声明会被系统二次裁切的 roundIcon。
        assertFalse(manifest.contains("android:roundIcon"))
    }

    @Test
    fun launcherBackgroundIsDeclared() {
        val colors = File(resDir, "values/colors.xml").readText()
        assertTrue(colors.contains("ic_launcher_background"))
    }

    private fun pngHeader(file: File): PngHeader {
        val bytes = file.readBytes()
        assertTrue("${file.path} is not a PNG", bytes.size > 33)
        val signature = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
        assertTrue("${file.path} has a bad PNG signature", bytes.copyOfRange(0, 8).contentEquals(signature))
        assertEquals("IHDR", String(bytes.copyOfRange(12, 16), Charsets.US_ASCII))
        val width = readInt(bytes, 16)
        val height = readInt(bytes, 20)
        val colorType = bytes[25].toInt() and 0xFF
        return PngHeader(width = width, height = height, colorType = colorType)
    }

    private fun readInt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)
}
