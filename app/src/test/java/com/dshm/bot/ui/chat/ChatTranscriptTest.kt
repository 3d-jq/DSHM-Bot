package com.dshm.bot.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 「复制整段对话」的 Markdown：条目顺序、角色、附件与事件行。 */
class ChatTranscriptTest {

    @Test
    fun writesHeaderAndTurnsInOrder() {
        val md = conversationMarkdown(
            title = "小助手",
            botName = "小助手",
            entries = listOf(
                ChatEntry(id = "1", kind = ChatEntryKind.USER, text = "帮我看看这个"),
                ChatEntry(id = "2", kind = ChatEntryKind.BOT, text = "好的", author = "小助手"),
            ),
        )
        assertEquals(
            """
            # 小助手

            ## 你

            帮我看看这个

            ## 小助手

            好的
            """.trimIndent() + "\n",
            md,
        )
    }

    @Test
    fun keepsAttachmentsEventsAndArtifacts() {
        val md = conversationMarkdown(
            title = "群聊",
            botName = "大总管",
            entries = listOf(
                ChatEntry(
                    id = "1",
                    kind = ChatEntryKind.USER,
                    text = "",
                    attachmentItems = listOf(ChatAttachmentItem("a.png", "/tmp/a.png", true)),
                    images = 2,
                ),
                ChatEntry(id = "2", kind = ChatEntryKind.EVENT, text = "大总管 做完了"),
                ChatEntry(
                    id = "3",
                    kind = ChatEntryKind.ARTIFACTS,
                    text = "",
                    artifacts = listOf(ChatArtifact("/root/out.pptx", "演示文稿")),
                ),
            ),
        )
        assertTrue(md.contains("- 附件：a.png"))
        assertTrue(md.contains("- 图片 × 2"))
        assertTrue(md.contains("> 大总管 做完了"))
        assertTrue(md.contains("- 文件：/root/out.pptx（演示文稿）"))
    }
}
