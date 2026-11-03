package com.dshm.bot.data.remote

import org.json.JSONArray
import org.json.JSONObject

/**
 * 引擎设置（对齐 dsh-Go `engine_settings_api.dart`）：
 * - `settings/describe` → namespaces[{ns, revision, value, ...}]
 * - `settings/mutate {ns, ops:[{op:'set', path:[...], value}], expectedRevision}`
 */
class DshEngineSettingsRepository(private val client: DshClient) {

    data class Namespace(
        val ns: String,
        val revision: Int,
        val value: JSONObject,
    )

    suspend fun describe(): List<Namespace> {
        val root = client.rpc("settings/describe")
        val array = root.optJSONArray("namespaces") ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val ns = item.optString("ns")
                if (ns.isEmpty()) continue
                add(Namespace(ns, item.optInt("revision", 0), item.optJSONObject("value") ?: JSONObject()))
            }
        }
    }

    data class Plugin(val entryId: String, val moduleName: String, val enabled: Boolean)

    /** `pluginInventory/list`：引擎里已加载的插件条目。 */
    suspend fun plugins(): List<Plugin> {
        val root = client.rpc("pluginInventory/list")
        val array = root.optJSONArray("entries")
            ?: root.optJSONArray("plugins")
            ?: root.optJSONArray("items")
            ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val entryId = item.optString("entryId")
                val moduleName = item.optString("moduleName")
                if (entryId.isEmpty() && moduleName.isEmpty()) continue
                add(Plugin(entryId, moduleName, item.optBoolean("enabled", false)))
            }
        }
    }

    suspend fun setBoolean(namespace: Namespace, path: String, value: Boolean) {
        client.rpc(
            "settings/mutate",
            JSONObject()
                .put("ns", namespace.ns)
                .put(
                    "ops",
                    JSONArray().put(
                        JSONObject()
                            .put("op", "set")
                            .put("path", JSONArray().put(path))
                            .put("value", value),
                    ),
                )
                .put("expectedRevision", namespace.revision),
        )
    }
}

