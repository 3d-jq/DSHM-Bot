package com.dshm.bot.core.design

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dshm.bot.data.settings.BotPrefsStore
import com.dshm.bot.domain.model.AppPalette

/**
 * 应用主题：把设计令牌注入 Compose，并同步 Material3 的基础色板。
 * 界面代码统一通过 [Dshm] 取令牌。
 *
 * 团队偏好（`prefs`）在这里生效：配色方案、强调色、动效档位。
 * 明暗仍由本机「设置 → 外观」决定（那是设备属性），其余跟团队走。
 */
@Composable
fun DshmTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val prefs by BotPrefsStore.prefs.collectAsStateWithLifecycle()
    val colors = remember(darkTheme, prefs.theme, prefs.accent) {
        (if (darkTheme) DshmColors.Dark else DshmColors.Light)
            .withPalette(AppPalette.fromId(prefs.theme), parseAccent(prefs.accent))
    }
    val spacing = remember { DshmSpacing() }
    val radius = remember { DshmRadius() }
    val typography = remember { DshmTypography() }
    val motion = remember(prefs.motion) { MotionProfile.of(prefs.motion) }
    val materialColors = remember(colors) { colors.toMaterialColorScheme() }

    CompositionLocalProvider(
        LocalDshmColors provides colors,
        LocalDshmSpacing provides spacing,
        LocalDshmRadius provides radius,
        LocalDshmTypography provides typography,
        LocalMotionProfile provides motion,
    ) {
        MaterialTheme(colorScheme = materialColors, content = content)
    }
}

private fun DshmColors.toMaterialColorScheme() = if (isDark) {
    darkColorScheme(
        primary = accent,
        onPrimary = onAccent,
        background = background,
        onBackground = textPrimary,
        surface = surface,
        onSurface = textPrimary,
        surfaceVariant = surfaceMuted,
        onSurfaceVariant = textSecondary,
        outline = divider,
        error = badge,
    )
} else {
    lightColorScheme(
        primary = accent,
        onPrimary = onAccent,
        background = background,
        onBackground = textPrimary,
        surface = surface,
        onSurface = textPrimary,
        surfaceVariant = surfaceMuted,
        onSurfaceVariant = textSecondary,
        outline = divider,
        error = badge,
    )
}

/** 令牌入口：`Dshm.colors`、`Dshm.spacing`、`Dshm.radius`、`Dshm.typography`。 */
object Dshm {
    val colors: DshmColors
        @Composable @ReadOnlyComposable get() = LocalDshmColors.current

    val spacing: DshmSpacing
        @Composable @ReadOnlyComposable get() = LocalDshmSpacing.current

    val radius: DshmRadius
        @Composable @ReadOnlyComposable get() = LocalDshmRadius.current

    val typography: DshmTypography
        @Composable @ReadOnlyComposable get() = LocalDshmTypography.current

    /** 当前动效档位（团队偏好 `prefs.motion`）。 */
    val motion: MotionProfile
        @Composable @ReadOnlyComposable get() = LocalMotionProfile.current
}
