package com.dshm.bot.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/** 停止键和麦克风不能因为瞬时状态刷新互相抢位置。 */
class ComposerActionTest {

    @Test
    fun stopVisibleWinsOverVoiceWhileTurnIsRunning() {
        assertEquals(
            ComposerTrailingAction.STOP_RUN,
            composerTrailingAction(
                listening = false,
                transcribing = false,
                stopVisible = true,
                draft = "",
                voiceAvailable = true,
            ),
        )
    }

    @Test
    fun dictationStillWinsOverStopRun() {
        assertEquals(
            ComposerTrailingAction.STOP_DICTATION,
            composerTrailingAction(
                listening = true,
                transcribing = false,
                stopVisible = true,
                draft = "",
                voiceAvailable = true,
            ),
        )
    }

    @Test
    fun voiceReturnsOnlyAfterStopIsHidden() {
        assertEquals(
            ComposerTrailingAction.VOICE,
            composerTrailingAction(
                listening = false,
                transcribing = false,
                stopVisible = false,
                draft = "",
                voiceAvailable = true,
            ),
        )
    }

    @Test
    fun typedDraftShowsSendWhenStopIsHidden() {
        assertEquals(
            ComposerTrailingAction.SEND,
            composerTrailingAction(
                listening = false,
                transcribing = false,
                stopVisible = false,
                draft = "在吗",
                voiceAvailable = true,
            ),
        )
    }
}
