package com.dshm.bot.data.remote

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「Agent 预设」的解析与约束（对齐 dsh-Go `features_api.dart`）。
 */
class AgentPresetsTest {

    @Test
    fun presetsAreSortedByOrderAndNullsLast() {
        val raw = JSONObject().put(
            "presets",
            org.json.JSONArray()
                .put(JSONObject().put("id", "custom").put("name", "我的预设"))
                .put(JSONObject().put("id", "standard").put("isDefault", true).put("order", 1))
                .put(JSONObject().put("id", "ptc").put("order", 2)),
        )
        val presets = parseAgentPresets(raw)
        assertEquals(3, presets.size)
        // order 小的在前；没有 order 的排最后。
        assertEquals("standard", presets[0].id)
        assertEquals("ptc", presets[1].id)
        assertEquals("custom", presets[2].id)
        assertTrue(presets[0].isDefault)
        assertEquals("我的预设", presets[2].name)
    }

    @Test
    fun brokenPresetsAreKeptWithTheirReason() {
        val raw = JSONObject().put(
            "presets",
            org.json.JSONArray().put(
                JSONObject().put("id", "broken").put("broken", "boom").put("description", "desc").put("order", 1),
            ),
        )
        val presets = parseAgentPresets(raw)
        assertEquals(1, presets.size)
        assertEquals("boom", presets.first().broken)
        assertEquals("desc", presets.first().description)
    }

    @Test
    fun emptyListIsNotAnError() {
        assertTrue(parseAgentPresets(null).isEmpty())
        assertTrue(parseAgentPresets(JSONObject()).isEmpty())
    }

    @Test
    fun documentKeepsTheRawYaml() {
        val raw = JSONObject()
            .put("agentPreset", "standard")
            .put("content", "- id: preset-standard\n")
            .put("name", "标准模式")
        val document = parseAgentPresetDocument(raw)
        assertEquals("standard", document?.id)
        assertTrue(document?.content?.contains("preset-standard") == true)
        assertEquals("标准模式", document?.name)
        assertNull(parseAgentPresetDocument(null))
    }

    @Test
    fun builtInPresetsCannotBeEditedOrDeleted() {
        assertTrue(BUILT_IN_AGENT_PRESETS.contains("standard"))
        assertTrue(BUILT_IN_AGENT_PRESETS.contains("cordis"))
        assertFalse(BUILT_IN_AGENT_PRESETS.contains("my-preset"))
    }

    @Test
    fun inventoryTitlePrefersTheChineseName() {
        val named = PluginInventoryEntry(
            "include:speech", "dsh-speech", true, "active",
            PluginDisplayMeta.fromJson(JSONObject().put("title", JSONObject().put("zh", "语音识别"))),
        )
        assertEquals("语音识别", inventoryTitleOf(named))
        // 没有中文名 → 用模块短名（去掉 dsh- 前缀）。
        val englishOnly = PluginInventoryEntry(
            "include:tool", "@deepseek-ai/dsh-tool-bash", true, "active",
            PluginDisplayMeta.fromJson(JSONObject().put("title", JSONObject().put("en", "Bash"))),
        )
        assertEquals("tool-bash", inventoryTitleOf(englishOnly))
    }

    @Test
    fun aFileUrlTitleIsNotAName() {
        // 本地路径加载的插件，引擎给的 title 就是一条 file:// 入口路径。
        val local = PluginInventoryEntry(
            "include:local", "dsh-local", true, "active",
            PluginDisplayMeta.fromJson(
                JSONObject().put("title", JSONObject().put("", "file:///opt/dsh/node_modules/ds-bot/index.js")),
            ),
        )
        // 那不是名字：用模块短名，并且它算「系统组件」。
        assertFalse(local.meta?.title?.resolve("zh")?.contains("file://") == false)
        assertEquals("local", inventoryTitleOf(local))
        assertTrue(isSystemEntry(local.meta))
    }

    @Test
    fun moduleNamesAreShortened() {
        assertEquals("tool-bash", shortModuleName("@deepseek-ai/dsh-tool-bash"))
        assertEquals("subagent", shortModuleName("dsh-subagent"))
        assertEquals("include", shortModuleName("cordis:include"))
        assertEquals("ui", shortModuleName("dsh-client-ui"))
    }

    @Test
    fun bundleRowsAreParsedSoWeCanFindTheOwnerOfAPreset() {
        val bundle = parsePluginBundle(
            JSONObject()
                .put("name", "@local/dsh-preset-mine")
                .put("removable", true)
                .put(
                    "rows",
                    org.json.JSONArray().put(
                        JSONObject()
                            .put("rowId", "preset-mine")
                            .put("moduleName", "@deepseek-ai/dsh-agent-preset"),
                    ),
                ),
        )
        assertEquals(1, bundle.rows.size)
        assertEquals("preset-mine", bundle.rows.first().rowId)
        assertEquals("@deepseek-ai/dsh-agent-preset", bundle.rows.first().moduleName)
    }
}
