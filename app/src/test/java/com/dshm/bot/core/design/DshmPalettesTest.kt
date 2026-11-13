package com.dshm.bot.core.design

import androidx.compose.ui.graphics.Color
import com.dshm.bot.domain.model.AppPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 团队偏好 → 本机令牌的映射。
 *
 * 网页端同一套语义在 `client.js` 的 `themes.js`（4 套配色）和 `themeVars`
 * （强调色覆盖 + `accentInk` 选墨色）。
 */
class DshmPalettesTest {

    @Test
    fun paletteIdFallsBackToDeepseek() {
        assertEquals(AppPalette.DEEPSEEK, AppPalette.fromId(null))
        assertEquals(AppPalette.DEEPSEEK, AppPalette.fromId(""))
        assertEquals(AppPalette.DEEPSEEK, AppPalette.fromId("nope"))
        assertEquals(AppPalette.PAPER, AppPalette.fromId("paper"))
        assertEquals(AppPalette.MOONLIGHT, AppPalette.fromId("moonlight"))
        assertEquals(AppPalette.MONO, AppPalette.fromId("mono"))
    }

    /** 切配色要真的换掉背景、卡片和气泡的底色。 */
    @Test
    fun paletteChangesTheWholeSurface() {
        val base = DshmColors.Light
        val paper = base.withPalette(AppPalette.PAPER)
        assertEquals(Color(0xFFFAF9F5), paper.background)
        assertEquals(Color(0xFFF3F0E8), paper.pageBackground)
        assertEquals(Color(0xFFEBE7DC), paper.bubbleAssistant)
        assertNotEquals(base.background, paper.background)
        assertNotEquals(base.bubbleAssistant, paper.bubbleAssistant)
    }

    /** 配色自带一个强调色，团队没指定强调色时就用它。 */
    @Test
    fun paletteCarriesItsOwnAccent() {
        val paper = DshmColors.Light.withPalette(AppPalette.PAPER)
        assertEquals(Color(0xFFD97757), paper.accent)
        val moonlight = DshmColors.Dark.withPalette(AppPalette.MOONLIGHT)
        assertEquals(Color(0xFF4F9DFF), moonlight.accent)
    }

    /** 团队指定的强调色盖过配色自带的（网页端 `themeCss(theme, accent)` 同款优先级）。 */
    @Test
    fun teamAccentOverridesPaletteAccent() {
        val colors = DshmColors.Light.withPalette(AppPalette.PAPER, Color(0xFF4D6BFE))
        assertEquals(Color(0xFF4D6BFE), colors.accent)
    }

    /** 亮色上压深墨，暗色上压白（网页端 `accentInk`）。 */
    @Test
    fun inkOnAccentFollowsLuminance() {
        assertEquals(Color(0xFFFFFFFF), inkOn(Color(0xFF4D6BFE)))
        assertEquals(Color(0xFF141414), inkOn(Color(0xFFE8A33C)))
    }

    /** 强调色字符串要认 `#rrggbb`，其它一律不认（宁可跟主题走）。 */
    @Test
    fun parseAccentAcceptsOnlyHexColors() {
        assertEquals(Color(0xFF4D6BFE), parseAccent("#4D6BFE"))
        assertEquals(Color(0xFF4D6BFE), parseAccent("#4d6bfe"))
        assertNull(parseAccent(null))
        assertNull(parseAccent(""))
        assertNull(parseAccent("blue"))
        assertNull(parseAccent("#FFF"))
    }

    /** 深色主题下换配色也要换（网页端每套配色都有明暗两份）。 */
    @Test
    fun darkPaletteDiffersFromLight() {
        val light = AppPalettes.of(AppPalette.MOONLIGHT, dark = false)
        val dark = AppPalettes.of(AppPalette.MOONLIGHT, dark = true)
        assertNotEquals(light.bg, dark.bg)
        assertTrue(dark.bg.luminanceIsLowerThan(light.bg))
    }

    private fun Color.luminanceIsLowerThan(other: Color): Boolean =
        red + green + blue < other.red + other.green + other.blue
}
