package com.dshm.bot.ui.chat

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 输入框上方 dock 的两张卡：形状按 DS Bot `state.questions` / `state.views`。 */
class ChatPendingTest {

    @Test
    fun readsTheAskUserQuestionCard() {
        val state = JSONObject().put(
            "questions",
            JSONObject().put(
                "bot-1",
                JSONObject()
                    .put("id", "q-1")
                    .put("question", "用哪个模型？")
                    .put("detail", "默认是它自己选的")
                    .put("multiSelect", true)
                    .put("allowCustom", true)
                    .put(
                        "options",
                        JSONArray()
                            .put(JSONObject().put("label", "DeepSeek").put("description", "便宜"))
                            .put(JSONObject().put("label", "GPT")),
                    ),
            ),
        )

        val card = parseChatQuestions(state, "bot-1").single()
        assertEquals("q-1", card.id)
        assertEquals("用哪个模型？", card.question)
        assertEquals("默认是它自己选的", card.detail)
        assertTrue(card.multiSelect)
        assertTrue(card.allowCustom)
        assertEquals(listOf("DeepSeek", "GPT"), card.options.map { it.label })
        assertEquals("便宜", card.options[0].description)

        assertTrue(parseChatQuestions(state, "bot-2").isEmpty())
    }

    @Test
    fun readsThePendingViewCard() {
        val state = JSONObject().put(
            "views",
            JSONObject().put(
                "bot-1",
                JSONObject()
                    .put("id", "v-1")
                    .put("title", "本周用量")
                    .put(
                        "view",
                        JSONObject()
                            .put("type", "stack")
                            .put(
                                "children",
                                JSONArray().put(JSONObject().put("type", "text").put("text", "正文")),
                            ),
                    ),
            ),
        )

        val card = parseChatViews(state, "bot-1").single()
        assertEquals("v-1", card.id)
        assertEquals("本周用量", card.title)
        assertEquals(
            listOf("正文"),
            viewDisplayNodes(card.view).map { it.optString("text") },
        )
    }

    @Test
    fun walksTheBlueprintForControlsAndInputs() {
        val view = JSONObject()
            .put("type", "stack")
            .put(
                "children",
                JSONArray()
                    .put(JSONObject().put("type", "metric").put("label", "调用").put("value", 12))
                    .put(
                        JSONObject()
                            .put("type", "choice")
                            .put("id", "range")
                            .put("label", "范围")
                            .put("value", "week")
                            .put("options", JSONArray().put("day").put("week")),
                    )
                    .put(
                        JSONObject()
                            .put("type", "options")
                            .put("id", "pick")
                            .put("label", "选一个")
                            .put("multi", true)
                            .put("options", JSONArray().put(JSONObject().put("label", "A").put("value", "a"))),
                    )
                    .put(JSONObject().put("type", "submit").put("id", "go").put("label", "跑一下"))
                    .put(JSONObject().put("type", "chart").put("kind", "bar")),
            )

        assertEquals(listOf("metric", "chart"), viewDisplayNodes(view).map { it.optString("type") })

        val actions = chatViewActions(view)
        assertEquals(listOf("pick", "go"), actions.map { it.id })
        assertEquals("options", actions[0].kind)
        assertTrue(actions[0].multi)
        assertEquals(listOf("A"), actions[0].options)
        assertEquals("跑一下", actions[1].label)

        val inputs = chatViewInputFields(view)
        assertEquals(listOf("range"), inputs.map { it.id })
        assertEquals(listOf("day" to "day", "week" to "week"), inputs[0].options)
        assertEquals(mapOf("range" to "week"), chatViewInputs(view))
    }
}
