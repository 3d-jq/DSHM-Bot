package com.dshm.bot.ui.chat

import org.json.JSONObject

/**
 * 一条工具事件行：文案 + 目标（对齐 DS Bot `renderCall` 的 prefix / bots）。
 *
 * [target] 是界面认头像用的名字（Bot 或群聊）：`message_bot` 的目标 Bot、
 * `read_group_chat` 的群聊……；认不出来时只显示文字。
 */
data class ToolEventLine(
    val label: String,
    val target: String? = null,
)

private val GROUP_NAME = Regex("""(?:Created group|Updated group|Nothing changed in group) "([^"]+)"""")
private val POST_NAME = Regex("""Posted in "([^"]+)"""")
private val MEMORY_TEAM = Regex("^(team|团队|共享)$", RegexOption.IGNORE_CASE)

/**
 * DS Bot 会画出来的团队级工具事件（`ToolCell` 的 `DRAWN` 集合里，除卡片和群接力外的那些）。
 *
 * 文案按 `renderCall` 的分支走；不是这些工具时返回 null（不画事件行）。
 */
fun toolEventLine(
    tool: String,
    args: JSONObject,
    result: String,
    isError: Boolean,
    selfName: String,
): ToolEventLine? {
    val named = sequenceOf("to", "bot", "name", "group")
        .map { args.optString(it).trim() }
        .firstOrNull { it.isNotEmpty() }
        .orEmpty()
    val target = named.takeIf { it.isNotEmpty() }
    return when (tool) {
        // Messaged / Stopped / Created / Updated + 目标 Bot。
        "message_bot" -> if (isError) {
            ToolEventLine(target?.let { "没能给 $it 发消息" } ?: "没能给其他 Bot 发消息")
        } else {
            ToolEventLine(target?.let { "消息发给了 $it" } ?: "消息发给了其他 Bot", target)
        }

        "stop_bot" -> ToolEventLine(
            if (isError) (target?.let { "没能叫停 $it" } ?: "没能叫停其他 Bot")
            else (target?.let { "叫停了 $it" } ?: "叫停了其他 Bot"),
            target,
        )

        "create_bot" -> ToolEventLine(
            if (isError) "创建 Bot 失败" else "创建了 ${target ?: "Bot"}",
            target?.takeUnless { isError },
        )

        // DS Bot：`arg("bot")` 为空时改的是自己。
        "update_bot" -> {
            val self = named.ifEmpty { selfName }
            ToolEventLine(
                if (isError) "更新 Bot 失败" else "更新了 $self",
                self.takeIf { it.isNotEmpty() }?.takeUnless { isError },
            )
        }

        "create_group" -> {
            val name = GROUP_NAME.find(result)?.groupValues?.getOrNull(1) ?: target
            ToolEventLine(
                if (isError) "创建群聊失败" else "创建了群聊${if (name.isNullOrBlank()) "" else "：$name"}",
                name?.takeUnless { isError },
            )
        }

        "update_group" -> ToolEventLine(
            if (isError) "更新群聊失败" else "更新了群聊",
            target?.takeUnless { isError },
        )

        "delete_group" -> ToolEventLine(if (isError) "删除群聊失败" else "删除了群聊")

        "post_to_group" -> {
            val name = POST_NAME.find(result)?.groupValues?.getOrNull(1) ?: target
            ToolEventLine(
                if (isError) "群发言失败" else "在${if (name.isNullOrBlank()) "群里" else " $name "}发言",
                name?.takeUnless { isError },
            )
        }

        // DS Bot：Read + 群聊头像 + 名字。
        "read_group_chat" -> ToolEventLine(
            if (isError) "没能读群聊" else (target?.let { "阅读了群聊「$it」" } ?: "阅读了群聊"),
            target?.takeUnless { isError },
        )

        "read_own_chat" -> ToolEventLine("翻看了自己的聊天记录")
        "list_bots" -> ToolEventLine("查看了团队")
        "list_secrets" -> ToolEventLine("查看了密钥")

        // 浏览器：Bot 自己开页面、读页面、点按输入 —— 用户要看得见它在操作。
        "browser_open" -> {
            val host = browserHost(args.optString("url")) ?: browserResultHost(result)
            ToolEventLine(
                when {
                    isError -> "没能打开网页"
                    host != null -> "打开了 $host"
                    else -> "打开了网页"
                },
            )
        }

        "read_browser" -> ToolEventLine(if (isError) "没能读网页" else "读了网页")
        "browser_click" -> ToolEventLine(if (isError) "没能点页面上的元素" else "点了页面上的元素")
        "browser_type" -> ToolEventLine(if (isError) "没能填输入框" else "填了网页上的输入框")
        "browser_scroll" -> ToolEventLine(if (isError) "没能滚动网页" else "滚动了网页")
        "browser_back" -> ToolEventLine(if (isError) "网页后退失败" else "网页后退了")
        "browser_forward" -> ToolEventLine(if (isError) "网页前进失败" else "网页前进了")
        "browser_reload" -> ToolEventLine(if (isError) "刷新网页失败" else "刷新了网页")
        "browser_tabs" -> ToolEventLine("看了浏览器标签页")
        "browser_close_tab" -> ToolEventLine(if (isError) "没能关闭标签页" else "关了一个浏览器标签页")
        "browser_hand_over" -> ToolEventLine(if (isError) "没能交接浏览器" else "把浏览器交给了你")

        "remember", "forget" -> memoryEventLine(args, isError, selfName)
        "recall" -> ToolEventLine("查看了记忆")
        else -> null
    }
}

/** 事件行里的站点名：`https://www.example.com/a` → `example.com`。 */
private fun browserHost(raw: String): String? {
    val text = raw.trim()
    if (text.isEmpty() || text.contains(' ')) return null
    val withScheme = if (text.contains("://")) text else "https://$text"
    return runCatching { java.net.URI(withScheme).host }.getOrNull()
        ?.removePrefix("www.")
        ?.takeIf { it.isNotBlank() }
}

/** `browser_open` 的回执里带最终地址（重定向后以它为准）。 */
private fun browserResultHost(result: String): String? = runCatching {
    if (!result.trimStart().startsWith("{")) return@runCatching null
    browserHost(JSONObject(result).optString("url"))
}.getOrNull()

/** DS Bot `MemoryEvent`：团队 / 自己 / 另一个 Bot 的记忆。 */
private fun memoryEventLine(args: JSONObject, isError: Boolean, selfName: String): ToolEventLine {
    if (isError) return ToolEventLine("更新记忆失败")
    val scope = args.optString("scope").trim()
    if (scope.isEmpty()) return ToolEventLine("记忆已更新")
    if (MEMORY_TEAM.matches(scope)) return ToolEventLine("团队记忆已更新")
    if (scope.equals(selfName, ignoreCase = true)) return ToolEventLine("记忆已更新")
    return ToolEventLine("已更新 $scope 的记忆", scope)
}
