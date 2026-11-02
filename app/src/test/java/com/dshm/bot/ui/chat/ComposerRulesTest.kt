package com.dshm.bot.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposerRulesTest {

    @Test
    fun blankTextCannotBeSent() {
        assertFalse(ComposerRules.canSend(""))
        assertFalse(ComposerRules.canSend("   "))
        assertFalse(ComposerRules.canSend("\n\t"))
    }

    @Test
    fun nonBlankTextCanBeSent() {
        assertTrue(ComposerRules.canSend("你好"))
        assertTrue(ComposerRules.canSend("  a  "))
    }
}
