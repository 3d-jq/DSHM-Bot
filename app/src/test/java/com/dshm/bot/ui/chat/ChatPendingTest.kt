package com.dshm.bot.ui.chat

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 输入框上方 dock 的卡：形状按 DS Bot `state.questions` / `state.views` / `state.secretRequests`。 */
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
        val texts = buildList { walkView(card.view) { if (it.optString("type") == "text") add(it.optString("text")) } }
        assertEquals(listOf("正文"), texts)
    }

    @Test
    fun readsTheSecretCards() {
        val state = JSONObject().put(
            "secretRequests",
            JSONObject().put(
                "bot-1",
                JSONArray()
                    .put(
                        JSONObject()
                            .put("id", "s-1")
                            .put("name", "GITHUB_TOKEN")
                            .put("purpose", "克隆私有仓库")
                            .put("kind", "fill")
                            .put("status", "pending")
                            .put("askedAt", 10L),
                    )
                    .put(
                        JSONObject()
                            .put("id", "s-2")
                            .put("name", "OPENAI_API_KEY")
                            .put("kind", "allow")
                            .put("status", "saved")
                            .put("settledAt", 20L)
                            .put("scope", "all"),
                    ),
            ),
        )

        val requests = parseChatSecrets(state, "bot-1")
        assertEquals(listOf("s-1", "s-2"), requests.map { it.id })
        assertEquals("GITHUB_TOKEN", requests[0].name)
        assertEquals("克隆私有仓库", requests[0].purpose)
        assertTrue(requests[0].pending)
        assertEquals(10L, requests[0].askedAt)
        assertEquals("allow", requests[1].kind)
        assertEquals("all", requests[1].savedScope)
        assertTrue(parseChatSecrets(state, "bot-9").isEmpty())
    }

    @Test
    fun walksTheBlueprintForInputsAndAnswers() {
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
                            .put("multi", true)
                            .put("options", JSONArray().put(JSONObject().put("label", "A"))),
                    )
                    .put(JSONObject().put("type", "submit").put("id", "go").put("label", "跑一下"))
                    .put(
                        JSONObject()
                            .put("type", "form")
                            .put("id", "fill")
                            .put(
                                "fields",
                                JSONArray().put(JSONObject().put("id", "why").put("label", "原因")),
                            ),
                    )
                    .put(
                        JSONObject()
                            .put("type", "toggle")
                            .put("id", "dry")
                            .put("label", "先预演"),
                    )
                    .put(
                        JSONObject()
                            .put("type", "number")
                            .put("id", "count")
                            .put("label", "条数")
                            .put("value", 5),
                    ),
            )

        assertEquals(listOf("range", "dry", "count"), viewInputNodes(view).map { it.optString("id") })
        assertEquals(listOf("pick", "go", "fill"), viewAnswerNodes(view).map { it.optString("id") })
        assertEquals(
            mapOf("range" to "week", "dry" to false, "count" to 5.0),
            viewDefaultInputs(view),
        )
    }

    @Test
    fun choiceDefaultsToItsFirstOption() {
        val view = JSONObject()
            .put("type", "stack")
            .put(
                "children",
                JSONArray().put(
                    JSONObject()
                        .put("type", "choice")
                        .put("id", "range")
                        .put("label", "范围")
                        .put(
                            "options",
                            JSONArray()
                                .put(JSONObject().put("label", "今天").put("value", "day"))
                                .put(JSONObject().put("label", "本周").put("value", "week")),
                        ),
                ),
            )

        assertEquals(mapOf("range" to "day"), viewDefaultInputs(view))
    }

    @Test
    fun resolvesFormulasInPropsAndCells() {
        val view = JSONObject()
            .put("type", "stack")
            .put("let", JSONObject().put("total", "price * qty"))
            .put(
                "children",
                JSONArray()
                    .put(
                        JSONObject()
                            .put("type", "metric")
                            .put("label", "合计")
                            .put("value", JSONObject().put("=", "total")),
                    )
                    .put(
                        JSONObject()
                            .put("type", "table")
                            .put("columns", JSONArray().put(JSONObject().put("key", "name").put("label", "名字")))
                            .put(
                                "rows",
                                JSONArray().put(JSONArray().put(JSONObject().put("=", "'共 ' + total"))),
                            ),
                    )
                    .put(
                        JSONObject()
                            .put("type", "progress")
                            .put("label", "进度")
                            .put("value", JSONObject().put("=", "qty"))
                            .put("max", JSONObject().put("=", "qty * 2")),
                    ),
            )
        val scope = viewScope(view, mapOf("price" to 2.0, "qty" to 3.0))

        val nodes = buildList { walkView(view) { add(it) } }
        val metric = nodes.first { it.optString("type") == "metric" }
        assertEquals("6", metric.textProp("value", scope))

        val table = nodes.first { it.optString("type") == "table" }
        val cell = table.optJSONArray("rows")!!.optJSONArray(0)!!.opt(0)
        assertEquals("共 6", viewText(scope.resolve(cell, "")))

        val progress = nodes.first { it.optString("type") == "progress" }
        assertEquals(3.0, progress.numberProp("value", scope), 0.0)
        assertEquals(6.0, progress.numberProp("max", scope), 0.0)
    }

    @Test
    fun readsChartDataWithFormulaValues() {
        val chartNode = JSONObject()
            .put("type", "chart")
            .put("kind", "donut")
            .put("unit", "次")
            .put("categories", JSONArray().put("今天").put("本周"))
            .put(
                "series",
                JSONArray().put(
                    JSONObject()
                        .put("name", "调用")
                        .put("values", JSONArray().put(3).put(JSONObject().put("=", "qty"))),
                ),
            )
        val chart = chatViewChart(chartNode, viewScope(JSONObject(), mapOf("qty" to 5.0)))

        assertEquals("donut", chart.kind)
        assertEquals(listOf("今天", "本周"), chart.categories)
        assertEquals("调用", chart.series.single().name)
        assertEquals(listOf(3.0, 5.0), chart.series.single().values)
        assertEquals("次", chart.unit)
        assertEquals(5.0, chart.max, 0.0)
    }

    @Test
    fun brokenChartValuesFallBackToZero() {
        val chartNode = JSONObject()
            .put("type", "chart")
            .put("categories", JSONArray().put("A"))
            .put(
                "series",
                JSONArray().put(
                    JSONObject().put("values", JSONArray().put(JSONObject().put("=", "missing"))),
                ),
            )
        val chart = chatViewChart(chartNode, viewScope(JSONObject(), emptyMap()))
        assertEquals("bar", chart.kind)
        assertEquals(listOf(0.0), chart.series.single().values)
        assertEquals(0.0, chart.max, 0.0)
    }
}
