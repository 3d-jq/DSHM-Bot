package com.dshm.bot.data.remote

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.3.6 连接器视图的解析护栏。
 *
 * 0.3.6 前 `connector-*` 返回裸数组，0.3.6 起返回 `{connectors, mail}` 对象；
 * 客户端旧版按数组解析会把整页读空。这里锁住两种形状都能解析、字段不丢、
 * 坏数据不炸。
 */
class ConnectorModelsTest {

    @Test
    fun parsesThe036ObjectWithConnectorsAndMail() {
        val raw = JSONObject()
            .put(
                "connectors",
                JSONArray().put(
                    JSONObject()
                        .put("id", "github")
                        .put("name", "GitHub")
                        .put("category", "dev")
                        .put("about", "Repositories, issues, and pull requests")
                        .put("state", "ready")
                        .put("account", "octocat")
                        .put("auth", JSONObject().put("kind", "oauth").put("token", true)
                            .put("client", JSONObject().put("id", "Ov23li")))
                        .put("tools", 24)
                        .put("readOnly", false)
                        .put("offFor", JSONArray().put("bot-2")),
                ).put(
                    JSONObject()
                        .put("id", "notion")
                        .put("name", "Notion")
                        .put("state", "off")
                        .put("auth", JSONObject().put("kind", "oauth")),
                ),
            )
            .put(
                "mail",
                JSONObject()
                    .put("google", true)
                    .put("accounts", JSONArray().put(
                        JSONObject().put("id", "m1").put("address", "a@qq.com")
                            .put("provider", "qq").put("auth", "password").put("state", "ready"),
                    )),
            )

        val view = parseConnectorsView(raw)
        assertEquals(2, view.connectors.size)
        val github = view.connectors.first { it.id == "github" }
        assertTrue(github.connected)
        assertEquals("octocat", github.account)
        assertEquals(ConnectorAuthKind.OAUTH, github.auth)
        assertTrue(github.authToken)
        assertEquals("Ov23li", github.authClientId)
        assertEquals(24, github.tools)
        assertEquals(false, github.readOnly)
        assertEquals(listOf("bot-2"), github.offFor)
        assertTrue(view.mail.google)
        assertEquals("a@qq.com", view.mail.accounts.single().address)
    }

    @Test
    fun parsesTheLegacyBareArray() {
        val array = JSONArray().put(
            JSONObject().put("id", "github").put("name", "GitHub").put("state", "ready"),
        )
        val view = parseConnectorsView(array)
        assertEquals(1, view.connectors.size)
        assertTrue(view.mail.accounts.isEmpty())
    }

    @Test
    fun parsesBrowserAndDevicePending() {
        val raw = JSONObject().put("connectors", JSONArray().put(
            JSONObject().put("id", "notion").put("state", "connecting")
                .put("pending", JSONObject().put("kind", "browser").put("url", "https://example.com/auth")),
        ).put(
            JSONObject().put("id", "github").put("state", "connecting")
                .put("pending", JSONObject().put("kind", "device")
                    .put("userCode", "ABCD-1234").put("verificationUri", "https://github.com/login/device")),
        ))
        val view = parseConnectorsView(raw)
        val browser = view.connectors.first { it.id == "notion" }.pending!!
        assertTrue(browser.isBrowser)
        assertEquals("https://example.com/auth", browser.url)
        val device = view.connectors.first { it.id == "github" }.pending!!
        assertTrue(device.isDevice)
        assertEquals("ABCD-1234", device.userCode)
        assertEquals("https://github.com/login/device", device.verificationUri)
    }

    @Test
    fun mailboxCardReflectsAccountCountAndPending() {
        val withAccounts = ConnectorsView(
            listOf(),
            MailBox(listOf(
                MailAccountRow("m1", "a@qq.com", "qq", "password", "ready", null),
                MailAccountRow("m2", "b@gmail.com", "gmail", "password", "ready", null),
            ), false, emptyList(), emptyList(), emptyList()),
        )
        val card = withAccounts.mailboxCard()
        assertEquals(ConnectorsView.MAIL_ID, card.id)
        assertEquals("ready", card.state)
        assertEquals("2 accounts", card.account)

        val connecting = ConnectorsView(
            listOf(),
            MailBox(emptyList(), false, emptyList(), emptyList(),
                listOf(ConnectorPending("browser", url = "https://mail.google.com/auth"))),
        )
        assertEquals("connecting", connecting.mailboxCard().state)
        assertNull(connecting.mailboxCard().account)
    }

    @Test
    fun stateFlagsAgreeWithServerState() {
        fun row(state: String, account: String? = null) = parseConnectorsView(
            JSONObject().put("connectors", JSONArray().put(
                JSONObject().put("id", "x").put("state", state).apply { account?.let { put("account", it) } },
            )),
        ).connectors.single()

        assertTrue(row("ready", "me").connected)
        assertFalse(row("off").connected)
        assertTrue(row("connecting").connecting)
        assertTrue(row("needs-sign-in").needsSignIn)
        assertTrue(row("needs-client").needsClient)
        assertTrue(row("error").failed)
        assertTrue(row("connecting").on)
        assertFalse(row("off").on)
    }

    @Test
    fun parsesFromStateConnectorsField() {
        // 目录页的初始数据来自 `state.connectors`（host 的 state 把它设成 connectorsView()），
        // 不是某个 connector-* 端点 —— 这一条锁住「打开设置页就能看到连接器」。
        val state = JSONObject().put("connectors", JSONObject()
            .put("connectors", JSONArray().put(
                JSONObject().put("id", "github").put("name", "GitHub").put("state", "ready")
                    .put("account", "me"),
            ))
            .put("mail", JSONObject().put("accounts", JSONArray())))
        val view = parseConnectorsView(state.opt("connectors"))
        assertEquals(1, view.connectors.size)
        assertEquals("GitHub", view.connectors.single().name)
        assertTrue(view.connectors.single().connected)
    }

    @Test
    fun malformedFieldsFallBackAndBrokenRowsAreDropped() {
        val raw = JSONObject().put("connectors", JSONArray()
            .put(JSONObject().put("id", "").put("name", "no id")) // 无 id → 丢
            .put(JSONObject().put("id", "ok").put("state", JSONObject.NULL).put("tools", "not-int"))
            .put("not an object"),
        )
        val view = parseConnectorsView(raw)
        assertEquals(1, view.connectors.size)
        val ok = view.connectors.single()
        assertEquals("ok", ok.name) // name 缺省回落 id
        assertEquals("off", ok.state) // state 非字符串回落
        assertEquals(0, ok.tools) // tools 类型不符回落 0
        assertFalse(ok.connected)

        // 完全不是对象 → 空视图，不抛。
        assertEquals(ConnectorsView.EMPTY, parseConnectorsView("garbage"))
        assertEquals(ConnectorsView.EMPTY, parseConnectorsView(null))
    }

    /**
     * 邮箱写端点返回的是裸 mailView()，不是 connectorsView()。以前 VM 用
     * parseConnectorsView 解析它 → 得到空视图 → 加/删邮箱后整页连接器消失（review 高危）。
     */
    @Test
    fun mailWriteResponseIsParsedAsMailBoxNotEmptyConnectors() {
        val mailView = JSONObject()
            .put("accounts", JSONArray().put(
                JSONObject().put("id", "m1").put("address", "me@qq.com")
                    .put("provider", "qq").put("auth", "password").put("state", "ready"),
            ))
            .put("google", false)
            .put("allowedRecipients", JSONArray())
            .put("noDraftFor", JSONArray())

        // 裸 mailView 必须被识别成邮箱视图。
        val mail = parseMailView(mailView)
        assertNotNull(mail)
        assertEquals("me@qq.com", mail!!.accounts.single().address)

        // 不能用 connector 视图那套解析——那会把整页读空。
        val wrongly = parseConnectorsView(mailView)
        assertTrue("mailView 没有 connectors 键，connector 解析必然是空视图", wrongly.connectors.isEmpty())

        // 形状不对（比如 connector 响应）不能当邮箱视图覆盖 UI。
        val connectorsView = JSONObject().put("connectors", JSONArray()).put("mail", JSONObject())
        assertNull(parseMailView(connectorsView))
        assertNull(parseMailView("garbage"))
    }
}
