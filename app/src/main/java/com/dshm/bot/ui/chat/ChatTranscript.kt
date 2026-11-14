package com.dshm.bot.ui.chat

/**
 * 把这次会话整理成 Markdown（对齐网页版详情抽屉里的「Copy conversation as Markdown」）。
 *
 * 网页版读的是 DSH 的 session 投影（`transcriptOf`）；我们这边按界面上已有的条目生成，
 * 所以内容是「你看到的这段」，包含工具事件、产物卡和页面缩略图。
 */
fun conversationMarkdown(
    title: String,
    botName: String,
    entries: List<ChatEntry>,
    timeLabel: (Long) -> String? = { null },
): String {
    val out = StringBuilder()
    out.append("# ").append(title).append("\n\n")
    entries.forEach { entry ->
        when (entry.kind) {
            ChatEntryKind.USER -> {
                out.append("## 你\n\n")
                if (entry.text.isNotBlank()) out.append(entry.text).append("\n\n")
                entry.attachmentItems.forEach { item ->
                    out.append("- 附件：").append(item.name).append('\n')
                }
                if (entry.images > 0) out.append("- 图片 × ").append(entry.images).append('\n')
                if (entry.attachmentItems.isNotEmpty() || entry.images > 0) out.append('\n')
            }

            ChatEntryKind.BOT -> {
                out.append("## ").append(entry.author ?: botName).append("\n\n")
                if (entry.text.isNotBlank()) out.append(entry.text).append("\n\n")
                entry.whole?.let { post ->
                    out.append("- 长文附件：").append(post.name).append('\n').append('\n')
                }
            }

            ChatEntryKind.EVENT -> {
                if (entry.text.isNotBlank()) out.append("> ").append(entry.text).append("\n\n")
            }

            ChatEntryKind.ARTIFACTS -> entry.artifacts.forEach { file ->
                out.append("- 文件：").append(file.path)
                file.description?.takeIf { it.isNotBlank() }?.let { out.append("（").append(it).append("）") }
                out.append('\n')
            }.also { if (entry.artifacts.isNotEmpty()) out.append('\n') }

            ChatEntryKind.DIVIDER -> {
                out.append("---\n")
                timeLabel(entry.timeMs ?: 0L)?.let { label -> out.append(label).append('\n') }
                out.append('\n')
            }

            ChatEntryKind.ANSWERED -> entry.answered.forEach { question ->
                out.append("> ").append(question.question).append('\n')
                question.answers.forEach { answer -> out.append("> - ").append(answer).append('\n') }
                out.append('\n')
            }

            ChatEntryKind.VIEW -> {
                out.append("> 视图：").append(entry.view?.title ?: "视图").append("\n\n")
            }

            ChatEntryKind.BROWSER_SHOT -> entry.shot?.let { shot ->
                out.append("> 浏览器：").append(shot.title.ifBlank { shot.url }).append('\n')
                out.append("> ").append(shot.url).append("\n\n")
            }

            ChatEntryKind.PENDING -> {
                out.append("> ").append(
                    if (entry.pendingSlot == "next-step") "插话中" else "排队中",
                ).append("：").append(entry.text).append("\n\n")
            }
        }
    }
    return out.toString().trimEnd() + "\n"
}
