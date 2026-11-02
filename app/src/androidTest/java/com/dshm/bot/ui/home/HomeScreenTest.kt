package com.dshm.bot.ui.home

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dshm.bot.R
import com.dshm.bot.core.design.DshmTheme
import com.dshm.bot.domain.model.AvatarGlyph
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 主界面 UI 测试：渲染、点击进入对话、搜索入口、空态/错误态。 */
@RunWith(AndroidJUnit4::class)
class HomeScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun row(id: String, name: String, preview: String, time: String) = ChatRowUi(
        id = id,
        name = name,
        preview = preview,
        timeLabel = time,
        inkId = null,
        glyph = AvatarGlyph.BOT,
        pinned = false,
        isGroup = false,
        unreadCount = 0,
    )

    private fun loadedState(query: String = "") = HomeUiState(
        isLoading = false,
        query = query,
        rows = listOf(
            row("demo-office", "办公专家", "Word 版做好了", "21:53"),
            row("demo-mint", "薄荷", "Hermes…", "16:48"),
        ),
    )

    private fun setContent(uiState: HomeUiState = loadedState(), onOpenBot: (String) -> Unit = {}) {
        composeRule.setContent {
            DshmTheme(darkTheme = false) {
                HomeScreen(
                    uiState = uiState,
                    onQueryChange = {},
                    onOpenBot = onOpenBot,
                    onCreateBot = {},
                    onCreateGroup = {},
                )
            }
        }
    }

    @Test
    fun showsBotRowsWithPreviewAndTime() {
        setContent()

        composeRule.onNodeWithText("办公专家").assertIsDisplayed()
        composeRule.onNodeWithText("Word 版做好了").assertIsDisplayed()
        composeRule.onNodeWithText("21:53").assertIsDisplayed()
        composeRule.onNodeWithText("薄荷").assertIsDisplayed()
    }

    @Test
    fun clickingRowOpensThatBot() {
        var opened: String? = null
        setContent(onOpenBot = { opened = it })

        composeRule.onNode(hasText("薄荷") and hasClickAction()).performClick()

        assertEquals("demo-mint", opened)
    }

    @Test
    fun searchButtonOpensSearchField() {
        setContent()

        composeRule.onNodeWithContentDescription(context.getString(R.string.home_search)).performClick()

        composeRule.onNodeWithText(context.getString(R.string.home_search_placeholder)).assertIsDisplayed()
    }

    @Test
    fun plusButtonOpensCreateMenu() {
        setContent()

        composeRule.onNodeWithContentDescription(context.getString(R.string.home_new)).performClick()

        composeRule.onNodeWithText(context.getString(R.string.home_new_bot)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.home_new_group)).assertIsDisplayed()
    }

    @Test
    fun emptyStateIsShownWhenThereAreNoBots() {
        setContent(HomeUiState(isLoading = false))

        composeRule.onNodeWithText(context.getString(R.string.home_empty_title)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.home_empty_hint)).assertIsDisplayed()
    }

    @Test
    fun errorStateIsShownWhenLoadingFails() {
        setContent(HomeUiState(isLoading = false, hasError = true))

        composeRule.onNodeWithText(context.getString(R.string.home_error_title)).assertIsDisplayed()
    }

    @Test
    fun searchingWithoutMatchShowsNoResults() {
        setContent(HomeUiState(isLoading = false, query = "zzz"))

        composeRule.onNodeWithText(context.getString(R.string.home_no_results, "zzz")).assertIsDisplayed()
    }
}
