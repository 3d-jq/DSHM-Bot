package com.dshm.bot.data.remote

import com.dshm.bot.data.remote.DshAccountRepository.Phase
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 账号 wire 解析：对齐 dsh-Go account_api.dart 的 AccountView / SignInAttemptView。 */
class DshAccountRepositoryTest {

    private fun view(json: String) = DshAccountRepository.parseSnapshot(JSONObject(json))

    @Test
    fun signedOutViewHasNoAttempt() {
        val snapshot = view("""{"status":"signed-out"}""")
        assertFalse(snapshot.signedIn)
        assertNull(snapshot.attemptId)
        assertNull(snapshot.phase)
        assertNull(snapshot.authorizeUrl)
        assertNull(snapshot.usageUrl)
    }

    @Test
    fun credentialStoredIsSignedInAndCarriesLinks() {
        val snapshot = view(
            """
            {
              "status": "credential-stored",
              "links": {"usageUrl": "https://platform.deepseek.com/usage", "topUpUrl": "https://platform.deepseek.com/topup"}
            }
            """.trimIndent(),
        )
        assertTrue(snapshot.signedIn)
        assertEquals("https://platform.deepseek.com/usage", snapshot.usageUrl)
        assertEquals("https://platform.deepseek.com/topup", snapshot.topUpUrl)
    }

    @Test
    fun waitingBrowserAttemptExposesAuthorizeUrlAndDeadline() {
        val snapshot = view(
            """
            {
              "status": "signed-out",
              "attempt": {
                "id": "att-1",
                "phase": "waiting-browser",
                "authorizeUrl": "https://platform.deepseek.com/oauth?state=x",
                "expiresAt": 1700000000000
              }
            }
            """.trimIndent(),
        )
        assertEquals("att-1", snapshot.attemptId)
        assertEquals(Phase.WAITING_BROWSER, snapshot.phase)
        assertEquals("https://platform.deepseek.com/oauth?state=x", snapshot.authorizeUrl)
        assertEquals(1700000000000L, snapshot.expiresAt)
        assertFalse(snapshot.phase!!.terminal)
    }

    @Test
    fun failedAttemptCarriesErrorCodeAndIsTerminal() {
        val snapshot = view(
            """
            {"status":"signed-out","attempt":{"id":"att-2","phase":"failed","errorCode":"no-response"}}
            """.trimIndent(),
        )
        assertEquals(Phase.FAILED, snapshot.phase)
        assertEquals("no-response", snapshot.errorCode)
        assertTrue(snapshot.phase!!.terminal)
        assertNull(snapshot.expiresAt)
    }

    @Test
    fun phaseWireValuesMatchDshGo() {
        assertEquals(Phase.INITIALIZING, Phase.fromWire("initializing"))
        assertEquals(Phase.WAITING_BROWSER, Phase.fromWire("waiting-browser"))
        assertEquals(Phase.EXCHANGING, Phase.fromWire("exchanging"))
        assertEquals(Phase.COMMITTING, Phase.fromWire("committing"))
        assertEquals(Phase.SUCCEEDED, Phase.fromWire("succeeded"))
        assertEquals(Phase.CANCELLED, Phase.fromWire("cancelled"))
        assertEquals(Phase.EXPIRED, Phase.fromWire("expired"))
        assertEquals(Phase.FAILED, Phase.fromWire("failed"))
        assertEquals(Phase.UNKNOWN, Phase.fromWire("brand-new-phase"))
        assertNull(Phase.fromWire(null))
        assertTrue(Phase.SUCCEEDED.terminal)
        assertFalse(Phase.EXCHANGING.terminal)
    }

    @Test
    fun missingOrEmptyFieldsDegradeSafely() {
        val empty = view("{}")
        assertFalse(empty.signedIn)
        assertNull(empty.phase)
        assertNull(empty.errorCode)

        val blank = view("""{"attempt":{"id":"","phase":"","authorizeUrl":"","errorCode":""}}""")
        assertNull(blank.attemptId)
        assertNull(blank.phase)
        assertNull(blank.authorizeUrl)
        assertNull(blank.errorCode)
    }

    @Test
    fun loginSourceIsAlwaysWeb() {
        // 手机是外部浏览器 / Custom Tab，Host 需要渲染可见的完成页。
        assertEquals("web", DshAccountRepository.LOGIN_SOURCE_WEB)
    }

    @Test
    fun balanceSeparatesRechargeAndBonusWallets() {
        val balance = DshAccountRepository.parseBalance(
            JSONObject(
                """
                {
                  "status": "ready",
                  "value": [{"balance": "12.5", "currency": "CNY"}],
                  "bonusWallets": [{"balance": "3", "currency": "CNY"}]
                }
                """.trimIndent(),
            ),
        )
        assertEquals("¥12.50", balance.recharge)
        assertEquals("¥3.00", balance.bonus)
    }

    @Test
    fun missingBonusWalletsIsNullNotZero() {
        val balance = DshAccountRepository.parseBalance(
            JSONObject("""{"status":"ready","value":[{"balance":"8","currency":"USD"}]}"""),
        )
        assertEquals("8.00 USD", balance.recharge)
        assertNull(balance.bonus)
    }

    @Test
    fun nonReadyOrEmptyBalanceHasNoAmounts() {
        assertNull(DshAccountRepository.parseBalance(JSONObject("""{"status":"failed"}""")).recharge)
        val empty = DshAccountRepository.parseBalance(JSONObject("""{"status":"ready"}"""))
        assertNull(empty.recharge)
        assertNull(empty.bonus)
    }
}
