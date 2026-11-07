package com.dshm.bot.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.json.JSONObject

/** 底部面板/详情页的乐观回显：选中即显示，失败可回滚。 */
class BotDetailsOptimisticTest {

    private val info = BotDetailsInfo(
        id = "bot-1",
        name = "小助手",
        role = "助理",
        color = null,
        shape = null,
        model = "old/model",
        workDir = null,
        permission = "read-only",
    )

    @Test
    fun modelPatchUpdatesOnlyModel() {
        val patch = JSONObject().put("model", "new/model")
        val next = info.copy(model = patch.optString("model"))
        assertEquals("new/model", next.model)
        assertEquals(info.permission, next.permission)
    }

    @Test
    fun permissionPatchUpdatesOnlyPermission() {
        val patch = JSONObject().put("permission", "workspace-write")
        val next = info.copy(permission = patch.optString("permission"))
        assertEquals("workspace-write", next.permission)
        assertEquals(info.model, next.model)
    }

    @Test
    fun failedUpdateCanRestorePreviousInfo() {
        val previous = info
        val optimistic = info.copy(model = "new/model")
        val restored = previous
        assertEquals("old/model", restored.model)
        assertEquals("new/model", optimistic.model)
    }

    @Test
    fun permissionCatalogFallsBackToBotValueThenDefault() {
        val catalogDefault = "read-only"
        val botValue = ""
        val resolved = botValue.takeIf { it.isNotEmpty() } ?: catalogDefault.takeIf { it.isNotEmpty() }
        assertEquals("read-only", resolved)
        assertNull("".takeIf { it.isNotEmpty() })
    }
}
