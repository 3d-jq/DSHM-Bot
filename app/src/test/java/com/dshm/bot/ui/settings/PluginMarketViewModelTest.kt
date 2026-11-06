package com.dshm.bot.ui.settings

import com.dshm.bot.data.remote.PluginInspection
import com.dshm.bot.data.remote.PluginMarketEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「插件市场」页的纯逻辑：装没装上的判定、拒绝原因、引擎没起来时的报错。
 */
class PluginMarketViewModelTest {

    private fun entry(moduleName: String) = PluginMarketEntry(
        entryId = "include:$moduleName",
        moduleName = moduleName,
        enabled = true,
        fiberPhase = "active",
        meta = null,
    )

    @Test
    fun installedNamesCollectModuleNames() {
        val names = installedNamesOf(listOf(entry("@dsh-go/dsh-weather"), entry("dsh-writer")))
        assertEquals(setOf("@dsh-go/dsh-weather", "dsh-writer"), names)
    }

    @Test
    fun installedNamesIgnoreEmptyModuleNames() {
        val names = installedNamesOf(listOf(entry(""), entry("dsh-writer")))
        assertEquals(setOf("dsh-writer"), names)
    }

    @Test
    fun scopedPluginMatchesByFullName() {
        val installed = setOf("@dsh-go/dsh-weather")
        assertTrue(isMarketPluginInstalled("@dsh-go/dsh-weather", installed))
        assertFalse(isMarketPluginInstalled("@other/dsh-weather", installed))
    }

    @Test
    fun unscopedPluginMatchesHostModuleNameWithoutScope() {
        // 宿主报的是 @scope/pkg，市场里的包名是 pkg —— 同一个包。
        val installed = setOf("@dsh-go/dsh-writer")
        assertTrue(isMarketPluginInstalled("dsh-writer", installed))

        // 完全不相干的包不算装上了。
        assertFalse(isMarketPluginInstalled("dsh-other", installed))
    }

    @Test
    fun refusalTextJoinsProblemAndReason() {
        val text = refusalText(PluginInspection.Refused(problem = "not-found", reason = "404"))
        assertTrue(text.contains("not-found"))
        assertTrue(text.contains("404"))

        // 没有原因时只有问题名。
        assertEquals("already-installed", refusalText(PluginInspection.Refused(problem = "already-installed")))
    }

    @Test
    fun engineNotRunningReportsAnErrorInsteadOfPretendingToWork() {
        val viewModel = PluginMarketViewModel()
        viewModel.refresh()
        assertEquals(PluginMarketError.EngineUnavailable, viewModel.ui.value.error)

        // 没引擎时不能装也不能卸。
        viewModel.install("dsh-writer")
        assertEquals(PluginMarketError.EngineUnavailable, viewModel.ui.value.error)
        viewModel.uninstall("dsh-writer")
        assertEquals(PluginMarketError.EngineUnavailable, viewModel.ui.value.error)
        assertTrue(viewModel.ui.value.busy.isEmpty())
    }
}
