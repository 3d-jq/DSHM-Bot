package com.dshm.bot.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dshm.bot.R
import com.dshm.bot.core.design.DshmTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 输入框：空白禁用发送、输入后主色发送键可点、附件回调。 */
@RunWith(AndroidJUnit4::class)
class ChatComposerTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun setContent(
        initial: String = "",
        onSend: () -> Unit = {},
        onAttach: () -> Unit = {},
    ) {
        composeRule.setContent {
            DshmTheme(darkTheme = false) {
                var text by remember { mutableStateOf(initial) }
                ChatComposer(
                    value = text,
                    onValueChange = { text = it },
                    onSend = onSend,
                    placeholder = "给测试发消息…",
                    onAttach = onAttach,
                )
            }
        }
    }

    private fun sendNode() = composeRule.onNodeWithContentDescription(context.getString(R.string.composer_send))

    @Test
    fun sendIsDisabledWhenEmpty() {
        setContent()
        sendNode().assertIsNotEnabled()
    }

    @Test
    fun typingEnablesSendAndFiresCallback() {
        var sendCount = 0
        setContent(onSend = { sendCount += 1 })

        composeRule.onNode(hasSetTextAction()).performTextInput("你好")
        sendNode().assertIsEnabled()
        sendNode().performClick()

        assertEquals(1, sendCount)
    }

    @Test
    fun attachButtonFiresCallback() {
        var attachCount = 0
        setContent(onAttach = { attachCount += 1 })

        composeRule.onNodeWithContentDescription(context.getString(R.string.composer_attach)).performClick()

        assertEquals(1, attachCount)
    }
}
