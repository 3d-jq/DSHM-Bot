package com.dshm.bot.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 模型容量的显示与解析，对齐 dsh-Go `providers_state.dart` 的
 * `formatTokens` / `parseTokenCount`。
 */
class ProviderTokensTest {

    @Test
    fun formatsCapacities() {
        assertEquals("256K", formatTokens(262144))
        assertEquals("128K", formatTokens(131072))
        assertEquals("32K", formatTokens(32768))
        // 1000 进制（厂商报价习惯）也认。
        assertEquals("200K", formatTokens(200000))
        assertEquals("1M", formatTokens(1000000))
        // 不是整数单位就原样返回。
        assertEquals("12345", formatTokens(12345))
    }

    @Test
    fun parsesTypedCapacities() {
        assertEquals(131072, parseTokenCount("131072"))
        // K 是 1000、M 是 1000000（不是 1024）。
        assertEquals(128000, parseTokenCount("128K"))
        assertEquals(128000, parseTokenCount("128k"))
        assertEquals(1500000, parseTokenCount("1.5M"))
        // 分隔符和空格忽略。
        assertEquals(200000, parseTokenCount(" 200,000 "))
        assertEquals(200000, parseTokenCount("200_000"))
    }

    @Test
    fun rejectsInvalidCapacities() {
        assertNull(parseTokenCount(""))
        assertNull(parseTokenCount("abc"))
        assertNull(parseTokenCount("128kk"))
        // 不是整数。
        assertNull(parseTokenCount("1.5"))
        // 必须是正数。
        assertNull(parseTokenCount("0"))
    }

    @Test
    fun validatesInput() {
        // 空是合法的：表示回到渠道默认值。
        assertTrue(isValidTokenCount(""))
        assertTrue(isValidTokenCount("   "))
        assertTrue(isValidTokenCount("128K"))
        assertFalse(isValidTokenCount("128kk"))
        assertFalse(isValidTokenCount("1.5"))
    }
}
