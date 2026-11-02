package com.dshm.bot.data.remote

import com.dshm.bot.BuildConfig
import org.json.JSONObject
import java.util.TimeZone

/**
 * 账号服务仓储，对齐 dsh-Go `state/account_api.dart`：
 * - `account/getState`
 * - `account/startSignIn {client, callbackOrigin, loginSource}`
 * - `account/signOut {client}`
 * - `account/getProfile` / `account/getBalance`
 *
 * `callbackOrigin` 是浏览器能访问到的本机 DSH 主机 origin（`http://127.0.0.1:<port>`），
 * 授权页回调由 DSH 自己的 `/oauth/callback` 完成，App 只需轮询 `getState`。
 */
class DshAccountRepository(
    private val client: DshClient,
    private val callbackOrigin: String,
) {
    data class Snapshot(
        val signedIn: Boolean,
        val authorizeUrl: String?,
        val attemptId: String?,
    )

    suspend fun state(): Snapshot = parse(client.account("getState"))

    suspend fun startSignIn(): Snapshot = parse(
        client.account(
            "startSignIn",
            JSONObject()
                .put("client", clientMeta())
                .put("callbackOrigin", callbackOrigin)
                .put("loginSource", "web"),
        ),
    )

    suspend fun signOut(): Snapshot = parse(
        client.account("signOut", JSONObject().put("client", clientMeta())),
    )

    /** 已登录时的显示名（名称 → 脱敏联系方式）。 */
    suspend fun displayName(): String? = runCatching {
        val value = client.account("getProfile", JSONObject().put("client", clientMeta()))
            .optJSONObject("value")
        val name = value?.optString("name").orEmpty().trim()
        if (name.isNotEmpty()) name else value?.optString("contact").orEmpty().trim().ifEmpty { null }
    }.getOrNull()

    private fun parse(json: JSONObject): Snapshot {
        val status = json.optString("status")
        val attempt = json.optJSONObject("attempt")
        return Snapshot(
            signedIn = status == "credential-stored",
            authorizeUrl = attempt?.optString("authorizeUrl")?.ifEmpty { null },
            attemptId = attempt?.optString("id")?.ifEmpty { null },
        )
    }

    private fun clientMeta(): JSONObject = JSONObject()
        .put("version", BuildConfig.VERSION_NAME)
        .put("locale", "zh-CN")
        .put("timezoneOffsetSeconds", TimeZone.getDefault().rawOffset / 1000)
}
