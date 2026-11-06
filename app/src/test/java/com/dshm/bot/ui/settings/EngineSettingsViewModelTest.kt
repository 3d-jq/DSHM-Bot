package com.dshm.bot.ui.settings

import com.dshm.bot.data.remote.ProviderModelSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 引擎没起来时，设置页的写操作必须明确报错，不能回调「成功」。 */
class EngineSettingsViewModelTest {

    @Test
    fun saveCredentialReportsErrorWhenEngineIsNotRunning() {
        val viewModel = EngineSettingsViewModel()
        var result: String? = NOT_CALLED

        viewModel.saveCredential("MY_KEY", "secret") { result = it }

        assertEquals("本机引擎未运行", result)
    }

    @Test
    fun clearCredentialReportsErrorWhenEngineIsNotRunning() {
        val viewModel = EngineSettingsViewModel()
        var result: String? = NOT_CALLED

        viewModel.clearCredential("MY_KEY") { result = it }

        assertEquals("本机引擎未运行", result)
    }

    @Test
    fun blankApiKeyIsRejectedBeforeEngineCheck() {
        val viewModel = EngineSettingsViewModel()
        var result: String? = NOT_CALLED

        viewModel.saveCredential("MY_KEY", "   ") { result = it }

        assertEquals("API Key 不能为空", result)
    }

    @Test
    fun savingAProviderModelReportsErrorWhenEngineIsNotRunning() {
        val viewModel = EngineSettingsViewModel()
        var result: String? = NOT_CALLED

        viewModel.saveProviderModel("my-gw", ProviderModelSpec.create("gpt-x")) { result = it }

        assertEquals("本机引擎未运行", result)
    }

    @Test
    fun savingAProviderRejectsBlankModelList() {
        val viewModel = EngineSettingsViewModel()
        var result: String? = NOT_CALLED

        viewModel.saveProvider(
            route = "my-gw",
            displayName = "My GW",
            api = "openai-completions",
            baseUrl = "https://example.com/v1",
            models = emptyList(),
            apiKey = "",
            existing = null,
        ) { result = it }

        assertEquals("至少填写一个模型", result)
    }

    @Test
    fun savingAProviderRejectsInvalidRoute() {
        val viewModel = EngineSettingsViewModel()
        var result: String? = NOT_CALLED

        viewModel.saveProvider(
            route = "My GW",
            displayName = "My GW",
            api = "openai-completions",
            baseUrl = "https://example.com/v1",
            models = listOf(ProviderModelSpec.create("gpt-x")),
            apiKey = "",
            existing = null,
        ) { result = it }

        assertTrue(result!!.startsWith("渠道 ID"))
    }

    private companion object {
        const val NOT_CALLED = "<not-called>"
    }
}
