package com.dshm.bot.core.design

import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 触感档位 → 平台类型的映射护栏。
 *
 * 映射写错不会编译失败，只会「手感走样」（例如拒绝震得比同意还重），
 * 真机上很难发现，所以在这里锁住。
 */
class HapticsTest {

    @Test
    fun eachLevelMapsToADistinctEffect() {
        // 三档必须三种不同手感：复制粘贴写错会让两档变成同一个。
        val effects = HapticLevel.entries.map { hapticEffectFor(it) }
        assertEquals("每个档位都要有映射", HapticLevel.entries.size, effects.size)
        assertEquals("三个档位不能映射到同一个效果", effects.size, effects.toSet().size)
        assertNotEquals(effects[0], effects[1])
        assertNotEquals(effects[1], effects[2])
        assertNotEquals(effects[0], effects[2])
    }

    @Test
    fun theTiersKeepTheirAgreedMeanings() {
        // 约定的语义（2026-11-16）：LIGHT=短促键按（发送/复制/选择）、
        // MEDIUM=锐点击（停止/审批同意）、HEAVY=最重（留给危险操作）。
        //
        // ⚠️ 必须是这三个：它们分别映射到 View.HapticFeedbackConstants 的
        // VIRTUAL_KEY(API 3) / CONTEXT_CLICK(API 23) / LONG_PRESS(API 1)。
        // 换 Confirm/Reject/Toggle 之类（API 30 才有）会在 Android 8/9/10 上静默失效，
        // 而我们的 minSdk 是 26 —— 这条断言就是那道防线。
        assertEquals(HapticFeedbackType.VirtualKey, hapticEffectFor(HapticLevel.LIGHT))
        assertEquals(HapticFeedbackType.ContextClick, hapticEffectFor(HapticLevel.MEDIUM))
        assertEquals(HapticFeedbackType.LongPress, hapticEffectFor(HapticLevel.HEAVY))
    }
}