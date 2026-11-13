package com.dshm.bot.ui.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dshm.bot.R
import com.dshm.bot.core.design.DshmTheme
import com.dshm.bot.domain.model.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 设置首页：分组渲染、真实值、搜索入口与子页回调。 */
@RunWith(AndroidJUnit4::class)
class SettingsScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun setContent(
        themeMode: ThemeMode = ThemeMode.SYSTEM,
        onThemeChange: (ThemeMode) -> Unit = {},
        onOpenPage: (SettingsPageId) -> Unit = {},
    ) {
        val engineSettings = EngineSettingsViewModel()
        val botPrefs = BotPrefsViewModel(endpoint = null)
        composeRule.setContent {
            DshmTheme(darkTheme = false) {
                SettingsScreen(
                    uiState = SettingsUiState(themeMode = themeMode),
                    engineSettings = engineSettings,
                    botPrefs = botPrefs,
                    onBack = {},
                    onOpenEngine = {},
                    onThemeChange = onThemeChange,
                    onOpenChatFont = {},
                    onLanguageChange = {},
                    onOpenPage = onOpenPage,
                    onOpenAccount = {},
                    chatFontLabel = "标准",
                    languageLabel = "跟随系统",
                    engineStatus = "未运行",
                )
            }
        }
    }

    @Test
    fun showsGroupsAndCurrentValues() {
        setContent()
        composeRule.onNodeWithText(context.getString(R.string.settings_group_ai)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.settings_group_appearance)).assertIsDisplayed()
        composeRule.onAllNodesWithText(context.getString(R.string.theme_system)).onFirst().assertIsDisplayed()

        val about = context.getString(R.string.settings_group_about)
        composeRule.onNodeWithTag(SETTINGS_LIST_TAG).performScrollToNode(hasText(about))
        composeRule.onNodeWithText(about).assertIsDisplayed()

        val version = context.getString(R.string.settings_version)
        composeRule.onNodeWithTag(SETTINGS_LIST_TAG).performScrollToNode(hasText(version))
        composeRule.onNodeWithText(version).assertIsDisplayed()
    }

    @Test
    fun themeRowOpensPickerSheet() {
        var picked: ThemeMode? = null
        setContent(onThemeChange = { picked = it })
        composeRule.onNode(hasText(context.getString(R.string.settings_group_appearance))).performClick()
        composeRule.onNodeWithText(context.getString(R.string.theme_dark)).performClick()
        assertEquals(ThemeMode.DARK, picked)
    }

    @Test
    fun modelRowOpensModelPage() {
        var picked: SettingsPageId? = null
        setContent(onOpenPage = { picked = it })
        composeRule.onNode(hasText(context.getString(R.string.settings_model))).performClick()
        assertEquals(SettingsPageId.MODEL, picked)
    }
}
