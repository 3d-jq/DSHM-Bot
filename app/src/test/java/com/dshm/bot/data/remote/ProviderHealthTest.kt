package com.dshm.bot.data.remote

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** 渠道健康度四态：可用 / 缺少 API Key / 配置有误 / 未启用。 */
class ProviderHealthTest {

    private fun profile(api: String?, baseUrl: String?, modelIds: List<String>) =
        ProviderProfile.fromJson(
            "r1",
            JSONObject()
                .put("displayName", "网关")
                .put("api", api)
                .put("baseURL", baseUrl)
                .put("apiKeyEnv", "MY_KEY")
                .put(
                    "models",
                    JSONArray().apply { modelIds.forEach { put(JSONObject().put("id", it)) } },
                ),
        )

    @Test
    fun readyWhenProtocolBaseUrlAndKeyArePresent() {
        val value = profile("openai-completions", "https://api.example.com", listOf("m1"))
        assertEquals(ProviderHealth.READY, ProviderHealthEvaluator.evaluate(value, keyConfigured = true))
    }

    @Test
    fun missingKeyWhenCredentialIsNotConfigured() {
        val value = profile("openai-completions", "https://api.example.com", listOf("m1"))
        assertEquals(
            ProviderHealth.MISSING_KEY,
            ProviderHealthEvaluator.evaluate(value, keyConfigured = false),
        )
    }

    @Test
    fun misconfiguredWhenProtocolOrBaseUrlIsMissing() {
        assertEquals(
            ProviderHealth.MISCONFIGURED,
            ProviderHealthEvaluator.evaluate(
                profile(null, "https://api.example.com", listOf("m1")),
                keyConfigured = true,
            ),
        )
        assertEquals(
            ProviderHealth.MISCONFIGURED,
            ProviderHealthEvaluator.evaluate(
                profile("openai-completions", null, listOf("m1")),
                keyConfigured = true,
            ),
        )
    }

    @Test
    fun disabledWhenNoModelIsConfigured() {
        val value = profile("openai-completions", "https://api.example.com", emptyList())
        assertEquals(ProviderHealth.DISABLED, ProviderHealthEvaluator.evaluate(value, keyConfigured = true))
    }
}
