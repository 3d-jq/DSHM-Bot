package com.dshm.bot.ui.chat

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/** 最新气泡只给悬浮输入框留一次空间，不能再叠第二份。 */
class ChatListInsetsTest {

    @Test
    fun bottomPaddingReservesFloatingComposerExactlyOnce() {
        assertEquals(
            84.dp,
            chatListBottomPadding(
                composerHeight = 56.dp,
                composerBottomPadding = 20.dp,
                composerClearance = 8.dp,
            ),
        )
    }

    @Test
    fun bottomPaddingGrowsOnlyWithMeasuredComposerOrClearance() {
        val padding = chatListBottomPadding(70.dp, 16.dp, 8.dp)
        assertEquals(94.dp, padding)
        assertEquals(padding + 8.dp, chatListBottomPadding(70.dp, 16.dp, 16.dp))
    }
}
