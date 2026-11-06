package com.dshm.bot.data.remote

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 模型规格（`llm-pi-ai` 的 `models` 一条）的读写语义，
 * 对齐 dsh-Go `packages/dsh_client/lib/src/providers_api.dart`。
 */
class ProviderModelSpecTest {

    @Test
    fun parsesModelFieldsAndKeepsUnknownFields() {
        val spec = ProviderModelSpec.fromJson(
            JSONObject(
                """
                {
                  "id": "gpt-x",
                  "name": "GPT X",
                  "contextWindow": 131072,
                  "maxTokens": 8192,
                  "input": ["text", "image"],
                  "compat": {"family": "openai"}
                }
                """.trimIndent(),
            ),
        )!!
        assertEquals("gpt-x", spec.id)
        assertEquals("GPT X", spec.name)
        assertEquals("GPT X", spec.label)
        assertEquals(131072, spec.contextWindow)
        assertEquals(8192, spec.maxTokens)
        assertEquals(listOf("text", "image"), spec.input)
        assertTrue(spec.acceptsImages())
        assertFalse(spec.thinks)

        // 没建模的字段（compat）必须原样回写，编辑一次不能把引擎写的弄丢。
        val json = spec.toJson()
        assertEquals("openai", json.getJSONObject("compat").getString("family"))
        assertEquals("gpt-x", json.getString("id"))
        assertEquals(131072, json.getInt("contextWindow"))
    }

    @Test
    fun labelFallsBackToIdWhenUnnamed() {
        val spec = ProviderModelSpec.create("plain-model")
        assertEquals("plain-model", spec.label)
        assertEquals("plain-model", spec.toJson().getString("id"))
    }

    @Test
    fun reasoningEffortsGiveLevelsAndThinking() {
        val spec = ProviderModelSpec.fromJson(
            JSONObject()
                .put("id", "m")
                .put(
                    "reasoningEfforts",
                    JSONObject().put("off", JSONObject.NULL).put("low", "low").put("high", "high"),
                ),
        )!!
        assertEquals(listOf("off", "low", "high"), spec.thinkingLevels)
        assertTrue(spec.thinks)
        assertEquals("high", spec.reasoningEfforts!!["high"])
        // off 不发送任何东西。
        assertNull(spec.reasoningEfforts!!["off"])
    }

    @Test
    fun unknownLevelsAreDroppedAndOffOnlyDisablesThinking() {
        val spec = ProviderModelSpec.fromJson(
            JSONObject()
                .put("id", "m")
                .put(
                    "reasoningEfforts",
                    JSONObject().put("off", JSONObject.NULL).put("none", "none"),
                ),
        )!!
        // 适配器不认识的档位要丢掉；只剩 off 的表是非法的，读取时显式关闭思考。
        assertTrue(spec.thinkingLevels.isEmpty())
        assertFalse(spec.thinks)
        assertEquals(false, spec.extra.opt("reasoningEfforts"))
    }

    @Test
    fun withReasoningEffortsWritesLevelsInEscalationOrder() {
        val next = ProviderModelSpec.create("m").withReasoningEfforts(listOf("high", "low"))
        assertEquals(listOf("low", "high"), next.thinkingLevels)
        assertEquals("low", next.reasoningEfforts!!["low"])
        assertTrue(next.thinks)
    }

    @Test
    fun withReasoningEffortsDropsFieldWhenNothingIsDeclared() {
        val next = ProviderModelSpec.create("m")
            .withReasoningEfforts(listOf("low"))
            .withReasoningEfforts(emptyList())
        assertFalse(next.extra.has("reasoningEfforts"))
        assertFalse(next.thinks)
    }

    @Test
    fun withReasoningEffortsKeepsExplicitDisable() {
        // 之前是显式关闭思考的，空集合要保留 false，不能变成「字段缺失」
        // （字段缺失会继承目录里的档位）。
        val spec = ProviderModelSpec.create("m").copy(extra = JSONObject().put("reasoningEfforts", false))
        val next = spec.withReasoningEfforts(emptyList())
        assertTrue(next.extra.has("reasoningEfforts"))
        assertEquals(false, next.extra.opt("reasoningEfforts"))
    }

    @Test
    fun withReasoningEffortsKeepsPreviousLevelValues() {
        val spec = ProviderModelSpec.fromJson(
            JSONObject()
                .put("id", "m")
                .put("reasoningEfforts", JSONObject().put("low", "lowish")),
        )!!
        val next = spec.withReasoningEfforts(listOf("low", "medium"))
        // 老档位保留引擎配好的值，新档位用自己的名字。
        assertEquals("lowish", next.reasoningEfforts!!["low"])
        assertEquals("medium", next.reasoningEfforts!!["medium"])
    }

    @Test
    fun offOnlyDeclarationDisablesThinking() {
        val next = ProviderModelSpec.create("m").withReasoningEfforts(listOf("off"))
        // 只剩 off → 显式关闭思考。
        assertEquals(false, next.extra.opt("reasoningEfforts"))
        assertFalse(next.thinks)
    }

    @Test
    fun acceptsImagesUsesRouteDefaultWhenUndeclared() {
        val spec = ProviderModelSpec.create("m")
        assertFalse(spec.acceptsImages(listOf("text")))
        assertTrue(spec.acceptsImages(listOf("text", "image")))
        val declared = ProviderModelSpec.create("m").copy(input = listOf("text"))
        assertFalse(declared.acceptsImages(listOf("text", "image")))
    }

    @Test
    fun modelDefaultsFallBackToAdapterValues() {
        assertEquals(262144, ModelDefaults().contextWindow)
        assertEquals(32768, ModelDefaults().maxTokens)
        assertEquals(listOf("text"), ModelDefaults().input)

        val profile = ProviderProfile.fromJson(
            "r",
            JSONObject()
                .put("defaultContextWindow", 1_000_000)
                .put("defaultMaxTokens", 65_536)
                .put("defaultInput", JSONArray().put("text").put("image")),
        )
        val defaults = ModelDefaults.of(profile)
        assertEquals(1_000_000, defaults.contextWindow)
        assertEquals(65_536, defaults.maxTokens)
        assertEquals(listOf("text", "image"), defaults.input)

        // 没声明就用适配器的默认值。
        val plain = ModelDefaults.of(ProviderProfile.fromJson("r", JSONObject()))
        assertEquals(262144, plain.contextWindow)
        assertEquals(32768, plain.maxTokens)
        assertEquals(listOf("text"), plain.input)
    }

    @Test
    fun providerProfileKeepsRouteDefaults() {
        val profile = ProviderProfile.fromJson(
            "my-gw",
            JSONObject()
                .put("displayName", "My GW")
                .put("api", "openai-completions")
                .put("defaultContextWindow", 65_536),
        )
        assertEquals(65_536, profile.defaultContextWindow)
        assertNull(profile.defaultMaxTokens)
        assertNull(profile.defaultInput)
        assertEquals("My GW", profile.label)
    }
}
