package com.dshm.bot.data.remote

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 渠道模型规格的写入契约：引擎把模型规格存在 `providers.<route>.models` 里，
 * 没有单独的覆盖字段（对齐 dsh-Go `providers_api.dart` 的 `updateProvider`）。
 *
 * 用本机 FakeDshServer 抓真实请求体断言，防止把线协议写歪。
 */
class ProviderSettingsContractTest {

    private fun okResponse(value: String) =
        """{"result":{"ok":true,"value":$value}}"""

    @Test
    fun writesModelSpecsIntoProvidersModelsArray() {
        FakeDshServer().use { server ->
            server.responder = { request ->
                when {
                    request.path.startsWith(FakeDshServer.LOGIN_PATH_PREFIX) ->
                        FakeDshServer.cookie("v1")
                    request.path == "/api/settings/mutate" -> FakeDshServer.Response(
                        200,
                        body = okResponse("""{"ns":"llm-pi-ai","revision":9,"value":{"providers":{}}}"""),
                    )
                    else -> FakeDshServer.Response(404)
                }
            }
            server.start()

            val repo = DshEngineSettingsRepository(DshClient(server.endpoint))
            val spec = ProviderModelSpec.fromJson(
                JSONObject()
                    .put("id", "gpt-x")
                    .put("name", "GPT X")
                    .put("contextWindow", 131072)
                    .put("input", JSONArray().put("text").put("image"))
                    .put(
                        "reasoningEfforts",
                        JSONObject().put("off", JSONObject.NULL).put("high", "high"),
                    ),
            )!!

            runBlocking {
                repo.mutate(
                    PROVIDER_NAMESPACE,
                    listOf(
                        SettingsOp.set(
                            listOf("providers", "my-gw", "models"),
                            JSONArray().put(spec.toJson()),
                        ),
                    ),
                    expectedRevision = 3,
                )
            }

            val mutate = server.requests().first { it.path == "/api/settings/mutate" }
            val envelope = JSONObject(mutate.body)
            assertEquals("client-request", envelope.getString("type"))
            assertEquals("settings/mutate", envelope.getString("method"))

            val args = envelope.getJSONObject("payload").getJSONObject("args")
            assertEquals(PROVIDER_NAMESPACE, args.getString("ns"))
            assertEquals(3, args.getInt("expectedRevision"))

            val op = args.getJSONArray("ops").getJSONObject(0)
            assertEquals("set", op.getString("op"))
            val path = op.getJSONArray("path")
            assertEquals("providers", path.getString(0))
            assertEquals("my-gw", path.getString(1))
            // 关键：模型规格写在 models 数组里，不是 modelOverrides。
            assertEquals("models", path.getString(2))

            val model = op.getJSONArray("value").getJSONObject(0)
            assertEquals("gpt-x", model.getString("id"))
            assertEquals("GPT X", model.getString("name"))
            assertEquals(131072, model.getInt("contextWindow"))
            assertEquals("image", model.getJSONArray("input").getString(1))
            assertEquals("high", model.getJSONObject("reasoningEfforts").getString("high"))
        }
    }

    @Test
    fun describeReadsProviderProfilesWithModels() {
        FakeDshServer().use { server ->
            server.responder = { request ->
                when {
                    request.path.startsWith(FakeDshServer.LOGIN_PATH_PREFIX) ->
                        FakeDshServer.cookie("v1")
                    request.path == "/api/settings/describe" -> FakeDshServer.Response(
                        200,
                        body = okResponse(
                            """
                            {
                              "writable": true,
                              "namespaces": [{
                                "ns": "llm-pi-ai",
                                "revision": 4,
                                "value": {"providers": {"my-gw": {
                                  "displayName": "My GW",
                                  "api": "openai-completions",
                                  "baseURL": "https://example.com/v1",
                                  "apiKeyEnv": "MY_GW_API_KEY",
                                  "defaultContextWindow": 262144,
                                  "models": [{
                                    "id": "gpt-x",
                                    "contextWindow": 131072,
                                    "input": ["text", "image"],
                                    "reasoningEfforts": {"off": null, "high": "high"}
                                  }]
                                }}},
                                "user": {"providers": {"my-gw": {}}},
                                "base": {"providers": {}}
                              }]
                            }
                            """.trimIndent(),
                        ),
                    )
                    else -> FakeDshServer.Response(404)
                }
            }
            server.start()

            val repo = DshEngineSettingsRepository(DshClient(server.endpoint))
            val providers = runBlocking { repo.providerSettings() }

            assertEquals(1, providers.profiles.size)
            val profile = providers.profiles.first()
            assertEquals("my-gw", profile.route)
            assertEquals("My GW", profile.label)
            assertEquals(262144, profile.defaultContextWindow)
            assertTrue(providers.removable("my-gw"))

            val model = profile.models.first()
            assertEquals("gpt-x", model.id)
            assertEquals(131072, model.contextWindow)
            assertTrue(model.acceptsImages())
            assertTrue(model.thinks)
            assertEquals(listOf("off", "high"), model.thinkingLevels)
            // 渠道默认值能算出「这个模型没声明时的上下文长度」。
            assertEquals(262144, ModelDefaults.of(profile).contextWindow)
        }
    }
}
