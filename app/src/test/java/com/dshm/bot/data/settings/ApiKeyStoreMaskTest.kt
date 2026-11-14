package com.dshm.bot.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** [ApiKeyStore.mask] 的脱敏规则（ESP / KeyStore 路径要仪器测试，这里锁纯逻辑）。 */
class ApiKeyStoreMaskTest {

    @Test
    fun `空串原样返回`() {
        assertEquals("", ApiKeyStore.mask(""))
    }

    @Test
    fun `短 Key 整串打码`() {
        assertEquals("••••", ApiKeyStore.mask("sk-123"))
        assertEquals("••••", ApiKeyStore.mask("1234567890"))
    }

    @Test
    fun `长 Key 保留前 4 后 4`() {
        assertEquals("sk-1" + "…" + "cdef", ApiKeyStore.mask("sk-1234567890abcdef"))
    }

    @Test
    fun `边界长度 11 以上走前后保留`() {
        assertEquals("abcd" + "…" + "ijkl", ApiKeyStore.mask("abcdefghijkl"))
    }

    @Test
    fun `脱敏结果不包含中间原文`() {
        val masked = ApiKeyStore.mask("very-secret-middle-part-token")
        assertFalse(masked.contains("secret"))
        assertFalse(masked.contains("middle"))
    }
}
