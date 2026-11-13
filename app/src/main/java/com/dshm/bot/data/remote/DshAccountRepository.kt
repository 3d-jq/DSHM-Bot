package com.dshm.bot.data.remote

import com.dshm.bot.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.TimeZone

/**
 * 账号服务仓储，对齐 dsh-Go `app/lib/state/account_api.dart`：
 * - `account/getState`、`account/watch`（我们暂用轮询代替流）
 * - `account/startSignIn {client, callbackOrigin, loginSource}`
 * - `account/cancelSignIn {attemptId}`、`account/signOut {client}`
 * - `account/getProfile` / `account/getBalance` / `account/hasRunningAccountTasks`
 * - 登录成功后 `session/initializeDefaultModel`
 *
 * Host 负责 PKCE、`/oauth/callback`、凭据存储与 deepseek-account 模型路由，
 * App 只发起/取消 attempt、跟随状态、读取展示安全的资料与余额；令牌不进 App。
 */
class DshAccountRepository(
    private val client: DshClient,
    private val callbackOrigin: String,
) {
    /** `SignInAttemptView.phase` 的 wire 取值。 */
    enum class Phase {
        INITIALIZING,
        WAITING_BROWSER,
        EXCHANGING,
        COMMITTING,
        SUCCEEDED,
        CANCELLED,
        EXPIRED,
        FAILED,
        UNKNOWN;

        val terminal: Boolean
            get() = this == SUCCEEDED || this == CANCELLED || this == EXPIRED || this == FAILED

        companion object {
            fun fromWire(value: String?): Phase? = when (value) {
                null -> null
                "initializing" -> INITIALIZING
                "waiting-browser" -> WAITING_BROWSER
                "exchanging" -> EXCHANGING
                "committing" -> COMMITTING
                "succeeded" -> SUCCEEDED
                "cancelled" -> CANCELLED
                "expired" -> EXPIRED
                "failed" -> FAILED
                else -> UNKNOWN
            }
        }
    }

    data class Snapshot(
        val signedIn: Boolean,
        val authorizeUrl: String?,
        val attemptId: String?,
        val phase: Phase? = null,
        val expiresAt: Long? = null,
        val errorCode: String? = null,
        val usageUrl: String? = null,
        val topUpUrl: String? = null,
    )

    /** 充值余额与赠送余额，对齐 dsh-Go 的 `value` 与 `bonusWallets`。 */
    data class Balance(val recharge: String?, val bonus: String?)

    suspend fun state(): Snapshot = parseSnapshot(client.account("getState"))

    suspend fun startSignIn(): Snapshot = parseSnapshot(
        client.account(
            "startSignIn",
            JSONObject()
                .put("client", clientMeta())
                .put("callbackOrigin", callbackOrigin)
                .put("loginSource", LOGIN_SOURCE_WEB),
        ),
    )

    suspend fun cancelSignIn(attemptId: String): Snapshot = parseSnapshot(
        client.account("cancelSignIn", JSONObject().put("attemptId", attemptId)),
    )

    suspend fun signOut(): Snapshot = parseSnapshot(
        client.account("signOut", JSONObject().put("client", clientMeta())),
    )

    /** 布尔型 value 必须走 rpcValue，account() 会把它转成空对象。 */
    suspend fun hasRunningAccountTasks(): Boolean = runCatching {
        client.rpcValue("account/hasRunningAccountTasks") == true
    }.getOrDefault(false)

    /** 登录成功后让 Host 把默认模型切到账号渠道。 */
    suspend fun initializeDefaultModel() {
        runCatching { client.rpc("session/initializeDefaultModel") }
    }

    data class Profile(val name: String?, val avatarUrl: String?)

    /** 账号资料（account/getProfile 的 value：name/contact/avatarUrl）。 */
    suspend fun profile(): Profile? = runCatching {
        val value = client.account("getProfile", JSONObject().put("client", clientMeta())).optJSONObject("value")
        val name = value?.optString("name")?.trim()?.ifEmpty { null }
            ?: value?.optString("contact")?.trim()?.ifEmpty { null }
        Profile(name, value?.optString("avatarUrl")?.trim()?.ifEmpty { null })
    }.getOrNull()

    /** 余额：充值钱包在 value，赠送钱包在 bonusWallets，两者分别展示。 */
    suspend fun balances(): Balance = runCatching {
        parseBalance(client.account("getBalance", JSONObject().put("client", clientMeta())))
    }.getOrDefault(Balance(null, null))

    /** 已登录时的显示名（名称 → 脱敏联系方式）。 */
    suspend fun displayName(): String? = runCatching {
        val value = client.account("getProfile", JSONObject().put("client", clientMeta()))
            .optJSONObject("value")
        val name = value?.optString("name").orEmpty().trim()
        if (name.isNotEmpty()) name else value?.optString("contact").orEmpty().trim().ifEmpty { null }
    }.getOrNull()

    private fun clientMeta(): JSONObject = JSONObject()
        .put("version", BuildConfig.VERSION_NAME)
        .put("locale", "zh-CN")
        .put("timezoneOffsetSeconds", TimeZone.getDefault().rawOffset / 1000)

    companion object {
        /** 手机是 Custom Tab / 外部浏览器，完成页需要可见，固定上报 web。 */
        const val LOGIN_SOURCE_WEB = "web"

        /** 解析 AccountView；抽成公开静态方法便于 JVM 单测覆盖 wire 形状。 */
        fun parseSnapshot(json: JSONObject): Snapshot {
            val status = json.optString("status")
            val attempt = json.optJSONObject("attempt")
            val links = json.optJSONObject("links")
            return Snapshot(
                signedIn = status == "credential-stored",
                authorizeUrl = attempt?.optString("authorizeUrl")?.ifEmpty { null },
                attemptId = attempt?.optString("id")?.ifEmpty { null },
                phase = Phase.fromWire(attempt?.optString("phase")?.ifEmpty { null }),
                expiresAt = attempt?.let {
                    if (it.has("expiresAt") && !it.isNull("expiresAt")) it.optLong("expiresAt") else null
                },
                errorCode = attempt?.optString("errorCode")?.ifEmpty { null },
                usageUrl = links?.optString("usageUrl")?.ifEmpty { null },
                topUpUrl = links?.optString("topUpUrl")?.ifEmpty { null },
            )
        }

        /** 解析 getBalance；status 非 ready 或字段缺失时返回两个 null，不臆造金额。 */
        fun parseBalance(json: JSONObject): Balance {
            if (json.optString("status") != "ready") return Balance(null, null)
            return Balance(
                recharge = walletText(json.optJSONArray("value")),
                bonus = walletText(json.optJSONArray("bonusWallets")),
            )
        }

        private fun walletText(wallets: JSONArray?): String? {
            val wallet = wallets?.optJSONObject(0) ?: return null
            val raw = wallet.optString("balance")
            if (raw.isEmpty()) return null
            val display = raw.toDoubleOrNull()?.let { String.format(Locale.ROOT, "%.2f", it) } ?: raw
            val currency = wallet.optString("currency").ifEmpty { "CNY" }
            return if (currency == "CNY") "¥$display" else "$display $currency"
        }
    }
}
