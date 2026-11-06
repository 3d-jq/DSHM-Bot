package com.dshm.bot.ui.settings

import com.dshm.bot.data.remote.FieldMeta
import com.dshm.bot.data.remote.SettingsSchemaNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 插件配置页的字段分层与折叠判定（对齐 dsh-Go `plugin_config_layout.dart`）。
 */
class PluginConfigLayoutTest {

    private fun node(type: String, meta: FieldMeta = FieldMeta()): SettingsSchemaNode {
        val raw = org.json.JSONObject().put("type", type)
        if (meta.required) raw.put("meta", org.json.JSONObject().put("required", true))
        if (meta.hasDefault) {
            raw.put("meta", org.json.JSONObject().put("default", meta.defaultValue ?: 1))
        }
        return SettingsSchemaNode.fromJson(raw)
    }

    @Test
    fun switchesAndChoicesComeFirst() {
        assertEquals(ConfigTier.PRIMARY, configTierOf("enabled", node("boolean")))
        // choice 是「有多个枚举值的 union」，不是普通字符串。
        val choice = SettingsSchemaNode.fromJson(
            org.json.JSONObject().put("type", "union").put(
                "list",
                org.json.JSONArray()
                    .put(org.json.JSONObject().put("type", "const").put("value", "a"))
                    .put(org.json.JSONObject().put("type", "const").put("value", "b")),
            ),
        )
        assertEquals(ConfigTier.PRIMARY, configTierOf("mode", choice))
    }

    @Test
    fun secretsAndRequiredFieldsArePrimary() {
        val secret = SettingsSchemaNode.fromJson(
            org.json.JSONObject().put("type", "string").put("meta", org.json.JSONObject().put("role", "secret")),
        )
        assertEquals(ConfigTier.PRIMARY, configTierOf("apiKey", secret))
        val required = SettingsSchemaNode.fromJson(
            org.json.JSONObject().put("type", "string").put("meta", org.json.JSONObject().put("required", true)),
        )
        assertEquals(ConfigTier.PRIMARY, configTierOf("name", required))
    }

    @Test
    fun requiredWithAWorkingDefaultIsNotPrimary() {
        val required = SettingsSchemaNode.fromJson(
            org.json.JSONObject()
                .put("type", "string")
                .put("meta", org.json.JSONObject().put("required", true).put("default", "x")),
        )
        // 有可用默认值就不算必填，按普通字段处理。
        assertEquals(ConfigTier.REGULAR, configTierOf("name", required))
    }

    @Test
    fun plumbingKeysGoAdvanced() {
        assertTrue(isPlumbingKey("timeoutMs"))
        assertTrue(isPlumbingKey("maxOutputBytes"))
        assertTrue(isPlumbingKey("logLevel"))
        assertTrue(isPlumbingKey("maxDepth"))
        assertFalse(isPlumbingKey("enabled"))
        assertFalse(isPlumbingKey("model"))
    }

    @Test
    fun numbersWithAUnitGoAdvanced() {
        // 以 Ms / Bytes 结尾的数字是「容量类」，折进高级。
        assertEquals(ConfigTier.ADVANCED, configTierOf("timeoutMs", node("number")))
        assertEquals(ConfigTier.ADVANCED, configTierOf("maxOutputBytes", node("number")))
        // max 开头的也是管道类（dsh-Go 同样把它折进高级）。
        assertEquals(ConfigTier.ADVANCED, configTierOf("maxDepth", node("number")))
        // 普通数字留在常规层。
        assertEquals(ConfigTier.REGULAR, configTierOf("depth", node("number")))
    }

    @Test
    fun liveFieldsNeverFold() {
        // 实时生效的字段提到最前，也不折进高级。
        assertEquals(ConfigTier.PRIMARY, configTierOf("timeoutMs", node("number"), live = true))
        assertEquals(ConfigTier.PRIMARY, configTierOf("enabled", node("boolean"), live = true))
    }

    @Test
    fun foldingNeedsEnoughFieldsAndEnoughPlumbing() {
        // 字段不够多不折。
        assertFalse(shouldFoldAdvanced(fieldCount = 5, advancedCount = 4, collapseDeclared = false))
        // 高级字段不够多不折。
        assertFalse(shouldFoldAdvanced(fieldCount = 12, advancedCount = 2, collapseDeclared = false))
        // 都够就折。
        assertTrue(shouldFoldAdvanced(fieldCount = 12, advancedCount = 5, collapseDeclared = false))
        // schema 自己声明了折叠就不折。
        assertFalse(shouldFoldAdvanced(fieldCount = 12, advancedCount = 5, collapseDeclared = true))
    }

    @Test
    fun schemaThatDeclaresCollapseIsLeftAlone() {
        val declared = SettingsSchemaNode.fromJson(
            org.json.JSONObject()
                .put("type", "object")
                .put("meta", org.json.JSONObject().put("collapse", true))
                .put("dict", org.json.JSONObject().put("a", org.json.JSONObject().put("type", "string"))),
        )
        assertTrue(declaresCollapse(declared))
        val plain = SettingsSchemaNode.fromJson(
            org.json.JSONObject()
                .put("type", "object")
                .put("dict", org.json.JSONObject().put("a", org.json.JSONObject().put("type", "string"))),
        )
        assertFalse(declaresCollapse(plain))
    }
}
