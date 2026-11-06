package com.dshm.bot.core.design

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DesignTokensTest {

    @Test
    fun spacingScaleIsPositiveAndAscending() {
        val spacing = DshmSpacing()
        val ordered = listOf(
            spacing.xxs.value,
            spacing.xs.value,
            spacing.sm.value,
            spacing.md.value,
            spacing.lg.value,
            spacing.xl.value,
            spacing.xxl.value,
            spacing.xxxl.value,
        )
        assertTrue(ordered.all { it > 0f })
        assertEquals(ordered.sorted(), ordered)
    }

    @Test
    fun rowAndAvatarUseCoherentSizes() {
        val spacing = DshmSpacing()
        assertTrue(spacing.avatar.value > spacing.icon.value)
        assertTrue(spacing.rowMinHeight.value >= spacing.avatar.value)
        assertTrue(spacing.iconButton.value >= spacing.icon.value)
    }

    @Test
    fun radiiArePositiveAndOrdered() {
        val radius = DshmRadius()
        val ordered = listOf(radius.xs.value, radius.sm.value, radius.md.value, radius.lg.value, radius.xl.value)
        assertTrue(ordered.all { it > 0f })
        assertEquals(ordered.sorted(), ordered)
        assertTrue(radius.pill.value > radius.xl.value)
    }

    @Test
    fun lightTextHasReadableContrast() {
        val colors = DshmColors.Light
        assertTrue(contrast(colors.textPrimary, colors.background) >= 7.0)
        assertTrue(contrast(colors.textPrimary, colors.surface) >= 7.0)
        assertTrue(contrast(colors.textSecondary, colors.background) >= 3.0)
    }

    @Test
    fun darkTextHasReadableContrast() {
        val colors = DshmColors.Dark
        assertTrue(contrast(colors.textPrimary, colors.background) >= 7.0)
        assertTrue(contrast(colors.textSecondary, colors.background) >= 3.0)
    }

    @Test
    fun semanticColorsAreOpaqueWhereRequired() {
        listOf(DshmColors.Light, DshmColors.Dark).forEach { colors ->
            assertEquals(1f, colors.background.alpha)
            assertEquals(1f, colors.surface.alpha)
            assertEquals(1f, colors.surfaceMuted.alpha)
            assertEquals(1f, colors.textPrimary.alpha)
            assertEquals(1f, colors.accent.alpha)
        }
    }

    @Test
    fun scrimAndShadowAreTranslucent() {
        listOf(DshmColors.Light, DshmColors.Dark).forEach { colors ->
            assertTrue(colors.scrim.alpha < 1f)
            assertTrue(colors.shadow.alpha < 1f)
        }
    }

    @Test
    fun floatingButtonsDifferFromPageBackground() {
        listOf(DshmColors.Light, DshmColors.Dark).forEach { colors ->
            assertTrue(colors.surfaceMuted != colors.background)
            assertTrue(colors.iconTint != colors.surfaceMuted)
        }
    }

    private fun contrast(foreground: Color, background: Color): Double {
        val first = luminance(foreground)
        val second = luminance(background)
        val lighter = maxOf(first, second)
        val darker = minOf(first, second)
        return (lighter + 0.05) / (darker + 0.05)
    }

    private fun luminance(color: Color): Double {
        fun channel(value: Float): Double {
            val v = value.toDouble()
            return if (v <= 0.03928) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
    }
}
