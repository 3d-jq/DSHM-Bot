package com.dshm.bot.ui.chat

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** schedules 端点（含 create/delete 的返回）→ 本 Bot 任务列表，三处界面共用同一口径。 */
class ScheduleSyncTest {

    private fun raw(vararg tasks: JSONObject) = JSONObject()
        .put("available", true)
        .put("tasks", JSONArray().apply { tasks.forEach { put(it) } })

    private fun task(id: String, botId: String, title: String) = JSONObject()
        .put("id", id).put("sessionId", "s-1").put("botId", botId)
        .put("title", title).put("prompt", "做点什么")

    @Test
    fun createResponseReplacesTheListImmediately() {
        // 模拟「新建任务」后端点直接返回的新列表：只有新建的这一条。
        val created = schedulesOf(raw(task("t2", "bot-1", "晨报")), "bot-1")
        assertEquals(1, created.size)
        assertEquals("t2", created.single().id)
        assertEquals("晨报", created.single().title)
    }

    @Test
    fun deleteResponseDropsTheDeletedTaskImmediately() {
        // 模拟「删掉旧任务」后端点返回的列表：不再含旧的那条。
        val afterDelete = schedulesOf(raw(task("t2", "bot-1", "晨报")), "bot-1")
        assertEquals(emptyList<String>(), afterDelete.map { it.id } - "t2")
        assertEquals(false, afterDelete.any { it.id == "t1" })
    }

    @Test
    fun otherBotsTasksAndMalformedRowsAreIgnored() {
        val result = schedulesOf(
            raw(
                task("t1", "bot-2", "别人的"),
                task("t2", "bot-1", "我的"),
                JSONObject().put("id", "").put("botId", "bot-1"),
            ),
            "bot-1",
        )
        assertEquals(listOf("t2"), result.map { it.id })
    }
}
