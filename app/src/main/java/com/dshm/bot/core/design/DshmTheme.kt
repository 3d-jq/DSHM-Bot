package com.dshm.bot.core.design

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember

/**
 * 应用主题：把设计令牌注入 Compose，并同步 Material3 的基础色板。
 * 界面代码统一通过 [Dshm] 取令牌。
 */
@Composable
fun DshmTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DshmColors.Dark else DshmColors.Light
    val spacing = remember { DshmSpacing() }
    val radius = remember { DshmRadius() }
    val typography = remember { DshmTypography() }
    val materialColors = remember(colors) { colors.toMaterialColorScheme() }

    CompositionLocalProvider(
        LocalDshmColors provides colors,
        LocalDshmSpacing provides spacing,
        LocalDshmRadius provides radius,
        LocalDshmTypography provides typography,
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
}
