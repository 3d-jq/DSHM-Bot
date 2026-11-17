package com.dshm.bot.data.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** 引擎日志会直接展示给用户并允许复制，密钥绝不能漏出去。 */
class TextMaskingTest {

    @Test
    fun masksStartupTokenInUrl() {
        val line = "dsh web: http://127.0.0.1:3080/?token=AbC-123_xyz"
        assertEquals("dsh web: http://127.0.0.1:3080/?token=***", TextMasking.maskSecrets(line))
    }

    @Test
    fun masksApiKeysRegardlessOfSpellingOrCase() {
        assertEquals("API_KEY=***", TextMasking.maskSecrets("API_KEY=sk-abc.123"))
        assertEquals("api-key: ***", TextMasking.maskSecrets("api-key: sk_abc-123"))
        assertEquals("apiKey = ***", TextMasking.maskSecrets("apiKey = ABCDef01"))
    }

    @Test
    fun stripsAnsiEscapeSequences() {
        assertEquals("red green", TextMasking.maskSecrets("\u001B[31mred\u001B[0m green"))
    }

    @Test
    fun masksEverySecretInOnePass() {
        val raw = "\u001B[32mready\u001B[0m /?token=T0k_123 api_key: sk-live-999"
        val masked = TextMasking.maskSecrets(raw)
        assertEquals("ready /?token=*** api_key: ***", masked)
        assertFalse(masked.contains("T0k_123"))
        assertFalse(masked.contains("sk-live-999"))
        assertFalse(masked.contains("\u001B"))
    }

    @Test
    fun masksBareDeepSeekKeysAndBearerHeaders() {
        // 这两个是外部 review 指出的漏网形态：裸的 sk-… 与 Authorization 头。
        // 用明显是假的值（`dshm-test-` 前缀），只验证掩码形状。
        assertEquals("key sk-*** end", TextMasking.maskSecrets("key sk-dshm-test-only-0001 end"))
        assertEquals(
            "Authorization: Bearer ***",
            TextMasking.maskSecrets("Authorization: Bearer dshm-test-token-ab"),
        )
        val masked = TextMasking.maskSecrets("Bearer dshm-test-token-ab sk-dshm-test-only-0001")
        assertEquals("Bearer *** sk-***", masked)
        assertFalse(masked.contains("dshm-test-token-ab"))
        assertFalse(masked.contains("sk-dshm-test-only-0001"))
    }

    @Test
    fun leavesUnrelatedAndEmptyTextAlone() {
        assertEquals("", TextMasking.maskSecrets(""))
        assertEquals("starting proot…", TextMasking.maskSecrets("starting proot…"))
        assertEquals("token=***", TextMasking.maskSecrets("token=***"))
    }
}
