package com.dshm.bot.ui.settings

import com.dshm.bot.core.design.DshmColors
import org.junit.Assert.assertEquals
import org.junit.Test

/** 次级按钮取色规则：白底用灰、非白底（灰/深色）用白。 */
class SecondaryButtonColorTest {

    private val light = DshmColors.Light
    private val dark = DshmColors.Dark

    @Test
    fun onAWhiteContainerTheSecondaryButtonIsGrey() {
        assertEquals(light.surfaceMuted, secondaryContainerFor(light.surface, light))
    }

    @Test
    fun onAGreyPageTheSecondaryButtonIsWhite() {
        assertEquals(light.floatingSurface, secondaryContainerFor(light.pageBackground, light))
    }

    @Test
    fun darkThemeUsesTheElevatedSurfaceOnADarkPage() {
        // 深色主题没有「白」，规则退化成「亮一档的面」，仍要和页面底区分开。
        assertEquals(dark.floatingSurface, secondaryContainerFor(dark.pageBackground, dark))
    }
}
