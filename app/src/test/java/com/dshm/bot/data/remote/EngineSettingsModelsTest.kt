package com.dshm.bot.data.remote

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineSettingsModelsTest {

    private fun inlineSchema() = SettingsSchemaNode.fromJson(
        JSONObject(
            """
            {
              "type": "object",
              "dict": {
                "enabled": {"type": "boolean", "meta": {"default": true}},
                "name": {"type": "string", "meta": {"description": {"zh": "名称", "en": "Name"}}},
                "mode": {"type": "union", "list": [
                  {"type": "const", "value": "a"},
                  {"type": "const", "value": "b"},
                  {"type": "const", "value": null}
                ]},
                "apiKey": {"type": "string", "meta": {"role": "secret"}},
                "apiKeyEnv": {"type": "string", "meta": {"role": "credential-ref", "default": "DEFAULT_KEY"}},
                "tags": {"type": "array", "inner": {"type": "string"}},
                "count": {"type": "number", "meta": {"min": 1, "max": 10, "step": 1}},
                "nested": {"type": "object", "dict": {"x": {"type": "boolean"}}}
              }
            }
            """.trimIndent(),
        ),
    )

    @Test
    fun inlineSchemaMapsToControls() {
        val schema = inlineSchema()
        val fields = schema.fields()!!
        assertEquals(FieldKind.TOGGLE, fieldKindOf(fields.getValue("enabled")))
        assertEquals(FieldKind.TEXT, fieldKindOf(fields.getValue("name")))
        assertEquals(FieldKind.CHOICE, fieldKindOf(fields.getValue("mode")))
        assertEquals(FieldKind.SECRET, fieldKindOf(fields.getValue("apiKey")))
        assertEquals(FieldKind.HIDDEN, fieldKindOf(fields.getValue("apiKeyEnv")))
        assertEquals(FieldKind.LIST, fieldKindOf(fields.getValue("tags")))
        assertEquals(FieldKind.NUMBER, fieldKindOf(fields.getValue("count")))
        assertEquals(FieldKind.GROUP, fieldKindOf(fields.getValue("nested")))

        val mode = fields.getValue("mode")
        assertEquals(listOf("a", "b"), mode.enumValues)
        assertTrue(mode.nullable)
        assertEquals("名称", fields.getValue("name").meta.description?.resolve("zh-CN"))
        assertEquals("Name", fields.getValue("name").meta.description?.resolve("en"))
    }

    @Test
    fun refsEnvelopeLinksChildren() {
        val schema = SettingsSchemaNode.fromJson(
            JSONObject(
                """
                {
                  "uid": "root",
                  "refs": {
                    "root": {"type": "object", "dict": {"enabled": "n1", "name": "n2"}},
                    "n1": {"type": "boolean"},
                    "n2": {"type": "string"}
                  }
                }
                """.trimIndent(),
            ),
        )
        val fields = schema.fields()!!
        assertEquals(setOf("enabled", "name"), fields.keys)
        assertEquals(FieldKind.TOGGLE, fieldKindOf(fields.getValue("enabled")))
        assertEquals(FieldKind.TEXT, fieldKindOf(fields.getValue("name")))
    }

    @Test
    fun numericUidsAndRolesFromRealSchemastery() {
        val schema = SettingsSchemaNode.fromJson(
            JSONObject(
                """
                {
                  "uid": 42,
                  "refs": {
                    "1": {"type": "string", "meta": {"role": "textarea"}},
                    "3": {"type": "string", "meta": {"role": "secret"}},
                    "13": {"type": "number", "meta": {"step": 0.01, "min": 0, "max": 1, "role": "slider"}},
                    "16": {"type": "const", "meta": {"required": true}, "value": "auto"},
                    "18": {"type": "const", "meta": {"required": true}, "value": "manual"},
                    "20": {"type": "union", "meta": {"role": "radio"}, "list": [16, 18]},
                    "21": {"type": "string", "meta": {}},
                    "22": {"type": "object", "meta": {"default": {}}, "dict": {"name": 21}},
                    "42": {"type": "object", "dict": {
                      "notes": 1,
                      "apiKey": 3,
                      "ratio": 13,
                      "mode": 20,
                      "nested": 22
                    }}
                  }
                }
                """.trimIndent(),
            ),
        )
        val fields = schema.fields()!!
        assertEquals(FieldKind.TEXT, fieldKindOf(fields.getValue("notes")))
        assertEquals(FieldKind.SECRET, fieldKindOf(fields.getValue("apiKey")))
        assertEquals(FieldKind.NUMBER, fieldKindOf(fields.getValue("ratio")))
        assertEquals(FieldKind.CHOICE, fieldKindOf(fields.getValue("mode")))
        assertEquals(listOf("auto", "manual"), fields.getValue("mode").enumValues)
        assertEquals(FieldKind.GROUP, fieldKindOf(fields.getValue("nested")))
        assertEquals(FieldKind.TEXT, fieldKindOf(fields.getValue("nested").fields()!!.getValue("name")))
        assertEquals(FieldUnit.RATIO, unitFor("ratio", fields.getValue("ratio")))
    }

    @Test
    fun namespaceKeepsLayersAndCredentialRef() {
        val raw = JSONObject(
            """
            {
              "ns": "demo",
              "revision": 7,
              "value": {"enabled": true, "apiKeyEnv": "DEMO_KEY", "mode": "a"},
              "base": {"enabled": false},
              "user": {"enabled": true},
              "secrets": [{"path": ["apiKey"], "set": false}],
              "schema": {
                "type": "object",
                "dict": {
                  "enabled": {"type": "boolean"},
                  "apiKey": {"type": "string", "meta": {"role": "secret"}},
                  "apiKeyEnv": {"type": "string", "meta": {"role": "credential-ref"}},
                  "mode": {"type": "union", "list": [
                    {"type": "const", "value": "a"},
                    {"type": "const", "value": "b"}
                  ]}
                }
              }
            }
            """.trimIndent(),
        )
        val namespace = SettingsNamespace.fromJson(raw, writable = true)!!
        assertEquals("demo", namespace.ns)
        assertEquals(7, namespace.revision)
        assertEquals(true, namespace.valueOf(listOf("enabled")))
        assertEquals(false, namespace.baseValueOf(listOf("enabled")))
        assertTrue(namespace.userHas(listOf("enabled")))
        assertFalse(namespace.secretSet(listOf("apiKey")))
        assertEquals("DEMO_KEY", namespace.credentialRefOf(listOf("apiKey")))
        assertEquals("a", namespace.valueOf(listOf("mode")))
        assertEquals(
            namespace.schema,
            schemaAt(namespace.schema, emptyList()),
        )
    }

    @Test
    fun providerSettingsParseAndRemovable() {
        val raw = JSONObject(
            """
            {
              "ns": "llm-pi-ai",
              "revision": 3,
              "value": {"providers": {"my-gw": {
                "displayName": "My GW",
                "api": "openai-completions",
                "baseURL": "https://example.com/v1",
                "apiKeyEnv": "MY_GW_API_KEY",
                "models": [{"id": "model-1"}]
              }}},
              "user": {"providers": {"my-gw": {}}},
              "base": {"providers": {}},
              "schema": {"type": "object", "dict": {}}
            }
            """.trimIndent(),
        )
        val namespace = SettingsNamespace.fromJson(raw, writable = true)!!
        val providers = ProviderSettings.fromNamespace(namespace)
        assertEquals(1, providers.profiles.size)
        val profile = providers.profiles.first()
        assertEquals("my-gw", profile.route)
        assertEquals("My GW", profile.label)
        assertEquals("openai-completions", profile.api)
        assertEquals("https://example.com/v1", profile.baseUrl)
        assertEquals("MY_GW_API_KEY", profile.apiKeyEnv)
        assertEquals(1, profile.models.size)
        assertTrue(providers.removable("my-gw"))
        assertEquals("MY_GW_API_KEY", deriveProviderKeyRef("my-gw"))
        assertTrue(providerRoutePattern.matches("my-gw-2"))
        assertFalse(providerRoutePattern.matches("My-GW"))
    }

    @Test
    fun opsAndJsonEquality() {
        val op = SettingsOp.set(listOf("providers", "my-gw"), JSONObject().put("x", 1)).toJson()
        assertEquals("set", op.getString("op"))
        assertEquals("providers", op.getJSONArray("path").getString(0))
        assertEquals(1, op.getJSONObject("value").getInt("x"))

        val unset = SettingsOp.unset(listOf("a")).toJson()
        assertEquals("unset", unset.getString("op"))
        assertFalse(unset.has("value"))

        assertTrue(jsonEquals(1, 1.0))
        assertTrue(
            jsonEquals(
                JSONObject().put("a", 1).put("b", JSONArray().put("x")),
                JSONObject().put("b", JSONArray().put("x")).put("a", 1.0),
            ),
        )
        assertFalse(jsonEquals(JSONObject().put("a", 1), JSONObject().put("a", 2)))
        assertNull(lookupPath(JSONObject().put("a", 1), listOf("b")).value)
    }
}
