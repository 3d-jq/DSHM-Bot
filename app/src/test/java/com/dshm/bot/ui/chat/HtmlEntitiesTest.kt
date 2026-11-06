package com.dshm.bot.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * HTML 实体解码：模型回复里常带 `&#128075;`、`&amp;` 这类转义，
 * 不解码用户看到的是「在的 &#128075;」而不是「在的 👋」。
 */
class HtmlEntitiesTest {

    @Test
    fun decimalEntitiesAreDecoded() {
        assertEquals("在的 👋", decodeHtmlEntities("在的 &#128075;"))
        assertEquals("😀", decodeHtmlEntities("&#128512;"))
    }

    @Test
    fun hexEntitiesAreDecoded() {
        assertEquals("😀", decodeHtmlEntities("&#x1F600;"))
    }

    @Test
    fun namedEntitiesAreDecoded() {
        assertEquals("A & B", decodeHtmlEntities("A &amp; B"))
        assertEquals("<tag>", decodeHtmlEntities("&lt;tag&gt;"))
        assertEquals("\"引号\"", decodeHtmlEntities("&quot;引号&quot;"))
        assertEquals("it's", decodeHtmlEntities("it&apos;s"))
    }

    @Test
    fun unknownEntitiesAreLeftAlone() {
        assertEquals("100% & more", decodeHtmlEntities("100% & more"))
        assertEquals("&notarealentity;", decodeHtmlEntities("&notarealentity;"))
    }

    @Test
    fun textWithoutEntitiesIsUnchanged() {
        assertEquals("普通文本", decodeHtmlEntities("普通文本"))
        assertEquals("", decodeHtmlEntities(""))
    }
}
