package com.dshm.bot.data.remote

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * DSH 会话协议客户端，对齐 dsh-Go `packages/dsh_client/lib/src/session_api.dart`。
 * 复用 [DshClient] 的 cookie / RPC envelope / 401 重登，不另起传输层。
 *
 * 本批只做 unary RPC；`session/follow` 的 WebSocket mux 流留到下一批。
 */
class DshSessionRepository(private val client: DshClient) {

    private fun request(fill: JSONObject.() -> Unit): JSONObject =
        JSONObject().put("request", JSONObject().apply(fill))

    suspend fun listSessions(): List<DshSessionSummary> =
        DshSessionSummary.listFromJson(
            client.rpc("session/list", JSONObject().put("_request", JSONObject())).optJSONArray("items"),
        )

    suspend fun createSession(
        cwd: String? = null,
        workspaceId: String? = null,
        agentPreset: String? = null,
        sessionId: String? = null,
    ): String = client.rpc(
        "session/create",
        request {
            workspaceId?.let { put("workspaceId", it) }
            cwd?.let { put("cwd", it) }
            sessionId?.let { put("sessionId", it) }
            agentPreset?.let { put("agentPreset", it) }
        },
    ).optString("sessionId")

    suspend fun renameSession(sessionId: String, title: String): String =
        client.rpc(
            "session/rename",
            request {
                put("sessionId", sessionId)
                put("title", title)
            },
        ).optString("title").ifEmpty { title }

    suspend fun forkSession(sessionId: String, atSeq: Int? = null): String =
        client.rpc(
            "session/fork",
            request {
                put("sessionId", sessionId)
                atSeq?.let { put("atSeq", it) }
            },
        ).optString("sessionId")

    suspend fun archiveSession(sessionId: String, stopActivity: Boolean? = null): List<String> =
        archivedIds(
            client.rpc(
                "workspace/archiveSession",
                request {
                    put("sessionId", sessionId)
                    stopActivity?.let { put("stopActivity", it) }
                },
            ),
        )

    suspend fun unarchiveSession(sessionId: String): List<String> =
        archivedIds(client.rpc("workspace/unarchiveSession", request { put("sessionId", sessionId) }))

    private fun archivedIds(value: JSONObject): List<String> {
        val array = value.optJSONArray("archivedSessionIds") ?: return emptyList()
        return List(array.length()) { array.optString(it) }
    }

    suspend fun searchSessions(query: String): DshSearchPage {
        val value = client.rpc("session/search", request { put("query", query) })
        val array = value.optJSONArray("items")
        val items = if (array == null) {
            emptyList()
        } else {
            List(array.length()) {
                val item = array.optJSONObject(it) ?: JSONObject()
                DshSearchHit(item.optString("sessionId"), item.optString("snippet"))
            }
        }
        return DshSearchPage(items, value.optBoolean("hasMore", false))
    }

    /**
     * `session/prompt`：返回 requestId（Host 会回显在 `user/message.source.rpcId`）。
     * 同一 requestId 重试是幂等的，所以允许调用方传入可复用的 id。
     */
    suspend fun prompt(
        sessionId: String,
        text: String,
        mode: String = PROMPT_MODE_QUEUE,
        requestId: String = UUID.randomUUID().toString(),
        timeZone: String? = null,
        fileReceiptIds: List<String> = emptyList(),
    ): String {
        require(text.isNotBlank() || fileReceiptIds.isNotEmpty()) {
            "session/prompt 需要非空文本或附件"
        }
        val content = JSONArray().apply {
            if (text.isNotEmpty()) put(JSONObject().put("type", "text").put("text", text))
            fileReceiptIds.forEach { put(JSONObject().put("type", "file").put("receiptId", it)) }
        }
        client.rpc(
            "session/prompt",
            request {
                put("requestId", requestId)
                put("sessionId", sessionId)
                put("mode", mode)
                put("content", content)
                timeZone?.let { put("clientTimeZone", it) }
            },
        )
        return requestId
    }

    suspend fun cancel(sessionId: String) {
        client.rpc("session/cancel", request { put("sessionId", sessionId) })
    }

    /** 排队消息的编辑 / 撤回 / 改为插话。 */
    suspend fun updateQueue(
        sessionId: String,
        itemId: String,
        editText: String? = null,
        remove: Boolean = false,
        steer: Boolean = false,
    ) {
        val action = when {
            remove -> JSONObject().put("kind", "remove")
            steer -> JSONObject().put("kind", "steer")
            else -> JSONObject().put("kind", "edit").put(
                "content",
                JSONArray().put(JSONObject().put("type", "text").put("text", editText ?: "")),
            )
        }
        client.rpc(
            "session/updateQueue",
            request {
                put("sessionId", sessionId)
                put("itemId", itemId)
                put("action", action)
            },
        )
    }

    suspend fun modelCatalog(): JSONObject = client.rpc("session/modelCatalog")

    suspend fun selectModel(
        sessionId: String,
        provider: String,
        model: String,
        reasoningEffort: String? = null,
    ): JSONObject = client.rpc(
        "session/selectModel",
        request {
            put("sessionId", sessionId)
            put("provider", provider)
            put("model", model)
            reasoningEffort?.let { put("reasoningEffort", it) }
        },
    )

    /** `llm/listProviders` 返回数组，必须走 rpcValue 保留原始 value。 */
    suspend fun listProviders(): List<DshProvider> {
        val array = client.rpcValue("llm/listProviders") as? JSONArray ?: return emptyList()
        return List(array.length()) {
            val item = array.optJSONObject(it) ?: JSONObject()
            DshProvider(item.optString("id"), item.optString("name"))
        }
    }

    suspend fun permissionPresets(): JSONObject = client.rpc("permissionPresets/catalog")

    suspend fun describeCredentials(refs: List<String>): JSONObject =
        client.rpc(
            "credentials/describe",
            JSONObject().put("refs", JSONArray().apply { refs.forEach { put(it) } }),
        )

    suspend fun projections(sessionId: String): JSONObject? =
        client.rpcValue("session/projections", request { put("sessionId", sessionId) }) as? JSONObject

    // ------------------------------------------------------------------
    // 审批 / 提问 / 主机事件回执
    // ------------------------------------------------------------------

    /**
     * 主机事件回执。outcome 形状：`{"kind":"result","value":...}`、
     * `{"kind":"next"}` 或 `{"kind":"rejected","error":{...}}`。
     * clientId 取自 `$events` 首帧 ready，eventId 取自 waterfall / cancel 帧。
     */
    suspend fun respondHostEvent(clientId: String, eventId: String, outcome: JSONObject) {
        client.rpc(
            "\$events/result",
            JSONObject()
                .put("clientId", clientId)
                .put("eventId", eventId)
                .put("outcome", outcome),
        )
    }

    /** 放行并让 Host 继续后续流程（审批「下一步」）。 */
    suspend fun passHostEvent(clientId: String, eventId: String) =
        respondHostEvent(clientId, eventId, JSONObject().put("kind", "next"))

    suspend fun resolveHostEvent(clientId: String, eventId: String, value: Any?) =
        respondHostEvent(clientId, eventId, JSONObject().put("kind", "result").put("value", value))

    /** 审批结果：decisionWire 由调用方按 Host 的决策枚举传入，不在这里写死取值。 */
    suspend fun respondApproval(clientId: String, eventId: String, decisionWire: String) =
        resolveHostEvent(clientId, eventId, decisionWire)

    suspend fun answerQuestion(clientId: String, eventId: String, answers: JSONArray) =
        resolveHostEvent(clientId, eventId, JSONObject().put("answers", answers))

    suspend fun rejectHostEvent(
        clientId: String,
        eventId: String,
        message: String,
        code: String? = null,
        name: String = "UserQuestionError",
    ) {
        val error = JSONObject().put("name", name).put("message", message)
        code?.let { error.put("code", it) }
        respondHostEvent(clientId, eventId, JSONObject().put("kind", "rejected").put("error", error))
    }

    /** 工具调用已经返回后补答提问（走 userQuestions/answer 的 unary 路径）。 */
    suspend fun answerContinuedQuestion(sessionId: String, callId: String, answers: JSONArray): Boolean =
        client.rpcValue(
            "userQuestions/answer",
            JSONObject()
                .put("agentId", sessionId)
                .put("callId", callId)
                .put("answer", JSONObject().put("answers", answers)),
        ) == true

    /** 切换当前对话的权限预设，与 dsh-Go 一样走 commands/execute 的斜杠命令。 */
    suspend fun setPermissionPreset(sessionId: String, preset: String) {
        client.rpc(
            "commands/execute",
            JSONObject()
                .put("agentId", sessionId)
                .put("line", "/permission $preset")
                .put("submittedAttachments", JSONArray()),
        )
    }

    companion object {
        const val PROMPT_MODE_QUEUE = "queue"
    }
}
