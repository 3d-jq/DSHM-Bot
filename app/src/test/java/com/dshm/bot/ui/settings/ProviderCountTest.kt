package com.dshm.bot.ui.settings

import com.dshm.bot.data.remote.DshEngineSettingsRepository.ListedProvider
import com.dshm.bot.data.remote.ProviderProfile
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** 设置首页的渠道计数必须和模型页显示的卡片数一致。 */
class ProviderCountTest {

    private fun profile(route: String) = ProviderProfile.fromJson(route, JSONObject())

    @Test
    fun twoDeepSeekBuiltInsCountAsOne() {
        val custom = (1..3).map { profile("custom-$it") }
        val listed = listOf(
            ListedProvider("deepseek-official", "DeepSeek Official"),
            ListedProvider("deepseek-account", "DeepSeek Account"),
        )
        assertEquals(4, providerDisplayCount(custom, listed))
    }

    @Test
    fun nonDeepSeekBuiltInCountsSeparately() {
        val listed = listOf(
            ListedProvider("deepseek-official", "DeepSeek"),
            ListedProvider("openai", "OpenAI"),
        )
        assertEquals(2, providerDisplayCount(emptyList(), listed))
    }

    @Test
    fun routeAlsoDefinedAsCustomIsNotDoubleCounted() {
        val custom = listOf(profile("deepseek-official"))
        val listed = listOf(ListedProvider("deepseek-official", "DeepSeek"))
        assertEquals(1, providerDisplayCount(custom, listed))
    }
}
