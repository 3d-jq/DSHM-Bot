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
    val surface: Color,
    val surfaceMuted: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val divider: Color,
    val accent: Color,
    val onAccent: Color,
    val onInk: Color,
    val iconTint: Color,
    val badge: Color,
    val scrim: Color,
    val shadow: Color,
    /** 柔和外投影（玻璃件，dsh-Go chrome 玻璃的 8% 阴影）。 */
    val shadowSoft: Color,
    /** 卡片悬浮投影（设置卡等大块面板，比 soft 明显一些）。 */
    val shadowCard: Color,
) {
    companion object {
        val Light = DshmColors(
            isDark = false,
            background = Color(0xFFFFFFFF),
            surface = Color(0xFFFFFFFF),
            surfaceMuted = Color(0xFFF1F2F4),
            textPrimary = Color(0xFF0B0B0F),
            textSecondary = Color(0xFF8A8F98),
            textTertiary = Color(0xFFB3B8C0),
            divider = Color(0xFFECEEF1),
            accent = Color(0xFF1F4FD8),
            onAccent = Color(0xFFFFFFFF),
            onInk = Color(0xFFFFFFFF),
            iconTint = Color(0xFF111318),
            badge = Color(0xFFFF3E51),
            scrim = Color(0x66000000),
            shadow = Color(0x8F000000),
            shadowSoft = Color(0x14000000),
            shadowCard = Color(0x40000000),
        )

        val Dark = DshmColors(
            isDark = true,
            background = Color(0xFF0E0F12),
            surface = Color(0xFF14161A),
            surfaceMuted = Color(0xFF1C1F25),
            textPrimary = Color(0xFFF2F3F5),
            textSecondary = Color(0xFF9AA0AA),
            textTertiary = Color(0xFF6C727C),
            divider = Color(0xFF24282F),
            accent = Color(0xFF6A84FF),
            onAccent = Color(0xFF0B0D12),
            onInk = Color(0xFFFFFFFF),
            iconTint = Color(0xFFF2F3F5),
            badge = Color(0xFFFF5A7A),
            scrim = Color(0x99000000),
            shadow = Color(0xE6000000),
            shadowSoft = Color(0x59000000),
            shadowCard = Color(0xA6000000),
        )
    }
}

val LocalDshmColors = staticCompositionLocalOf { DshmColors.Light }
