package com.dshm.bot

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** 界面图标统一用 Lucide（ISC），这里保证生成产物齐全且是可用的 VectorDrawable。 */
class LucideIconResourcesTest {

    private val drawableDir = File("src/main/res/drawable")

    private val expectedIcons = listOf(
        "user",
        "search",
        "plus",
        "briefcase",
        "bot",
        "book_open",
        "code",
        "users",
        "sparkles",
        "arrow_left",
        "chevron_right",
        "square_user",
    )

    @Test
    fun everyLucideIconIsGenerated() {
        expectedIcons.forEach { name ->
            val file = File(drawableDir, "ic_lucide_$name.xml")
            assertTrue("missing ${file.path}", file.isFile)
            val xml = file.readText()
            assertTrue("${file.path} is not a vector", xml.contains("<vector"))
            assertTrue("${file.path} has no path", xml.contains("android:pathData"))
            assertTrue("${file.path} should be stroked", xml.contains("android:strokeWidth=\"2\""))
        }
    }
}
