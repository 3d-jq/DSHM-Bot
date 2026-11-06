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
    fun inventorySplitsGlobalPluginsFromPresetEntries() {
        val global = PluginInventoryEntry("include:subagent", "dsh-subagent", true, "active", null)
        val presetRow = PluginInventoryEntry("include:tool-bash", "dsh-tool-bash", true, "active", null)
        val sections = com.dshm.bot.ui.settings.inventorySections(
            entries = listOf(global, presetRow),
            presets = listOf(
                PresetPlugins(
                    id = "standard",
                    name = "标准模式",
                    rows = listOf(presetRow),
                ),
            ),
            globalTitle = "全局插件",
            presetTitleOf = { it.id },
        )
        // 全局插件只留不属于任何预设的条目；预设自带一组。
        assertEquals(2, sections.size)
        assertEquals("全局插件", sections[0].title)
        assertEquals(1, sections[0].entries.size)
        assertEquals("include:subagent", sections[0].entries.first().entryId)
        assertEquals("standard", sections[1].title)
        assertEquals(1, sections[1].entries.size)
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
