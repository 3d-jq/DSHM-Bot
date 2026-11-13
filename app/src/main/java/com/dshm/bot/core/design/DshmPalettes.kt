package com.dshm.bot.core.design

import androidx.compose.ui.graphics.Color
import com.dshm.bot.domain.model.AppPalette
import kotlin.math.pow

/**
 * 配色方案的色值表。
 *
 * 网页端把一套配色写成 8 个语义色（bg / sidebar / bubble / card / text /
 * user / userText / accent），本机令牌更多，这里按语义对齐：
 *
 * | 网页端 | 本机 |
 * |---|---|
 * | `bg` | `background` |
 * | `sidebar` | `pageBackground` |
 * | `bubble` | `bubbleAssistant` / `surfaceMuted` |
 * | `card` | `surface` / `floatingSurface` |
 * | `text` | `textPrimary`（次级/三级/分隔线按网页端的 ink-2 / ink-3 / line 派生） |
 * | `user` | `bubbleUser` |
 * | `userText` | `onBubbleUser` |
 * | `accent` | `accent`（可被团队偏好的强调色覆盖） |
 *
 * **`deepseek` 就是本机原生令牌那套**（`DshmColors.Light` / `DshmColors.Dark`
 * 的原值，一个色都不差）—— 团队没动过主题时，界面和现在完全一样。
 * 另外三套是网页端 `themes.js` 的原值，团队切过去时整 App 跟着换色。
 *
 * 配色方案本身（id）见 `domain/model/AppPalette`。
 */

/** 一套配色的语义色。 */
data class PaletteColors(
    val bg: Color,
    val sidebar: Color,
    val bubble: Color,
    val card: Color,
    val text: Color,
    val user: Color,
    val userText: Color,
    val accent: Color,
    /** 压在强调色上的文字色；默认按亮度算（网页端 `accentInk`）。 */
    val onAccent: Color = inkOn(accent),
    /**
     * 次级文字（网页端 `ink-2`）。
     *
     * **一律是不透明的实色**（deepseek 那套的写法）：网页端写的是
     * `rgba(ink, .6)`，压在颜色上面会透出底色 —— 同一个令牌两套写法会让
     * 同一个控件在不同主题下画得不一样（强调色圆块外面会多一圈白边）。
     * 这里统一烤成实色（见 [inkOver]）。
     */
    val textSecondary: Color = inkOver(card, text, 0.62f),
    /** 三级文字（网页端 `ink-3`）；同样是不透明实色。 */
    val textTertiary: Color = inkOver(card, text, 0.38f),
    /** 分隔线（网页端 `line`）；同样是不透明实色。 */
    val divider: Color = inkOver(card, text, 0.10f),
    /** 悬浮圆钮底色：深色下要比卡片亮一档，否则只剩一个图标。 */
    val floatingSurface: Color = card,
    /** 弱化底色（分段控件、设置分区的底）。 */
    val surfaceMuted: Color = bubble,
)

/**
 * 把 ink 按比例混到底色上，烤成一个**不透明**的实色。
 *
 * 网页端用 `rgba(ink, a)`（半透明），本机统一烤实：半透明令牌压在别的颜色上
 * 会透出底色，同一个控件在不同主题 / 不同底色上画出来不一样。
 */
private fun inkOver(base: Color, ink: Color, ratio: Float): Color = Color(
    red = base.red + (ink.red - base.red) * ratio,
    green = base.green + (ink.green - base.green) * ratio,
    blue = base.blue + (ink.blue - base.blue) * ratio,
    alpha = 1f,
)

object AppPalettes {

    /**
     * `deepseek` = 本机原生配色（不是网页端那张表）。
     *
     * 本机的令牌是照着 DS Bot 手机端调过的（用户气泡是亮蓝而不是网页端的近黑），
     * 默认就用它 —— 团队没动过主题时，界面和现在**一模一样**。
     * 另外三套是网页端 `themes.js` 的原值，团队切过去时整 App 跟着换色。
     */
    val DEEPSEEK_LIGHT = PaletteColors(
        bg = Color(0xFFFFFFFF),
        sidebar = Color(0xFFF1F2F4),
        bubble = Color(0xFFF2F3F5),
        card = Color(0xFFFFFFFF),
        text = Color(0xFF0B0B0F),
        user = Color(0xFF2F6BFF),
        userText = Color(0xFFFFFFFF),
        accent = Color(0xFF1F4FD8),
        onAccent = Color(0xFFFFFFFF),
        textSecondary = Color(0xFF8A8F98),
        textTertiary = Color(0xFFB3B8C0),
        divider = Color(0xFFECEEF1),
        floatingSurface = Color(0xFFFFFFFF),
        surfaceMuted = Color(0xFFF1F2F4),
    )

    val DEEPSEEK_DARK = PaletteColors(
        bg = Color(0xFF0E0F12),
        sidebar = Color(0xFF0E0F12),
        bubble = Color(0xFF1C1F25),
        card = Color(0xFF14161A),
        text = Color(0xFFF2F3F5),
        user = Color(0xFF2B4FD6),
        userText = Color(0xFFFFFFFF),
        accent = Color(0xFF6A84FF),
        onAccent = Color(0xFF0B0D12),
        textSecondary = Color(0xFF9AA0AA),
        textTertiary = Color(0xFF6C727C),
        divider = Color(0xFF24282F),
        floatingSurface = Color(0xFF262A33),
        surfaceMuted = Color(0xFF1C1F25),
    )

    val MONO_LIGHT = PaletteColors(
        bg = Color(0xFFFFFFFF),
        sidebar = Color(0xFFF9F9F9),
        bubble = Color(0xFFF0F0F0),
        card = Color(0xFFFFFFFF),
        text = Color(0xFF0D0D0D),
        user = Color(0xFF0D0D0D),
        userText = Color(0xFFFFFFFF),
        accent = Color(0xFF0D0D0D),
    )

    val MONO_DARK = PaletteColors(
        bg = Color(0xFF0F0F0F),
        sidebar = Color(0xFF171717),
        bubble = Color(0xFF242424),
        card = Color(0xFF1C1C1C),
        text = Color(0xFFF4F4F4),
        user = Color(0xFFF4F4F4),
        userText = Color(0xFF0F0F0F),
        accent = Color(0xFFF4F4F4),
        floatingSurface = Color(0xFF2A2A2A),
    )

    val PAPER_LIGHT = PaletteColors(
        bg = Color(0xFFFAF9F5),
        sidebar = Color(0xFFF3F0E8),
        bubble = Color(0xFFEBE7DC),
        card = Color(0xFFFFFFFF),
        text = Color(0xFF1F1E1B),
        user = Color(0xFF2B2A26),
        userText = Color(0xFFFAF9F5),
        accent = Color(0xFFD97757),
    )

    val PAPER_DARK = PaletteColors(
        bg = Color(0xFF1F1E1B),
        sidebar = Color(0xFF262521),
        bubble = Color(0xFF34322D),
        card = Color(0xFF2A2925),
        text = Color(0xFFF3F0E8),
        user = Color(0xFFF3F0E8),
        userText = Color(0xFF1F1E1B),
        accent = Color(0xFFE08A6D),
        floatingSurface = Color(0xFF3A3833),
    )

    val MOONLIGHT_LIGHT = PaletteColors(
        bg = Color(0xFFF7F9FC),
        sidebar = Color(0xFFEEF2F8),
        bubble = Color(0xFFE5EBF5),
        card = Color(0xFFFFFFFF),
        text = Color(0xFF121826),
        user = Color(0xFF1E3A8A),
        userText = Color(0xFFFFFFFF),
        accent = Color(0xFF1E86FF),
    )

    val MOONLIGHT_DARK = PaletteColors(
        bg = Color(0xFF0B1020),
        sidebar = Color(0xFF10172A),
        bubble = Color(0xFF1A2340),
        card = Color(0xFF141C33),
        text = Color(0xFFE8EDF7),
        user = Color(0xFF3B6FD8),
        userText = Color(0xFFFFFFFF),
        accent = Color(0xFF4F9DFF),
        floatingSurface = Color(0xFF232C47),
    )

    /** 某套配色在明 / 暗下的色值。 */
    fun of(palette: AppPalette, dark: Boolean): PaletteColors = when (palette) {
        AppPalette.DEEPSEEK -> if (dark) DEEPSEEK_DARK else DEEPSEEK_LIGHT
        AppPalette.MONO -> if (dark) MONO_DARK else MONO_LIGHT
        AppPalette.PAPER -> if (dark) PAPER_DARK else PAPER_LIGHT
        AppPalette.MOONLIGHT -> if (dark) MOONLIGHT_DARK else MOONLIGHT_LIGHT
    }
}

/**
 * 网页端 `accentInk`：按强调色亮度决定压在它上面的文字用深墨还是白。
 *
 * 网页端用的是「相对亮度」的近似公式，这里照搬（`luminance` 取 sRGB 线性化）。
 */
fun inkOn(accent: Color): Color {
    val l = accent.luminance()
    val darkInk = 0x14 / 255.0
    return if ((l + 0.05).pow(2) > 1.05 * (darkInk + 0.05)) {
        Color(0xFF141414)
    } else {
        Color(0xFFFFFFFF)
    }
}

/** sRGB 相对亮度（网页端 `luminance` 的同款近似）。 */
private fun Color.luminance(): Double {
    fun channel(value: Float): Double {
        val v = value.toDouble()
        return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }
    return 0.2126 * channel(red) + 0.7152 * channel(green) + 0.0722 * channel(blue)
}

/**
 * 把配色套到本机令牌上。
 *
 * 团队偏好的强调色（`prefs.accent`）覆盖配色自带的 accent —— 网页端也是
 * 这个优先级（`themeCss(theme, accent)`）。
 *
 * **不动**的令牌：图标色和三档阴影。它们是「遮蔽」不是「墨色」，跟着文字色
 * 派生的话深色主题下会变成发亮的雾（原来都是黑的）。
 */
fun DshmColors.withPalette(palette: AppPalette, accent: Color? = null): DshmColors {
    val set = AppPalettes.of(palette, isDark)
    val accentColor = accent ?: set.accent
    return copy(
        background = set.bg,
        pageBackground = set.sidebar,
        surface = set.card,
        surfaceMuted = set.surfaceMuted,
        floatingSurface = set.floatingSurface,
        textPrimary = set.text,
        textSecondary = set.textSecondary,
        textTertiary = set.textTertiary,
        divider = set.divider,
        bubbleAssistant = set.bubble,
        bubbleUser = set.user,
        onBubbleUser = set.userText,
        accent = accentColor,
        // 团队指定了强调色才重算墨色；没指定就用配色自带的（原生那套的深墨
        // 是 #0B0D12，不是按亮度算出来的 #141414）。
        onAccent = if (accent == null) set.onAccent else inkOn(accentColor),
    )
}

/** 强调色字符串（`#rrggbb`）→ 颜色；不合法时返回 null。 */
fun parseAccent(raw: String?): Color? {
    val text = raw?.trim().orEmpty()
    if (!text.matches(Regex("#[0-9a-fA-F]{6}"))) return null
    val value = text.substring(1).toLong(16)
    return Color(
        red = ((value shr 16) and 0xFF) / 255f,
        green = ((value shr 8) and 0xFF) / 255f,
        blue = (value and 0xFF) / 255f,
        alpha = 1f,
    )
}
