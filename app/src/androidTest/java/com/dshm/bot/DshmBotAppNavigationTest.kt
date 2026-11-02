package com.dshm.bot

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dshm.bot.core.design.DshmTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 页面切换（iOS 整屏 push/pop）：点 Bot 进对话，顶栏返回 / 系统返回都能回到主界面。 */
@RunWith(AndroidJUnit4::class)
class DshmBotAppNavigationTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val chatTitle = "与 办公专家 的对话"

    private fun launchApp() {
        composeRule.setContent {
            DshmTheme(darkTheme = false) {
                DshmBotApp()
            }
        }
    }

    private fun openFirstBot() {
        composeRule.onNodeWithText("办公专家").performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText(chatTitle).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun awaitHome() {
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText(chatTitle).fetchSemanticsNodes().isEmpty()
        }
    }

    @Test
    fun topBarBackReturnsHome() {
        launchApp()
        openFirstBot()

        composeRule
            .onNodeWithContentDescription(composeRule.activity.getString(R.string.action_back))
            .performClick()
        awaitHome()
    }

    @Test
    fun systemBackFromChatReturnsHome() {
        launchApp()
        openFirstBot()

        composeRule.runOnUiThread {
            composeRule.activity.onBackPressedDispatcher.onBackPressed()
        }
        awaitHome()
    }

    @Test
    fun searchPushesAndPops() {
        launchApp()
        val placeholder = composeRule.activity.getString(R.string.home_search_placeholder)

        composeRule
            .onNodeWithContentDescription(composeRule.activity.getString(R.string.home_search))
            .performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText(placeholder).fetchSemanticsNodes().isNotEmpty()
        }

        composeRule
            .onNodeWithContentDescription(composeRule.activity.getString(R.string.action_back))
            .performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText(placeholder).fetchSemanticsNodes().isEmpty()
        }
    }
}
