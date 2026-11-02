package com.dshm.bot.ui.chat

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dshm.bot.core.design.DshmTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 输入框行数增加时必须向上长高（dsh-Go 的 1–6 行自适应行为）。 */
@RunWith(AndroidJUnit4::class)
class ChatComposerHeightTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun growsWhenTextWrapsToThreeLines() {
        val state = mutableStateOf("")
        composeRule.setContent {
            DshmTheme(darkTheme = false) {
                ChatComposer(
                    value = state.value,
                    onValueChange = { state.value = it },
                    onSend = {},
                    placeholder = "x",
                )
            }
        }

        val singleLine = composeRule.onNodeWithTag(COMPOSER_TEST_TAG).fetchSemanticsNode().size.height
        state.value = "a\nb\nc"
        composeRule.waitForIdle()
        val threeLines = composeRule.onNodeWithTag(COMPOSER_TEST_TAG).fetchSemanticsNode().size.height

        assertTrue("composer should grow: $singleLine -> $threeLines", threeLines > singleLine)
        assertTrue("should grow by more than one line: $singleLine -> $threeLines", threeLines - singleLine > 40)
    }
}
