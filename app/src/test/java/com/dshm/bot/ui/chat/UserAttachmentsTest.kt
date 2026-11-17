package com.dshm.bot.ui.chat

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * user 行 blocks → 附件项。
 *
 * 锁住两件事（真机 2026-11-16 的问题就是这两条丢了）：
 * 1. **按 `type` 认图片** —— 只按扩展名/名字的话 HEIC 之类会被画成文件卡；
 * 2. **整份 attachment ref 要带上** —— 客户端要靠它回宿主读字节（缩略图 / 点开文件）。
 */
class UserAttachmentsTest {

    private fun imageBlock(name: String, id: String = "sha256:aa"): JSONObject = JSONObject()
        .put("type", "image")
        .put("name", name)
        .put("attachment", JSONObject().put("name", name).put("attachmentId", id).put("mediaType", "image/png"))

    private fun fileBlock(name: String, id: String = "sha256:bb"): JSONObject = JSONObject()
        .put("type", "file")
        .put("name", name)
        .put("attachment", JSONObject().put("name", name).put("attachmentId", id))

    @Test
    fun imagesAreRecognisedByTypeAndKeepTheirRef() {
        val item = attachmentItemsOfBlocks(listOf(imageBlock("1000027794.heic"))).single()
        assertTrue("type=image 必须是图片（HEIC 也一样）", item.isImage)
        assertEquals("1000027794.heic", item.name)
        assertEquals("sha256:aa", item.attachmentId)
        assertTrue("ref 要原样带上，宿主按它校验", item.rawAttachment!!.contains("sha256:aa"))
        assertEquals("还没有字节时 path 为空，UI 先画占位", "", item.path)
    }

    @Test
    fun documentsStayFiles() {
        val item = attachmentItemsOfBlocks(listOf(fileBlock("需求.docx"))).single()
        assertFalse(item.isImage)
        assertEquals("sha256:bb", item.attachmentId)
    }

    @Test
    fun aNameOnlyImageFallsBackToItsExtension() {
        // 事件里没有 type 的老数据：按扩展名认。
        val block = JSONObject().put("name", "照片.JPG")
        val item = attachmentItemsOfBlocks(listOf(block)).single()
        assertTrue(item.isImage)
        assertNull("没有 ref 就没法回宿主取字节", item.attachmentId)
    }

    @Test
    fun offloadedImagesStillShowTheirThumbnail() {
        // `offloaded` 只是「不再喂给模型」：ref 还在、字节也读得到，客户端照样画缩略图。
        // 早先按它跳过 → 老图全变成一张点不开的图标（真机 2026-11-17）。
        val block = imageBlock("old.png").put("offloaded", true)
        val item = attachmentItemsOfBlocks(listOf(block)).single()
        assertTrue(item.isImage)
        assertEquals("sha256:aa", item.attachmentId)
    }

    /**
     * 进程重启后 `pendingEchoAttachments`（内存）没了，只剩落盘的
     * `sentAttachments[requestId]`：自己发的图必须还能用那份本地副本画缩略图
     * （真机 2026-11-17：重启后自己发的图变成一张点不开的图标）。
     */
    @Test
    fun aPersistedLocalCopyIsMergedBackByIndexOrName() {
        val blocks = listOf(imageBlock("a.png"), fileBlock("b.pdf"))
        val items = attachmentItemsOfBlocks(blocks)
        val local = listOf(
            ChatAttachmentItem("a.png", "/files/a.png", isImage = true),
            ChatAttachmentItem("b.pdf", "/files/b.pdf", isImage = false),
        )
        val merged = mergeLocalAttachmentPaths(items, local)
        assertEquals("/files/a.png", merged[0].path)
        assertEquals("/files/b.pdf", merged[1].path)
        // ref 不能被本地那份挤掉（id 还得留着，将来要回宿主取字节）。
        assertEquals("sha256:aa", merged[0].attachmentId)
        assertTrue(merged[0].isImage)

        // 名字对不上、数量也对不上时**不猜**（宁可没有缩略图，也不能把别的字节贴上去）。
        val renamed = mergeLocalAttachmentPaths(items, listOf(ChatAttachmentItem("x", "/files/x", true)))
        assertEquals("", renamed[0].path)
        assertEquals("", renamed[1].path)

        // 数量一致（发送时 blocks 与 draft 同序）时按下标兜底。
        val sameCount = mergeLocalAttachmentPaths(
            items,
            listOf(ChatAttachmentItem("x", "/files/x", true), ChatAttachmentItem("y", "/files/y", false)),
        )
        assertEquals("/files/x", sameCount[0].path)
        assertEquals("/files/y", sameCount[1].path)
    }

    @Test
    fun aFullLocalPathWinsOverNothingToMerge() {
        val items = attachmentItemsOfBlocks(listOf(imageBlock("a.png")))
        assertEquals("", items.single().path)
        assertSame(items, mergeLocalAttachmentPaths(items, emptyList()))
    }

    @Test
    fun anAlreadyDownloadedAttachmentReusesTheCachedPath() {
        val cached = ChatAttachmentItem("a.png", "/tmp/a.png", isImage = true, attachmentId = "sha256:aa")
        val item = attachmentItemsOfBlocks(listOf(imageBlock("a.png"))) { id ->
            if (id == "sha256:aa") cached else null
        }.single()
        assertEquals("/tmp/a.png", item.path)
    }
}
