package com.dshm.bot.ui.chat

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dshm.bot.R
import com.dshm.bot.core.design.DshmTheme
import com.dshm.bot.domain.model.AvatarGlyph
import com.dshm.bot.ui.home.ChatRowUi
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 对话页顶栏：返回 / 详情按钮、Bot 名字、状态栏 inset 修复后的顶栏渲染。 */
@RunWith(AndroidJUnit4::class)
class BotChatPlaceholderScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val bot = ChatRowUi(
        id = "demo-office",
        name = "办公专家",
        preview = "Word 版做好了",
        timeLabel = "15:42",
        inkId = "blue",
        glyph = AvatarGlyph.WORK,
        pinned = false,
        isGroup = false,
        unreadCount = 0,
    )

    private fun setContent(onBack: () -> Unit = {}, onDetails: () -> Unit = {}) {
        composeRule.setContent {
            DshmTheme(darkTheme = false) {
                BotChatPlaceholderScreen(bot = bot, onBack = onBack, onDetails = onDetails)
            }
        }
    }

    @Test
    fun showsBotNameInTopBar() {
        setContent()
        composeRule.onNodeWithText("办公专家").assertIsDisplayed()
    }

    @Test
    fun backButtonCallsOnBack() {
        var backed = false
        setContent(onBack = { backed = true })
        composeRule.onNodeWithContentDescription(context.getString(R.string.action_back)).performClick()
        assertEquals(true, backed)
    }

    @Test
    fun detailsButtonCallsOnDetails() {
        var detailsOpened = false
        setContent(onDetails = { detailsOpened = true })
        composeRule.onNodeWithContentDescription(context.getString(R.string.chat_details)).performClick()
        assertEquals(true, detailsOpened)
    }
}