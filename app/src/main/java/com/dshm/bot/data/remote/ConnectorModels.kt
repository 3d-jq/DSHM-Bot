package com.dshm.bot.data.remote

import org.json.JSONArray
import org.json.JSONObject

/**
 * DS Bot 0.3.x 连接器页的数据模型（对齐宿主 `src/host/connectors/index.js` 的
 * `connectorsView()` + `src/host/connectors/mail/index.js` 的 `mailView()`）。
 *
 * 0.3.6 之前 `connector-*` 端点返回**裸数组**；0.3.6 起返回 `{connectors, mail}`
 * 对象 —— 旧客户端按数组解析会把整页读空，这是连接器 UI 重写的第一个动机。
 *
 * 解析纪律（和 timeline 一样）：缺字段回落、类型不符给默认值、坏项跳过，
 * 绝不把畸形 JSON 抛进渲染管线。纯 JVM 逻辑，可用单测完整覆盖。
 */

/** 连接器的登录方式（决定详情页画哪种表单）。 */
enum class ConnectorAuthKind {
    NONE, OAUTH, KEY, UNKNOWN;

    companion object {
        fun of(raw: String?): ConnectorAuthKind = when (raw?.lowercase()) {
            "none" -> NONE
            "oauth" -> OAUTH
            "key" -> KEY
            else -> UNKNOWN
        }
    }
}

/** 登录进行中的信息（浏览器回执 / 设备码）。 */
data class ConnectorPending(
    val kind: String,
    val url: String? = null,
    val userCode: String? = null,
    val verificationUri: String? = null,
) {
    val isBrowser: Boolean get() = kind == "browser"
    val isDevice: Boolean get() = kind == "device"
}

/** 一个连接器在服务端的配置（来自 `connectorsView().connectors[]`）。 */
data class ConnectorRow(
    val id: String,
    val name: String,
    val category: String,
    val about: String,
    val state: String,
    val account: String?,
    val error: String?,
    val auth: ConnectorAuthKind,
    /** OAuth 目录是否也允许粘贴 token（GitHub）。 */
    val authToken: Boolean,
    /** 已知的公共 client id（GitHub device flow 有值）。 */
    val authClientId: String?,
    /** API key 的获取地址（key 类型连接器）。 */
    val keyUrl: String?,
    val tools: Int,
    val readOnly: Boolean?,
    val offFor: List<String>,
    val custom: Boolean,
    val pending: ConnectorPending?,
) {
    val connected: Boolean get() = state == "ready" || state == "connected" || (state == "idle" && account != null)
    val connecting: Boolean get() = state == "connecting"
    val needsSignIn: Boolean get() = state == "needs-sign-in"
    val needsClient: Boolean get() = state == "needs-client"
    val failed: Boolean get() = state == "error"
    /** UI 上「开」的状态：已连 / 连中 / 需要重新登录 / 出错都算（有操作可做）。 */
    val on: Boolean get() = state != "off" && state != "needs-client"
}

/** 邮箱账号（`mailView().accounts[]`）。 */
data class MailAccountRow(
    val id: String,
    val address: String,
    val provider: String,
    val auth: String,
    val state: String,
    val error: String?,
)

/** 邮箱页（`mailView()`）。 */
data class MailBox(
    val accounts: List<MailAccountRow>,
    val google: Boolean,
    val allowedRecipients: List<String>,
    val noDraftFor: List<String>,
    val pending: List<ConnectorPending>,
)

/** `connectorsView()` 的整体返回。 */
data class ConnectorsView(
    val connectors: List<ConnectorRow>,
    val mail: MailBox,
) {
    /** 目录里的「邮箱」虚拟卡（官方网页端把它当成一个 app 排在目录里）。 */
    fun mailboxCard(): ConnectorRow = ConnectorRow(
        id = MAIL_ID,
        name = "Mailbox",
        category = "work",
        about = "QQ, 163, 126, Gmail, iCloud and other IMAP mail",
        state = when {
            mail.accounts.isNotEmpty() -> "ready"
            mail.pending.isNotEmpty() -> "connecting"
            else -> "off"
        },
        account = when (mail.accounts.size) {
            0 -> null
            1 -> mail.accounts[0].address
            else -> "${mail.accounts.size} accounts"
        },
        error = mail.accounts.firstOrNull { it.error != null }?.error,
        auth = ConnectorAuthKind.UNKNOWN,
        authToken = false,
        authClientId = null,
        keyUrl = null,
        tools = 0,
        readOnly = null,
        offFor = emptyList(),
        custom = false,
        pending = null,
    )

    companion object {
        const val MAIL_ID = "mail"
        val EMPTY = ConnectorsView(emptyList(), MailBox(emptyList(), false, emptyList(), emptyList(), emptyList()))
    }
}

private fun optString(obj: JSONObject, key: String): String? =
    obj.opt(key)?.takeIf { it != JSONObject.NULL }?.toString()?.takeIf { it.isNotEmpty() }

private fun stringList(array: JSONArray?): List<String> = buildList {
    if (array == null) return@buildList
    for (index in 0 until array.length()) {
        array.optString(index).takeIf { it.isNotEmpty() }?.let(::add)
    }
}

private fun parsePending(obj: JSONObject?): ConnectorPending? {
    if (obj == null) return null
    val kind = obj.optString("kind").ifEmpty { return null }
    return ConnectorPending(
        kind = kind,
        url = optString(obj, "url"),
        userCode = optString(obj, "userCode"),
        verificationUri = optString(obj, "verificationUri"),
    )
}

private fun parseConnector(obj: JSONObject): ConnectorRow? {
    val id = obj.optString("id").ifEmpty { return null }
    val auth = obj.optJSONObject("auth")
    val client = auth?.optJSONObject("client")
    return ConnectorRow(
        id = id,
        name = obj.optString("name").ifEmpty { id },
        category = obj.optString("category"),
        about = obj.optString("about"),
        state = obj.optString("state").ifEmpty { "off" },
        account = optString(obj, "account"),
        error = optString(obj, "error"),
        auth = ConnectorAuthKind.of(auth?.optString("kind")),
        authToken = auth?.optBoolean("token", false) == true,
        authClientId = optString(client ?: JSONObject(), "id"),
        keyUrl = optString(auth ?: JSONObject(), "keyUrl"),
        tools = runCatching { obj.optInt("tools", 0) }.getOrDefault(0),
        readOnly = if (obj.has("readOnly") && !obj.isNull("readOnly")) obj.optBoolean("readOnly") else null,
        offFor = stringList(obj.optJSONArray("offFor")),
        custom = obj.optBoolean("custom", false),
        pending = parsePending(obj.optJSONObject("pending")),
    )
}

private fun parseMailAccount(obj: JSONObject): MailAccountRow? {
    val id = obj.optString("id").ifEmpty { return null }
    return MailAccountRow(
        id = id,
        address = obj.optString("address"),
        provider = obj.optString("provider"),
        auth = obj.optString("auth"),
        state = obj.optString("state").ifEmpty { "off" },
        error = optString(obj, "error"),
    )
}

private fun parseMail(obj: JSONObject?): MailBox {
    if (obj == null) return MailBox(emptyList(), false, emptyList(), emptyList(), emptyList())
    val accounts = buildList {
        val array = obj.optJSONArray("accounts") ?: return@buildList
        for (index in 0 until array.length()) {
            array.optJSONObject(index)?.let(::parseMailAccount)?.let(::add)
        }
    }
    val pending = buildList {
        val array = obj.optJSONArray("pending") ?: return@buildList
        for (index in 0 until array.length()) {
            parsePending(array.optJSONObject(index))?.let(::add)
        }
    }
    return MailBox(
        accounts = accounts,
        google = obj.optBoolean("google", false),
        allowedRecipients = stringList(obj.optJSONArray("allowedRecipients")),
        noDraftFor = stringList(obj.optJSONArray("noDraftFor")),
        pending = pending,
    )
}

/**
 * 解析 `connector-*` / `mail-*` 端点的返回值。
 * 0.3.6 起是 `{connectors:[...], mail:{...}}` 对象；兼容**老版的裸数组**
 * （那时只有连接器列表、没有邮箱），读端不会因升级而白屏。
 */
fun parseConnectorsView(raw: Any?): ConnectorsView {
    if (raw is JSONArray) {
        val rows = buildList {
            for (index in 0 until raw.length()) {
                raw.optJSONObject(index)?.let(::parseConnector)?.let(::add)
            }
        }
        return ConnectorsView(rows, MailBox(emptyList(), false, emptyList(), emptyList(), emptyList()))
    }
    val obj = raw as? JSONObject ?: return ConnectorsView.EMPTY
    val array = obj.optJSONArray("connectors")
    val rows = buildList {
        if (array == null) return@buildList
        for (index in 0 until array.length()) {
            array.optJSONObject(index)?.let(::parseConnector)?.let(::add)
        }
    }
    return ConnectorsView(rows, parseMail(obj.optJSONObject("mail")))
}

/**
 * 解析 `mail-account-add/remove`、`mail-rule-set` 等邮箱写端点的返回值。
 *
 * 它们经 `connectorEndpoint` 直接返回 host 的 `mailView()`（裸
 * `{accounts, google, allowedRecipients, noDraftFor, pending?}`），**不是**
 * `connectorsView()` 的 `{connectors, mail}` —— 不能拿 [parseConnectorsView] 解析，
 * 否则整页会被读成空（加/删邮箱后目录消失）。返回 null 表示形状不对、不该覆盖 UI。
 */
fun parseMailView(raw: Any?): MailBox? {
    val obj = raw as? JSONObject ?: return null
    // mailView 一定有 accounts 数组（哪怕为空）；没有就不认，避免误把 connector 响应当邮箱。
    if (!obj.has("accounts") || obj.isNull("accounts") || obj.optJSONArray("accounts") == null) return null
    return parseMail(obj)
}
