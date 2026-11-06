package com.dshm.bot.data.remote

import java.util.Collections
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 审批 / 提问 / 权限预设的 RPC 契约：路径、outcome 形状、返回值。 */
class DshSessionApprovalContractTest {

    private fun rpcResult(value: String) = FakeDshServer.Response(
        200,
        body = """{"result":{"ok":true,"value":$value}}""",
    )

    @Test
    fun approvalAndPassOutcomesAreSentToEventsResult() {
        FakeDshServer().use { server ->
            val bodies = Collections.synchronizedList(mutableListOf<String>())
            server.responder = { request ->
                when {
                    request.path.startsWith(FakeDshServer.LOGIN_PATH_PREFIX) -> FakeDshServer.cookie("appr")
                    request.path.endsWith("events/result") -> {
                        bodies.add(request.body)
                        rpcResult("{}")
                    }
                    else -> FakeDshServer.Response(404)
                }
            }
            server.start()

            runBlocking {
                val repository = DshSessionRepository(DshClient(server.endpoint))
                repository.respondApproval("client-1", "ev-1", "allow")
                repository.passHostEvent("client-1", "ev-2")
            }

            assertEquals(2, bodies.size)
            val first = JSONObject(bodies[0])
            assertTrue(first.optString("method").endsWith("events/result"))
            val args = first.getJSONObject("payload").getJSONObject("args")
            assertEquals("client-1", args.optString("clientId"))
            assertEquals("ev-1", args.optString("eventId"))
            assertEquals("result", args.getJSONObject("outcome").optString("kind"))
            assertEquals("allow", args.getJSONObject("outcome").optString("value"))
            assertEquals("next", JSONObject(bodies[1])
                .getJSONObject("payload").getJSONObject("args")
                .getJSONObject("outcome").optString("kind"))
        }
    }

    @Test
    fun answerQuestionAndRejectBuildExpectedOutcomes() {
        FakeDshServer().use { server ->
            val bodies = Collections.synchronizedList(mutableListOf<String>())
            server.responder = { request ->
                when {
                    request.path.startsWith(FakeDshServer.LOGIN_PATH_PREFIX) -> FakeDshServer.cookie("appr")
                    request.path.endsWith("events/result") -> {
                        bodies.add(request.body)
                        rpcResult("{}")
                    }
                    else -> FakeDshServer.Response(404)
                }
            }
            server.start()

            runBlocking {
                val repository = DshSessionRepository(DshClient(server.endpoint))
                repository.answerQuestion("c1", "ev1", JSONArray().put(JSONObject().put("id", "q1")))
                repository.rejectHostEvent("c1", "ev2", "declined", code = "ASK_TIMED_OUT")
            }

            val answer = JSONObject(bodies[0]).getJSONObject("payload")
                .getJSONObject("args").getJSONObject("outcome")
            assertEquals("result", answer.optString("kind"))
            assertEquals("q1", answer.getJSONObject("value")
                .getJSONArray("answers").getJSONObject(0).optString("id"))

            val rejected = JSONObject(bodies[1]).getJSONObject("payload")
                .getJSONObject("args").getJSONObject("outcome")
            assertEquals("rejected", rejected.optString("kind"))
            assertEquals("ASK_TIMED_OUT", rejected.getJSONObject("error").optString("code"))
        }
    }

    @Test
    fun answerContinuedQuestionReturnsHostBoolean() {
        FakeDshServer().use { server ->
            server.responder = { request ->
                when {
                    request.path.startsWith(FakeDshServer.LOGIN_PATH_PREFIX) -> FakeDshServer.cookie("appr")
                    request.path == "/api/userQuestions/answer" -> rpcResult("true")
                    else -> FakeDshServer.Response(404)
                }
            }
            server.start()

            val accepted = runBlocking {
                DshSessionRepository(DshClient(server.endpoint))
                    .answerContinuedQuestion("s1", "call-1", JSONArray())
            }
            assertTrue(accepted)
        }
    }

    @Test
    fun permissionPresetGoesThroughCommandsExecute() {
        FakeDshServer().use { server ->
            val bodies = Collections.synchronizedList(mutableListOf<String>())
            server.responder = { request ->
                when {
                    request.path.startsWith(FakeDshServer.LOGIN_PATH_PREFIX) -> FakeDshServer.cookie("appr")
                    request.path == "/api/commands/execute" -> {
                        bodies.add(request.body)
                        rpcResult("{}")
                    }
                    else -> FakeDshServer.Response(404)
                }
            }
            server.start()

            runBlocking {
                DshSessionRepository(DshClient(server.endpoint))
                    .setPermissionPreset("s1", "full-access")
            }

            val args = JSONObject(bodies.single()).getJSONObject("payload").getJSONObject("args")
            assertEquals("s1", args.optString("agentId"))
            assertEquals("/permission full-access", args.optString("line"))
            assertTrue(args.has("submittedAttachments"))
        }
    }
}
