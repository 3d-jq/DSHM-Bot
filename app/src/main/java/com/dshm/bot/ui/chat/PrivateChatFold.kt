package com.dshm.bot.ui.chat

import com.dshm.bot.data.remote.SessionEvent
import org.json.JSONArray

/**
 * 四期 4b：客户端折叠私聊的 **durable 气泡行**（先只做 user / bot 的最小子集）。
 *
 * 参照实现是宿主 `parts.js` 的 `displayItems`（官方 DSH 行的复刻）：
 * - `turn/start` 推进当前轮号（`turn` 打在该轮后续的行上）；
 * - `assistant/message` **按文本块各出一行**——空白块不出行；
 * - `data.turn` 被 `exchangeTurns` 标记的轮（Bot 之间互答）整轮不画；
 * - `user/message` 只画 `source.kind === "user"`，文本是各 text 块 `\n` 连接后 trim，
 *   `images` 是 image 块计数，`rpcId` 是本地乐观行的认领键。
 *
 * 纯函数、无 Android 依赖。**4b 不接线到渲染**：先用 oracle 差分证明「与宿主逐行
 * 一致」（`PrivateChatFoldTest`，期望值由 `test/gen-display-items.mjs` 生成），再在
 * 4c 切换。这样「地基硬」是自证的，不是靠肉眼。
 */
data class FoldRow(
    /** `user` | `bot`（4b 子集；卡片/工具行在下一批扩）。 */
    val kind: String,
    val text: String,
    val seq: Long?,
    val turn: Int?,
    val images: Int = 0,
    /** 本地乐观 echo 行的认领键（`user/message.source.rpcId`）。 */
    val requestId: String? = null,
    val timeMs: Long? = null,
)

/** `textOf` 的 Kotlin 版（`src/host/text.js`）：text 块按 `\n` 连接后 trim。 */
internal fun foldTextOf(content: JSONArray?): String {
    if (content == null) return ""
    val parts = ArrayList<String>(content.length())
    for (index in 0 until content.length()) {
        val block = content.optJSONObject(index) ?: continue
        if (block.optString("type") != "text") continue
        if (!block.has("text") || block.isNull("text")) continue
        parts += block.optString("text")
    }
    return parts.joinToString("\n").trim()
}

/**
 * 折叠一段**连续**的 durable 事件（`SessionEventLog.eventsIn` 的结果）为气泡行。
 *
 * @param exchangeTurns 被隐藏的轮号（Bot 之间互答的轮，宿主 `state.exchangeTurns`）。
 */
fun foldPrivateChat(events: List<SessionEvent>, exchangeTurns: Set<Int> = emptySet()): List<FoldRow> {
    val rows = ArrayList<FoldRow>()
    var turn: Int? = null
    for (event in events) {
        val data = event.data
        when (event.type) {
            "turn/start" -> {
                val next = data?.optInt("turn", 0) ?: 0
                if (next > 0) turn = next
            }

            "assistant/message" -> {
                val eventTurn = data?.optInt("turn", 0) ?: 0
                if (eventTurn > 0 && eventTurn in exchangeTurns) continue
                val content = data?.optJSONObject("message")?.optJSONArray("content") ?: continue
                for (index in 0 until content.length()) {
                    val block = content.optJSONObject(index) ?: continue
                    if (block.optString("type") != "text") continue
                    val text = block.optString("text")
                    if (text.isBlank()) continue
                    rows += FoldRow(kind = "bot", text = text, seq = event.seq, turn = turn, timeMs = event.timeMs)
                }
            }

            "user/message" -> {
                val source = data?.optJSONObject("source")
                if (source?.optString("kind") != "user") continue
                val content = data?.optJSONArray("content")
                var images = 0
                if (content != null) {
                    for (index in 0 until content.length()) {
                        val block = content.optJSONObject(index) ?: continue
                        if (block.optString("type") == "image") images += 1
                    }
                }
                rows += FoldRow(
                    kind = "user",
                    text = foldTextOf(content),
                    seq = event.seq,
                    turn = turn,
                    images = images,
                    requestId = source.optString("rpcId").takeIf { it.isNotEmpty() },
                    timeMs = event.timeMs,
                )
            }
        }
    }
    return rows
}
