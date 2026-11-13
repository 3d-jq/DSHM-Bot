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

    /**
     * **回归锁**：`deepseek` 这套配色就是本机原生令牌，一个色都不许差。
     *
     * 团队没动过主题时，界面必须和接线之前**完全一样** —— 少一个色都不行。
     */
    @Test
    fun deepseekPaletteIsExactlyTheNativeTokens() {
        assertEquals(DshmColors.Light, DshmColors.Light.withPalette(AppPalette.DEEPSEEK))
        assertEquals(DshmColors.Dark, DshmColors.Dark.withPalette(AppPalette.DEEPSEEK))
    }

    /** 换成别的配色时，阴影和图标色**不能**跟着文字色变（深色下会变成发亮的雾）。 */
    @Test
    fun shadowsAndIconTintStayNative() {
        val base = DshmColors.Dark
        val moonlight = base.withPalette(AppPalette.MOONLIGHT)
        assertEquals(base.shadowSoft, moonlight.shadowSoft)
        assertEquals(base.shadowCard, moonlight.shadowCard)
        assertEquals(base.shadow, moonlight.shadow)
        assertEquals(base.iconTint, moonlight.iconTint)
    }

    /**
     * **回归锁**：所有配色的墨色令牌（次级文字 / 三级文字 / 分隔线）都必须是
     * **不透明实色**。
     *
     * 网页端写的是 `rgba(ink, a)`（半透明），本机统一烤实（deepseek 那套的写法）：
     * 半透明令牌压在别的颜色上会透出底色 —— 同一个控件（强调色圆块的外圈）在
     * 不同主题下就会画出白边 / 暗边，切主题时圆环大小观感跟着跳。
     */
    @Test
    fun everyPaletteBakesItsInkAsOpaque() {
        for (palette in AppPalette.entries) {
            for (dark in listOf(false, true)) {
                val set = AppPalettes.of(palette, dark)
                assertEquals("$palette dark=$dark textSecondary", 1f, set.textSecondary.alpha, 0.001f)
                assertEquals("$palette dark=$dark textTertiary", 1f, set.textTertiary.alpha, 0.001f)
                assertEquals("$palette dark=$dark divider", 1f, set.divider.alpha, 0.001f)
            }
        }
    }

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
