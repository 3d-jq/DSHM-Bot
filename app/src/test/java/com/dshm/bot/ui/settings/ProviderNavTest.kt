package com.dshm.bot.ui.settings

import com.dshm.bot.data.remote.ProviderModelSpec
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 渠道页内层导航栈：返回永远「弹一层」，不能跳级。
 *
 * 这里锁住的行为对应 bug「设置深页返回不是上一级」：
 * 详情 → 编辑 → 返回 必须回详情，而不是直接回列表。
 */
class ProviderNavTest {

    private fun spec(id: String) = ProviderModelSpec(
        id = id,
        name = null,
        contextWindow = null,
        maxTokens = null,
        input = emptyList(),
        extra = JSONObject(),
    )

    private fun detail(route: String) = ProviderTarget.Detail(route)

    @Test
    fun emptyStackMeansList() {
        assertNull(ProviderNav.current(ProviderNav.reset()))
    }

    @Test
    fun backOnEmptyStackStaysEmpty() {
        assertTrue(ProviderNav.back(ProviderNav.reset()).isEmpty())
    }

    @Test
    fun backFromEditorOpenedFromDetailReturnsToDetail() {
        var stack = ProviderNav.push(ProviderNav.reset(), detail("gw"))
        stack = ProviderNav.push(stack, ProviderTarget.Editor(null))

        stack = ProviderNav.back(stack)
        assertEquals(detail("gw"), ProviderNav.current(stack))

        stack = ProviderNav.back(stack)
        assertNull(ProviderNav.current(stack))
    }

    @Test
    fun backFromEditorOpenedFromListReturnsToList() {
        var stack = ProviderNav.push(ProviderNav.reset(), ProviderTarget.Editor(null))
        stack = ProviderNav.back(stack)
        assertNull(ProviderNav.current(stack))
    }

    @Test
    fun modelOpenedFromDetailPopsBackToDetail() {
        var stack = ProviderNav.push(ProviderNav.reset(), detail("gw"))
        stack = ProviderNav.push(stack, ProviderTarget.Model("gw", spec("m"), writes = true))

        // ProviderModelSpec 带 JSONObject（没有结构相等性），按字段断言。
        val open = ProviderNav.current(stack) as ProviderTarget.Model
        assertEquals("gw", open.route)
        assertEquals("m", open.model.id)
        assertTrue(open.writes)

        // 详情里改模型：保存成功与返回都落到详情。
        stack = ProviderNav.back(stack)
        assertEquals(detail("gw"), ProviderNav.current(stack))
    }

    @Test
    fun modelOpenedFromEditorPopsBackToEditor() {
        var stack = ProviderNav.push(ProviderNav.reset(), detail("gw"))
        stack = ProviderNav.push(stack, ProviderTarget.Editor(null))
        stack = ProviderNav.push(stack, ProviderTarget.Model("gw", spec("m"), writes = false))

        stack = ProviderNav.back(stack)
        assertEquals(ProviderTarget.Editor(null), ProviderNav.current(stack))
        stack = ProviderNav.back(stack)
        assertEquals(detail("gw"), ProviderNav.current(stack))
    }

    @Test
    fun savingFormGoesBackToList() {
        var stack = ProviderNav.push(ProviderNav.reset(), detail("gw"))
        stack = ProviderNav.push(stack, ProviderTarget.Editor(null))

        // 保存成功落点是列表，不是详情。
        stack = ProviderNav.reset()
        assertTrue(stack.isEmpty())
    }
}
