package com.dshm.bot.service

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.delay
import org.json.JSONObject

/**
 * 列表 collect：从当前屏幕的可滚动列表里，边滚边收集可见条目文本。
 *
 * 对齐 dsh-Go `MainScreenDriver.collect`：每页滚动后重新读屏，
 * 靠「连续两页没有新内容」和页数上限收口。输出是纯文本（工具结果契约是字符串）。
 */
internal object PhoneCollect {

    private const val MAX_PAGES = 30
    private const val MAX_CHARS = 16_000
    private const val SETTLE_MS = 850L

    data class Outcome(val text: String, val items: Int, val pages: Int, val stop: String)

    /**
     * @param findList 返回滚动容器的节点与屏幕范围；null = 没找到。
     * @param readPage 重新读屏，返回「范围内可见元素的文本」。
     * @param nextPage 滚到下一页，返回 false = 到底了。
     */
    suspend fun collect(
        request: JSONObject,
        findList: () -> Rect?,
        readPage: (Rect) -> List<String>,
        nextPage: () -> Boolean,
    ): Outcome {
        val pages = request.optInt("pages", 10).coerceIn(1, MAX_PAGES)
        val until = request.optString("until").trim().takeIf { it.isNotEmpty() }
        val bounds = findList()
            ?: return Outcome("没有找到可滚动的列表；先用 phone_screen 看一下屏幕。", 0, 0, "no-list")

        val collected = LinkedHashMap<String, String>()
        var page = 0
        var blankPages = 0
        var stop = "到底了"
        while (page < pages) {
            if (PhoneControlSession.stopped) { stop = "用户停止"; break }
            val labels = readPage(bounds)
            var added = 0
            for (label in labels) {
                val text = label.trim()
                if (text.isEmpty() || text.length > 200) continue
                if (collected.putIfAbsent(text, text) == null) added += 1
                if (until != null && text.contains(until, ignoreCase = true)) {
                    stop = "命中「$until」"
                    break
                }
            }
            page += 1
            if (until != null && stop.startsWith("命中")) break
            if (collected.values.sumOf { it.length + 4 } >= MAX_CHARS) { stop = "达到输出上限"; break }
            if (added == 0) {
                blankPages += 1
                if (blankPages >= 2) { stop = "没有新内容了"; break }
            } else {
                blankPages = 0
            }
            if (page >= pages) { stop = "达到页数上限"; break }
            if (!nextPage()) { stop = "到底了"; break }
            delay(SETTLE_MS)
        }

        val body = buildString {
            append("收集 ${collected.size} 项 · 共 $page 页 · $stop\n")
            collected.values.forEachIndexed { index, text -> append("${index + 1}. $text\n") }
        }.take(MAX_CHARS)
        return Outcome(body, collected.size, page, stop)
    }
}
