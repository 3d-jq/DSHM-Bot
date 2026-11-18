package com.dshm.bot.ui.settings

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 品牌色底上的墨色选择（[readableInkOn]）。
 *
 * 连接器图标用**人家的品牌色**（用户 2026-11-18：「保持人家原样子」），
 * 所以图形本身的颜色要按底色算：Miro 的黄、Hugging Face 的黄必须配近黑墨，
 * Slack 的深紫、Notion 的黑必须配白 —— 否则图形糊在底上看不见。
 */
class ConnectorBrandInkTest {

    private fun hex(value: Long) = Color(value)

    @Test
    fun brightBrandsGetDarkInkAndDarkBrandsGetWhite() {
        // 亮底 → 深墨（对比度要够 WCAG AA 的 4.5）。
        for (bright in listOf(0xFFFFD02F, 0xFFFFD21E, 0xFF6AFDEF, 0xFF00E599)) {
            val ink = readableInkOn(hex(bright))
            assertTrue(
                "亮品牌色 $bright 上用了浅墨，对比度 ${contrastRatio(hex(bright), ink)}",
                contrastRatio(hex(bright), ink) >= 4.5f,
            )
        }
        // 深底 → 白。
        for (dark in listOf(0xFF000000, 0xFF4A154B, 0xFF003087, 0xFF181717)) {
            val ink = readableInkOn(hex(dark))
            assertEquals("深品牌色 $dark 上应该用白", Color.White, ink)
            assertTrue(contrastRatio(hex(dark), ink) >= 4.5f)
        }
    }

    @Test
    fun midTonesPickWhicheverReadsBetter() {
        // 中间调（品牌紫、品牌橙）不锁死颜色，只锁「能看清」。
        for (brand in listOf(0xFF5E6AD2, 0xFFFF6C37, 0xFFF38020, 0xFFF24E1E)) {
            val ink = readableInkOn(hex(brand))
            val chosen = contrastRatio(hex(brand), ink)
            val other = contrastRatio(hex(brand), if (ink == Color.White) hex(0xFF101418) else Color.White)
            assertTrue("$brand 的墨色不是对比度更高的那个（$chosen < $other）", chosen >= other)
        }
    }

    @Test
    fun everyBrandInTheTableHasAReadableMark() {
        assertTrue("品牌表不能是空的", CONNECTOR_BRANDS.isNotEmpty())
        CONNECTOR_BRANDS.forEach { (id, brand) ->
            val tile = Color(brand.color)
            val contrast = contrastRatio(tile, readableInkOn(tile))
            assertTrue("$id 的品牌色 #${brand.color.toString(16)} 上图形看不清（对比度 $contrast）", contrast >= 3f)
        }
    }
}
