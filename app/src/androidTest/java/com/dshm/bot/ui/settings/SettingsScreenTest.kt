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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 设置页：分组渲染、主题行显示当前值、选择主题回调。 */
@RunWith(AndroidJUnit4::class)
class SettingsScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun setContent(
        themeMode: ThemeMode = ThemeMode.SYSTEM,
        onThemeChange: (ThemeMode) -> Unit = {},
    ) {
        composeRule.setContent {
            DshmTheme(darkTheme = false) {
                SettingsScreen(
                    uiState = SettingsUiState(themeMode = themeMode),
                    onBack = {},
                    onThemeChange = onThemeChange,
                    onOpenEngine = {},
                    onOpenAppearance = {},
                    onOpenChatFont = {},
                    onOpenLanguage = {},
                    chatFontLabel = "标准",
                    languageLabel = "跟随系统",
                    onOpenAccount = {},
                )
            }
        }
    }

    @Test
    fun showsGroupsAndCurrentValues() {
        setContent()
        composeRule.onNodeWithText(context.getString(R.string.settings_group_ai)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.settings_group_appearance)).assertIsDisplayed()
        composeRule.onAllNodesWithText(ThemeMode.SYSTEM.label(context)).onFirst().assertIsDisplayed()

        val about = context.getString(R.string.settings_group_about)
        composeRule.onNodeWithTag(SETTINGS_LIST_TAG).performScrollToNode(hasText(about))
        composeRule.onNodeWithText(about).assertIsDisplayed()

        val version = context.getString(R.string.settings_version)
        composeRule.onNodeWithTag(SETTINGS_LIST_TAG).performScrollToNode(hasText(version))
        composeRule.onNodeWithText(version).assertIsDisplayed()
    }

    @Test
    fun pickingDarkThemeCallsCallback() {
        var picked: ThemeMode? = null
        setContent(onThemeChange = { picked = it })

        composeRule.onNode(hasText(context.getString(R.string.settings_theme))).performClick()
        composeRule.onNodeWithText(context.getString(R.string.theme_dark)).performClick()

        assertEquals(ThemeMode.DARK, picked)
    }
}

/** 主题文案（与 ThemeMode 对应）。 */
internal fun ThemeMode.label(context: android.content.Context): String = context.getString(
    when (this) {
        ThemeMode.SYSTEM -> R.string.theme_system
        ThemeMode.LIGHT -> R.string.theme_light
        ThemeMode.DARK -> R.string.theme_dark
    },
)






