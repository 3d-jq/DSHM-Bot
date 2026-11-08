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

        // 「看了一眼」类的动作（看团队 / 看密钥 / 翻自己的记录）、浏览器动作、
        // 更新记忆都不进对话：活动状态栏已经在显示，多一行只会把对话冲乱。
        else -> null
    }
}

