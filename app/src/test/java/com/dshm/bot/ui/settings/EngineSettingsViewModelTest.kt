package com.dshm.bot.ui.settings

import org.junit.Assert.assertEquals
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

    private companion object {
        const val NOT_CALLED = "<not-called>"
    }
}
