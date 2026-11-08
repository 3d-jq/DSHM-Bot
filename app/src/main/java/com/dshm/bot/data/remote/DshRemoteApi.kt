package com.dshm.bot.data.remote

import kotlinx.coroutines.flow.Flow
import org.json.JSONObject

/**
 * 对话/详情页依赖的最小 DSH 能力边界。
 *
 * ViewModel 不再直接依赖 [DshClient] 这个具体 HTTP 实现，测试可以替换成假实现；
 * 认证、cookie、SSE 解析仍然收在 data 层。
 */
interface DshRemoteApi {
    suspend fun bot(endpoint: String, payload: JSONObject = JSONObject()): JSONObject
    /** 同 [bot]，但保留原始 `value`（`room-progress` / `exchange` 这类返回数组的端点）。 */
    suspend fun botValue(endpoint: String, payload: JSONObject = JSONObject()): Any?
    suspend fun rpc(method: String, args: JSONObject = JSONObject(), timeoutMs: Int = 30_000): JSONObject
    fun feed(): Flow<BotFeedEvent>
}

/** 把具体 [DshClient] 收进接口，调用方只看到对话所需的最小面。 */
class RemoteDshApi(private val client: DshClient) : DshRemoteApi {
    override suspend fun bot(endpoint: String, payload: JSONObject): JSONObject =
        client.bot(endpoint, payload)

    override suspend fun botValue(endpoint: String, payload: JSONObject): Any? =
        client.botValue(endpoint, payload)

    override suspend fun rpc(method: String, args: JSONObject, timeoutMs: Int): JSONObject =
        client.rpc(method, args, timeoutMs)

    override fun feed(): Flow<BotFeedEvent> = client.feed()
}
