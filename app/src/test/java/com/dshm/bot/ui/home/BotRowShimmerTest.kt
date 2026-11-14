package com.dshm.bot.ui.home

import androidx.compose.ui.graphics.Color
import com.dshm.bot.ui.home.components.shimmerTintFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 行状态 → 名字扫光色：干活中主色、等你批准警告色，其余不扫。 */
class BotRowShimmerTest {

    private val accent = Color(0xFF4D6BFE)
    private val warning = Color(0xFFB26A00)

    @Test
    fun runningSweepsWithTheAccentColour() {
        assertEquals(accent, shimmerTintFor("running", accent, warning))
    }

    @Test
    fun waitingSweepsWithTheWarningColour() {
        assertEquals(warning, shimmerTintFor("waiting", accent, warning))
    }

    @Test
    fun idleRowsDoNotSweepAtAll() {
        assertNull(shimmerTintFor(null, accent, warning))
        assertNull(shimmerTintFor("idle", accent, warning))
        assertNull(shimmerTintFor("", accent, warning))
    }
}
