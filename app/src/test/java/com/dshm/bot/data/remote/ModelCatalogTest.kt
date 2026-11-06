package com.dshm.bot.data.remote

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 模型目录与插件清单的纯逻辑：子代理页挑模型、插件清单的状态与分组。
 */
class ModelCatalogTest {

    private fun catalogJson() = JSONObject(
        """
        {
          "groups": [
            {
              "id": "deepseek-official",
              "name": "DeepSeek",
              "models": [
                {"id": "deepseek-chat", "name": "DeepSeek Chat"},
                {"id": "deepseek-reasoner", "name": "DeepSeek Reasoner", "description": "思考"}
              ]
            },
            {
              "id": "minimax",
              "name": "MiniMax",
              "models": [{"id": "MiniMax-M2.5"}]
            }
          ]
        }
        """.trimIndent(),
    )

    @Test
    fun catalogParsesGroupsAndModels() {
        val catalog = ModelCatalog.fromJson(catalogJson())
        assertEquals(2, catalog.groups.size)
        assertEquals(3, catalog.modelCount)
        val first = catalog.groups.first()
        assertEquals("deepseek-official", first.providerId)
        assertEquals("DeepSeek", first.name)
        assertEquals(2, first.models.size)
        assertEquals("DeepSeek Chat", first.models.first().name)
        // 没给名字就用 id。
        assertEquals("MiniMax-M2.5", catalog.groups[1].models.first().name)
        assertEquals("minimax", catalog.groups[1].models.first().provider)
    }

    @Test
    fun emptyCatalogIsNotAnError() {
        assertEquals(0, ModelCatalog.fromJson(null).modelCount)
        assertEquals(0, ModelCatalog.fromJson(JSONObject()).modelCount)
    }

    @Test
    fun routesRoundTripAndSkipBadItems() {
        val array = JSONArray()
            .put(JSONObject().put("provider", "a").put("model", "m"))
            .put(JSONObject().put("provider", "b"))
            .put("not-an-object")
        val routes = routesOf(array)
        assertEquals(1, routes.size)
        assertEquals(ModelRoute("a", "m"), routes.first())

        val json = routesJson(listOf(ModelRoute("a", "m"), ModelRoute("b", "n")))
        assertEquals(2, json.length())
        assertEquals("a", json.getJSONObject(0).getString("provider"))
        assertEquals("n", json.getJSONObject(1).getString("model"))
    }

    @Test
    fun candidatesGroupByProviderAndKeepUnavailableOnes() {
        val saved = listOf(ModelRoute("gone", "old-model"))
        val groups = modelCandidates(ModelCatalog.fromJson(catalogJson()), saved)
        assertEquals(3, groups.size)
        assertEquals("DeepSeek", groups[0].first)
        assertEquals(2, groups[0].second.size)
        assertTrue(groups[0].second.all { it.available })
        // 已保存但目录里没有的，单独一组。
        assertEquals(SAVED_UNAVAILABLE_GROUP, groups[2].first)
        assertFalse(groups[2].second.first().available)
        assertEquals("old-model", groups[2].second.first().name)
    }

    @Test
    fun inventoryStatusFollowsFiberPhase() {
        assertEquals(InventoryStatus.FAILED, inventoryStatusOf(true, "failed"))
        assertEquals(InventoryStatus.LOADING, inventoryStatusOf(true, "loading"))
        assertEquals(InventoryStatus.LOADING, inventoryStatusOf(true, "unloading"))
        assertEquals(InventoryStatus.WAITING, inventoryStatusOf(true, "pending"))
        assertEquals(InventoryStatus.RUNNING, inventoryStatusOf(true, "active"))
        assertEquals(InventoryStatus.OFF, inventoryStatusOf(false, "active"))
        assertEquals(InventoryStatus.IDLE, inventoryStatusOf(true, null))
    }

    @Test
    fun systemEntriesAreTheOnesWithoutAChineseName() {
        val chinese = PluginDisplayMeta.fromJson(
            JSONObject().put("title", JSONObject().put("zh", "语音识别")),
        )
        val englishOnly = PluginDisplayMeta.fromJson(
            JSONObject().put("title", JSONObject().put("en", "Speech")),
        )
        assertFalse(isSystemEntry(chinese))
        assertTrue(isSystemEntry(englishOnly))
        assertTrue(isSystemEntry(null))
    }

    @Test
    fun inventoryGroupsFailedEntriesFirst() {
        val named = PluginDisplayMeta.fromJson(
            JSONObject().put("title", JSONObject().put("zh", "正常组件")),
        )
        val entries = listOf(
            PluginInventoryEntry("include:ok", "pkg/ok", true, "active", named),
            PluginInventoryEntry("include:bad", "pkg/bad", true, "failed", named),
        )
        val (groups, system) = groupInventory(
            entries = entries,
            groupTitleOf = { it.moduleName },
            failedTitle = "Failed",
            systemTitle = "System",
        )
        // 出错的单独一张卡在最前面，其余按来源分组。
        assertEquals(2, groups.size)
        assertEquals("Failed", groups.first().title)
        assertEquals("pkg/bad", groups.first().entries.first().moduleName)
        // 有中文名的不是系统组件。
        assertTrue(system.isEmpty())
    }

    @Test
    fun entriesWithoutAChineseNameAreSystemComponents() {
        val entries = listOf(
            PluginInventoryEntry("include:plain", "pkg/plain", true, "active", null),
        )
        val (_, system) = groupInventory(
            entries = entries,
            groupTitleOf = { it.moduleName },
            failedTitle = "Failed",
            systemTitle = "System",
        )
        assertEquals(1, system.size)
    }
}
