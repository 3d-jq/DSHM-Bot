package com.dshm.bot.ui.settings

import com.dshm.bot.MainDispatcherRule
import com.dshm.bot.data.remote.AgentPresetDocument
import com.dshm.bot.data.remote.AgentPresetInfo
import com.dshm.bot.data.remote.AgentPresetSource
import com.dshm.bot.data.remote.CredentialStatus
import com.dshm.bot.data.remote.DiscoveredModels
import com.dshm.bot.data.remote.DshEngineSettingsRepository
import com.dshm.bot.data.remote.DshRpcException
import com.dshm.bot.data.remote.EngineSettingsGateway
import com.dshm.bot.data.remote.EngineSettingsRepositories
import com.dshm.bot.data.remote.EngineSettingsRepositoryFactory
import com.dshm.bot.data.remote.PermissionCatalog
import com.dshm.bot.data.remote.PluginBundle
import com.dshm.bot.data.remote.PluginInventory
import com.dshm.bot.data.remote.PluginToggleResult
import com.dshm.bot.data.remote.SessionModelCatalogSource
import com.dshm.bot.data.remote.SettingsDescription
import com.dshm.bot.data.remote.SettingsNamespace
import com.dshm.bot.data.remote.SettingsOp
import com.dshm.bot.data.remote.SpeechCatalog
import com.dshm.bot.data.remote.SpeechRepository
import com.dshm.bot.data.runtime.RuntimeEndpoint
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * `EngineSettingsViewModel` 的注入缝测试：
 * 覆盖原来只能靠真机验证的 revision 冲突重试、凭据写入与刷新落状态。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EngineSettingsViewModelInjectionTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val endpoint = RuntimeEndpoint("http://127.0.0.1:1/", "test-token")

    private fun namespace(revision: Int, value: JSONObject): SettingsNamespace =
        SettingsNamespace.fromJson(
            JSONObject()
                .put("ns", "appearance")
                .put("revision", revision)
                .put("value", value),
            writable = true,
        )!!

    private fun descriptionOf(vararg namespaces: SettingsNamespace): SettingsDescription {
        val map = LinkedHashMap<String, SettingsNamespace>()
        namespaces.forEach { map[it.ns] = it }
        return SettingsDescription(namespaces = map, writable = true, hasDocument = false)
    }

    private fun viewModel(gateway: EngineSettingsGateway): EngineSettingsViewModel =
        EngineSettingsViewModel(
            EngineSettingsRepositoryFactory {
                EngineSettingsRepositories(
                    settings = gateway,
                    sessions = EmptyModelCatalog,
                    features = EmptyAgentPresets,
                )
            },
        )

    @Test
    fun conflictRetriesWhenFieldHasNotChangedElsewhere() = runTest(mainDispatcherRule.testDispatcher) {
        val gateway = FakeGateway(
            descriptions = listOf(
                descriptionOf(namespace(1, JSONObject().put("theme", "dark"))),
                descriptionOf(namespace(2, JSONObject().put("theme", "dark"))),
            ),
            onMutate = { revision ->
                if (revision == 1) {
                    throw DshRpcException("settings/conflict", "revision 过期", null)
                }
                namespace(2, JSONObject().put("theme", "light"))
            },
        )
        val viewModel = viewModel(gateway)

        viewModel.setEndpoint(endpoint)
        advanceUntilIdle()
        viewModel.setValue("appearance", listOf("theme"), "light")
        advanceUntilIdle()

        assertEquals(listOf(1, 2), gateway.mutateRevisions)
        assertNull(viewModel.ui.value.error)
        assertEquals("light", viewModel.ui.value.description?.namespace("appearance")?.valueOf(listOf("theme")))
    }

    @Test
    fun conflictKeepsErrorWhenFieldChangedElsewhere() = runTest(mainDispatcherRule.testDispatcher) {
        val gateway = FakeGateway(
            descriptions = listOf(
                descriptionOf(namespace(1, JSONObject().put("theme", "dark"))),
                descriptionOf(namespace(2, JSONObject().put("theme", "sepia"))),
            ),
            onMutate = { throw DshRpcException("settings/conflict", "revision 过期", null) },
        )
        val viewModel = viewModel(gateway)

        viewModel.setEndpoint(endpoint)
        advanceUntilIdle()
        viewModel.setValue("appearance", listOf("theme"), "light")
        advanceUntilIdle()

        assertEquals(listOf(1), gateway.mutateRevisions)
        assertEquals(
            "设置已在其他页面被修改，请重新打开后再试",
            viewModel.ui.value.fieldErrors["appearance:theme"],
        )
    }

    @Test
    fun savingCredentialWritesThroughGateway() = runTest(mainDispatcherRule.testDispatcher) {
        val gateway = FakeGateway(descriptions = listOf(descriptionOf(namespace(1, JSONObject()))))
        val viewModel = viewModel(gateway)
        viewModel.setEndpoint(endpoint)
        advanceUntilIdle()

        var done: String? = "not-called"
        viewModel.saveCredential("provider-key", "  secret-value  ") { error -> done = error }
        advanceUntilIdle()

        assertEquals(listOf("provider-key" to "secret-value"), gateway.savedCredentials)
        assertNull(done)
    }

    @Test
    fun setEndpointLoadsEveryDescriptionSection() = runTest(mainDispatcherRule.testDispatcher) {
        val bundle = PluginBundle(
            name = "bundle-a",
            official = false,
            version = "1.0.0",
            meta = null,
            description = null,
            enabled = true,
            installed = true,
            optional = true,
            removable = true,
            readOnlyReason = null,
            error = null,
        )
        val gateway = FakeGateway(
            descriptions = listOf(descriptionOf(namespace(1, JSONObject()))),
            bundles = listOf(bundle),
            providers = listOf(DshEngineSettingsRepository.ListedProvider("openai", "OpenAI")),
        )
        val viewModel = viewModel(gateway)

        viewModel.setEndpoint(endpoint)
        advanceUntilIdle()

        val state = viewModel.ui.value
        assertNotNull(state.description)
        assertNotNull(state.plugins)
        assertEquals(listOf("bundle-a"), state.bundles.map { it.name })
        assertNotNull(state.permission)
        assertEquals(listOf("OpenAI"), state.listedProviders.map { it.name })
    }

    @Test
    fun staleRefreshFromOldEndpointDoesNotOverwriteNewEndpointState() = runTest(mainDispatcherRule.testDispatcher) {
        // 旧端点的 describe 挂起（模拟慢返回）；切到新端点、新状态落定后再放行，
        // 旧端点的结果必须按代际被丢弃，不能把新端点的状态盖回去。
        val gatewayA = FakeGateway(
            descriptions = listOf(descriptionOf(namespace(1, JSONObject().put("theme", "from-a")))),
            describeGate = CompletableDeferred(),
        )
        val gatewayB = FakeGateway(
            descriptions = listOf(descriptionOf(namespace(1, JSONObject().put("theme", "from-b")))),
        )
        val endpointA = RuntimeEndpoint("http://127.0.0.1:1/", "token-a")
        val endpointB = RuntimeEndpoint("http://127.0.0.1:2/", "token-b")
        val viewModel = EngineSettingsViewModel(
            EngineSettingsRepositoryFactory { next ->
                if (next == endpointA) {
                    EngineSettingsRepositories(
                        settings = gatewayA,
                        sessions = EmptyModelCatalog,
                        features = EmptyAgentPresets,
                    )
                } else {
                    EngineSettingsRepositories(
                        settings = gatewayB,
                        sessions = EmptyModelCatalog,
                        features = EmptyAgentPresets,
                    )
                }
            },
        )

        viewModel.setEndpoint(endpointA)
        advanceUntilIdle() // A 的 describe 还挂在门上，loading 中
        assertTrue(viewModel.ui.value.loading)

        viewModel.setEndpoint(endpointB)
        advanceUntilIdle() // B 完成，状态是 from-b
        assertEquals("from-b", viewModel.ui.value.description?.namespace("appearance")?.valueOf(listOf("theme")))

        gatewayA.releaseDescribe()
        advanceUntilIdle() // A 的慢返回落地，应被代际校验丢掉
        assertEquals("from-b", viewModel.ui.value.description?.namespace("appearance")?.valueOf(listOf("theme")))
        assertNull(viewModel.ui.value.error)
        assertTrue(!viewModel.ui.value.loading)
    }

    // ------------------------------------------------------------------
    // 假依赖
    // ------------------------------------------------------------------

    private class FakeGateway(
        private val descriptions: List<SettingsDescription>,
        private val onMutate: suspend (Int?) -> SettingsNamespace = { revision ->
            throw UnsupportedOperationException("mutate 未配置：$revision")
        },
        private val bundles: List<PluginBundle> = emptyList(),
        private val providers: List<DshEngineSettingsRepository.ListedProvider> = emptyList(),
        /** 非 null 时 describe() 先挂到这扇门上，用来模拟旧端点的慢返回。 */
        private val describeGate: CompletableDeferred<Unit>? = null,
    ) : EngineSettingsGateway {

        val mutateRevisions = mutableListOf<Int?>()
        val savedCredentials = mutableListOf<Pair<String, String>>()
        private var describeCalls = 0

        fun releaseDescribe() {
            describeGate?.complete(Unit)
        }

        override val speech: SpeechRepository = EmptySpeech

        override suspend fun describe(): SettingsDescription {
            describeGate?.await()
            val index = describeCalls.coerceAtMost(descriptions.lastIndex)
            describeCalls += 1
            return descriptions[index]
        }

        override suspend fun mutate(
            ns: String,
            ops: List<SettingsOp>,
            expectedRevision: Int?,
            writable: Boolean,
        ): SettingsNamespace {
            mutateRevisions += expectedRevision
            return onMutate(expectedRevision)
        }

        override suspend fun pluginInventory(): PluginInventory =
            PluginInventory(entries = emptyList(), managementAvailable = false)

        override suspend fun pluginBundles(): List<PluginBundle> = bundles

        override suspend fun permissionCatalog(): PermissionCatalog =
            PermissionCatalog(options = emptyList(), defaultOptions = emptyList(), defaultPreset = "")

        override suspend fun describeCredentials(refs: List<String>): Map<String, CredentialStatus> =
            emptyMap()

        override suspend fun setCredential(ref: String, value: String) {
            savedCredentials += ref to value
        }

        override suspend fun unsetCredential(ref: String) = Unit

        override suspend fun listProviders(): List<DshEngineSettingsRepository.ListedProvider> = providers

        override suspend fun discoverModels(
            baseUrl: String,
            api: String,
            apiKey: String,
            route: String?,
        ): DiscoveredModels = DiscoveredModels(ids = emptyList(), baseUrl = baseUrl)

        override suspend fun removeBundle(name: String) = Unit

        override suspend fun installBundle(spec: String, enabled: Boolean?) = Unit

        override suspend fun setPluginBundleEnabled(name: String, enabled: Boolean): PluginToggleResult =
            PluginToggleResult(succeeded = true, needsRestart = false, message = "")
    }

    private object EmptySpeech : SpeechRepository {
        override suspend fun catalog(): SpeechCatalog? = null
        override suspend fun prepare(providerId: String) = Unit
        override suspend fun cancelPreparation(providerId: String) = Unit
        override suspend fun configure(providerId: String?, language: String?) = Unit
        override suspend fun transcribe(wav: ByteArray): String = ""
    }

    private object EmptyModelCatalog : SessionModelCatalogSource {
        override suspend fun modelCatalog(): JSONObject = JSONObject()
    }

    private object EmptyAgentPresets : AgentPresetSource {
        override suspend fun agentPresets(): List<AgentPresetInfo> = emptyList()
        override suspend fun agentPresetDocument(id: String): AgentPresetDocument? = null
        override suspend fun removeBundle(name: String) = Unit
    }
}
