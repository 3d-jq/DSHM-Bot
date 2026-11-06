package com.dshm.bot.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「哪些 namespace 算插件设置」（对齐 dsh-Go 的 curatedNamespaces /
 * internalNamespaces / PluginConfigScope）。
 *
 * 重点：本机 rootfs 里预装了网页客户端 dsh-go-bridge 与 dsh-app，
 * 它们的私有设置在引擎里也是 namespace，但对手机端没意义，
 * 不能被当插件列出来。
 */
class PluginConfigScopeTest {

    @Test
    fun curatedAndInternalNamespacesAreNeverPlugins() {
        // App 自己有专门页的。
        assertTrue(PluginConfigScope.isInternal("subagent"))
        assertTrue(PluginConfigScope.isInternal("agent-loop"))
        assertTrue(PluginConfigScope.isInternal("ui-settings"))
        // 网页客户端 dsh-go-bridge 的私有设置。
        assertTrue(PluginConfigScope.isInternal("ui-theme"))
        assertTrue(PluginConfigScope.isInternal("ui-settings-account"))
        assertTrue(PluginConfigScope.isInternal("ui-settings-general"))
        assertTrue(PluginConfigScope.isInternal("llm-pi-ai"))
        assertTrue(PluginConfigScope.isInternal("llm-deepseek"))
        assertTrue(PluginConfigScope.isInternal("llm-deepseek-account"))
        // 引擎自己的管道。
        assertTrue(PluginConfigScope.isInternal("permission"))
        assertTrue(PluginConfigScope.isInternal("agent-preset-registry"))
        assertTrue(PluginConfigScope.isInternal("locale"))
        // 真正属于插件的。
        assertFalse(PluginConfigScope.isInternal("dsh-tool-bash"))
    }

    @Test
    fun onlyRowsDeclaredByAnEnabledBundleArePlugins() {
        val declared = PluginConfigScope.declaredRows(listOf(listOf("dsh-tool-bash", "my-tool")))
        assertTrue(PluginConfigScope.isPluginNamespace("dsh-tool-bash", declared))
        // 引擎里有、但没有包声明它 —— 不是插件设置。
        assertFalse(PluginConfigScope.isPluginNamespace("never-declared", declared))
        // 内部 namespace 即便被声明了也不算。
        assertFalse(PluginConfigScope.isPluginNamespace("ui-theme", declared))
    }

    @Test
    fun aRowDeclaredTwiceIsNobodyS() {
        // 同一个 rowId 被两个包声明：引擎也无法寻址它（ambiguous-id）。
        val declared = PluginConfigScope.declaredRows(listOf(listOf("shared"), listOf("shared")))
        assertFalse(PluginConfigScope.isPluginNamespace("shared", declared))
    }

    @Test
    fun disabledBundlesDoNotDeclareAnything() {
        // 传进来的就是「已启用的包」的 row，停用的包不算。
        val declared = PluginConfigScope.declaredRows(emptyList())
        assertFalse(PluginConfigScope.isPluginNamespace("dsh-tool-bash", declared))
    }
}
