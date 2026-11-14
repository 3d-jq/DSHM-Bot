package com.dshm.bot.core.design

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * 语义化颜色令牌。界面代码只允许引用这里的语义名，不允许出现颜色字面量。
 *
 * 品牌色取自 DS Bot 的 DeepSeek 蓝（插件端 `ds-bot/src/client/inks.js` 同一色系）；
 * 后续接入 DSH 后如果需要跟随主机主题，只改这里即可。
 */
@Immutable
data class DshmColors(
    val isDark: Boolean,
    val background: Color,
    /** 页面底色：浅色主题用浅灰，深色主题用深底；设置/主界面共用。 */
    val pageBackground: Color,
    val surface: Color,
    val surfaceMuted: Color,
    /**
     * 悬浮圆钮（顶栏那些）的填充色。
     *
     * 浅色主题跟 `surface` 一样是白的；深色主题要比 `background`/`surface` 都亮一档，
     * 否则深底上的深圆钮只剩一个图标，看不出按钮形状。
     */
    val floatingSurface: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val divider: Color,
    val accent: Color,
    val onAccent: Color,
    /** 对话里 AI 气泡（浅灰，和页面白底区分）。 */
    val bubbleAssistant: Color,
    /** 用户气泡：跟强调色走（同一个主色；见 `DshmPalettes.withPalette`）。 */
    val bubbleUser: Color,
    /** 用户气泡上的文字 / 图标：按强调色亮度算墨色（亮底白墨 / 近白底深墨）。 */
    val onBubbleUser: Color,
    val onInk: Color,
    val iconTint: Color,
    val badge: Color,
    val scrim: Color,
    val shadow: Color,
    /** 柔和外投影（玻璃件，dsh-Go chrome 玻璃的 8% 阴影）。 */
    val shadowSoft: Color,
    /** 卡片悬浮投影（设置卡等大块面板，比 soft 明显一些）。 */
    val shadowCard: Color,
    /** 错误/危险提示文字。 */
    val danger: Color,
    /** 运行中/成功状态点。 */
    val success: Color,
    /** 启动中/安装中等进行中状态点。 */
    val warning: Color,
    /** 终端/日志井底色（明暗主题都保持深色）。 */
    val terminalBackground: Color,
    /** 终端文字。 */
    val terminalText: Color,
    /** GitHub 品牌色（图标用，明暗主题同值——品牌色不随主题变）。 */
    val githubBrand: Color,
) {
    companion object {
        val Light = DshmColors(
            isDark = false,
            background = Color(0xFFFFFFFF),
            pageBackground = Color(0xFFF1F2F4),
            surface = Color(0xFFFFFFFF),
            surfaceMuted = Color(0xFFF1F2F4),
            floatingSurface = Color(0xFFFFFFFF),
            textPrimary = Color(0xFF0B0B0F),
            textSecondary = Color(0xFF8A8F98),
            textTertiary = Color(0xFFB3B8C0),
            divider = Color(0xFFECEEF1),
            // 主色统一成强调色第一个色板的蓝（DS Bot 品牌蓝）：
            // 按钮 / 链接 / 用户气泡都是它 —— 用户气泡跟强调色走（DshmPalettes）。
            accent = Color(0xFF4D6BFE),
            onAccent = Color(0xFFFFFFFF),
            bubbleAssistant = Color(0xFFF2F3F5),
            bubbleUser = Color(0xFF4D6BFE),
            onBubbleUser = Color(0xFFFFFFFF),
            onInk = Color(0xFFFFFFFF),
            iconTint = Color(0xFF111318),
            badge = Color(0xFFFF3E51),
            scrim = Color(0x66000000),
            shadow = Color(0x8F000000),
            shadowSoft = Color(0x14000000),
            shadowCard = Color(0x40000000),
            danger = Color(0xFFD93025),
            success = Color(0xFF2E7D32),
            warning = Color(0xFFB26A00),
            terminalBackground = Color(0xFF15161A),
            terminalText = Color(0xFFE8E8EA),
            githubBrand = Color(0xFF181717),
        )

        val Dark = DshmColors(
            isDark = true,
            background = Color(0xFF0E0F12),
            pageBackground = Color(0xFF0E0F12),
            surface = Color(0xFF14161A),
            surfaceMuted = Color(0xFF1C1F25),
            floatingSurface = Color(0xFF262A33),
            textPrimary = Color(0xFFF2F3F5),
            textSecondary = Color(0xFF9AA0AA),
            textTertiary = Color(0xFF6C727C),
            divider = Color(0xFF24282F),
            accent = Color(0xFF6A84FF),
            onAccent = Color(0xFF0B0D12),
            bubbleAssistant = Color(0xFF1C1F25),
            // 深色主题用亮一档的蓝（跟 accent 同一个色）：气泡跟强调色走。
            bubbleUser = Color(0xFF6A84FF),
            onBubbleUser = Color(0xFFFFFFFF),
            onInk = Color(0xFFFFFFFF),
            iconTint = Color(0xFFF2F3F5),
            badge = Color(0xFFFF5A7A),
            scrim = Color(0x99000000),
            shadow = Color(0xE6000000),
            shadowSoft = Color(0x59000000),
            shadowCard = Color(0xA6000000),
            danger = Color(0xFFFF6B60),
            success = Color(0xFF6FD07A),
            warning = Color(0xFFFFC46B),
            terminalBackground = Color(0xFF0B0C0E),
            terminalText = Color(0xFFE8E8EA),
            githubBrand = Color(0xFF181717),
        )
    }
}

val LocalDshmColors = staticCompositionLocalOf { DshmColors.Light }



