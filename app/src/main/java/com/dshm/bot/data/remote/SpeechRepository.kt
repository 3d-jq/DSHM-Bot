package com.dshm.bot.data.remote

/** 语音输入用例：UI 只依赖这个接口，不直接碰 HTTP/RPC。 */
interface SpeechRepository {
    suspend fun catalog(): SpeechCatalog?
    suspend fun prepare(providerId: String)
    suspend fun cancelPreparation(providerId: String)
    suspend fun configure(providerId: String?, language: String?)
    suspend fun transcribe(wav: ByteArray): String
}

/** 语音接口的 Remote 实现（官方语音输入插件）。 */
class RemoteSpeechRepository(private val client: DshClient) : SpeechRepository {

    override suspend fun catalog(): SpeechCatalog? = runCatching {
        SpeechCatalog.fromJson(client.rpc("speech/catalog"))
    }.getOrNull()

    override suspend fun prepare(providerId: String) {
        client.rpc("speech/prepare", org.json.JSONObject().put("providerId", providerId))
    }

    override suspend fun cancelPreparation(providerId: String) {
        client.rpc(
            "speech/cancelPreparation",
            org.json.JSONObject().put("providerId", providerId),
            timeoutMs = 60_000,
        )
    }

    override suspend fun configure(providerId: String?, language: String?) {
        val patch = org.json.JSONObject()
        providerId?.takeIf { it.isNotBlank() }?.let { patch.put("providerId", it) }
        language?.takeIf { it.isNotBlank() }?.let { patch.put("language", it) }
        client.rpc("speech/configure", org.json.JSONObject().put("patch", patch))
    }

    override suspend fun transcribe(wav: ByteArray): String {
        val request = org.json.JSONObject().put(
            "audioBase64",
            android.util.Base64.encodeToString(wav, android.util.Base64.NO_WRAP),
        )
        val result = client.rpc(
            "speech/transcribe",
            org.json.JSONObject().put("request", request),
            timeoutMs = 180_000,
        )
        return result.optString("text").trim()
    }
}
