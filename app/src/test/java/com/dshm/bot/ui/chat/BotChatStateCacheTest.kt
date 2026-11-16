package com.dshm.bot.ui.chat

import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * 进程级对话缓存的边界：只留最近几个 bot、换引擎时能清掉。
 *
 * 它是「重进对话不闪骨架屏」的载体（见 [BotChatStateCache] 的注释），
 * 存的是不可变 UiState，所以这里不需要关心内容本身，只锁「留谁 / 清空」。
 */
class BotChatStateCacheTest {

    @Before
    fun setUp() = BotChatStateCache.clear()

    @After
    fun tearDown() = BotChatStateCache.clear()

    @Test
    fun keepsOnlyTheMostRecentlyUsedBots() {
        repeat(BotChatStateCache.MAX_BOTS + 2) { index ->
            BotChatStateCache.put("bot-$index", BotChatUiState(loading = false))
        }
        // 最早的两个被挤掉，最新的还在。
        assertNull(BotChatStateCache.get("bot-0"))
        assertNull(BotChatStateCache.get("bot-1"))
        assertNotNull(BotChatStateCache.get("bot-${BotChatStateCache.MAX_BOTS + 1}"))
    }

    @Test
    fun clearDropsEverythingSoStaleEnginesNeverPaint() {
        BotChatStateCache.put("bot-1", BotChatUiState(loading = false))
        BotChatStateCache.clear()
        assertNull(BotChatStateCache.get("bot-1"))
    }
}
