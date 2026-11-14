package com.dshm.bot.ui.engine

import com.dshm.bot.MainDispatcherRule
import com.dshm.bot.data.remote.BotFeedEvent
import com.dshm.bot.data.remote.DshRemoteApi
import com.dshm.bot.data.runtime.RuntimeEndpoint
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * 诊断页两个宿主动作的状态机：看完整日志（`diagnostics-log`）与清空
 * （`diagnostics-clear`）。走注入的 [DshRemoteApi]，不碰 Android。
 *
 * UI 组合层（弹窗/滚动/二次确认）没有 Robolectric 依赖，这里只锁状态迁移与端点调用；
 * 组合层由编译期与真机自测覆盖。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DiagnosticsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun viewModel(api: FakeApi): DiagnosticsViewModel =
        DiagnosticsViewModel(providedApi = { api }).also {
            // 生产链路由引擎状态喂端点；测试里端点非空即可，客户端来自注入。
            it.setEndpoint(RuntimeEndpoint("http://127.0.0.1:1/", "t"))
        }

    @Test
    fun `openLog loads the host log and shows it`() = runTest(mainDispatcherRule.testDispatcher) {
        val api = FakeApi(log = "line one\nline two")
        val vm = viewModel(api)

        vm.openLog()
        runCurrent()

        val state = vm.state.value
        assertTrue(state.logOpen)
        assertEquals(DiagnosticsLogStatus.LOADED, state.logStatus)
        assertEquals("line one\nline two", state.logText)
        assertEquals(listOf(DiagnosticsViewModel.ENDPOINT_LOG), api.calls)
    }

    @Test
    fun `an empty log is a loaded, empty state rather than an error`() = runTest(mainDispatcherRule.testDispatcher) {
        val vm = viewModel(FakeApi(log = ""))

        vm.openLog()
        runCurrent()

        assertEquals(DiagnosticsLogStatus.LOADED, vm.state.value.logStatus)
        assertEquals("", vm.state.value.logText)
    }

    @Test
    fun `a failed log load is visible, and retry succeeds`() = runTest(mainDispatcherRule.testDispatcher) {
        val api = FakeApi(logFailure = IOException("engine offline"))
        val vm = viewModel(api)

        vm.openLog()
        runCurrent()
        assertEquals(DiagnosticsLogStatus.FAILED, vm.state.value.logStatus)
        assertEquals("", vm.state.value.logText)

        // The engine comes back: the retry button reloads and shows the text.
        api.logFailure = null
        api.log = "recovered"
        vm.reloadLog()
        runCurrent()

        assertEquals(DiagnosticsLogStatus.LOADED, vm.state.value.logStatus)
        assertEquals("recovered", vm.state.value.logText)
    }

    @Test
    fun `no engine means a visible failure, not a silent no-op`() = runTest(mainDispatcherRule.testDispatcher) {
        val vm = DiagnosticsViewModel(providedApi = { null })

        vm.openLog()
        runCurrent()
        assertEquals(DiagnosticsLogStatus.FAILED, vm.state.value.logStatus)

        vm.clear()
        runCurrent()
        assertEquals(DiagnosticsClearStatus.FAILED, vm.state.value.clearStatus)
    }

    @Test
    fun `clear posts the endpoint, refreshes the log and bumps the report revision`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val api = FakeApi(log = "before clear")
            val vm = viewModel(api)
            vm.openLog()
            runCurrent()
            assertEquals("before clear", vm.state.value.logText)

            // The host empties its log while clearing.
            api.calls.clear()
            api.log = ""
            vm.clear()
            runCurrent()

            assertEquals(
                "清空后必须重读日志（刷新），不是只发一次 clear",
                listOf(DiagnosticsViewModel.ENDPOINT_CLEAR, DiagnosticsViewModel.ENDPOINT_LOG),
                api.calls,
            )
            val state = vm.state.value
            assertEquals(DiagnosticsClearStatus.CLEARED, state.clearStatus)
            assertEquals(1, state.reportRevision)
            assertEquals("", state.logText)
            assertEquals(DiagnosticsLogStatus.LOADED, state.logStatus)
        }

    @Test
    fun `a failed clear is visible and leaves the report revision alone`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val api = FakeApi(clearFailure = IOException("refused"))
            val vm = viewModel(api)

            vm.clear()
            runCurrent()

            val state = vm.state.value
            assertEquals(DiagnosticsClearStatus.FAILED, state.clearStatus)
            assertEquals(0, state.reportRevision)
            assertEquals(listOf(DiagnosticsViewModel.ENDPOINT_CLEAR), api.calls)
        }

    @Test
    fun `a second clear while one is in flight is ignored`() = runTest(mainDispatcherRule.testDispatcher) {
        val api = FakeApi()
        val vm = viewModel(api)

        vm.clear()
        vm.clear()
        runCurrent()

        assertEquals(
            "清空是破坏性操作，进行中不能被重复触发",
            1,
            api.calls.count { it == DiagnosticsViewModel.ENDPOINT_CLEAR },
        )
    }

    @Test
    fun `switching engines resets the page state`() = runTest(mainDispatcherRule.testDispatcher) {
        val api = FakeApi(log = "old engine log")
        val vm = viewModel(api)
        vm.openLog()
        runCurrent()
        vm.clear()
        runCurrent()
        assertEquals(DiagnosticsClearStatus.CLEARED, vm.state.value.clearStatus)

        vm.setEndpoint(RuntimeEndpoint("http://127.0.0.1:2/", "t2"))
        val state = vm.state.value
        assertFalse("换引擎时打开的日志弹窗要收起", state.logOpen)
        assertEquals(DiagnosticsLogStatus.IDLE, state.logStatus)
        assertEquals("", state.logText)
        assertEquals(DiagnosticsClearStatus.IDLE, state.clearStatus)
    }

    /** 假 DSH 客户端：记录端点调用，可按需让某一次失败。 */
    private class FakeApi(
        var log: String = "",
        var logFailure: Throwable? = null,
        var clearFailure: Throwable? = null,
    ) : DshRemoteApi {
        val calls = mutableListOf<String>()

        override suspend fun bot(endpoint: String, payload: JSONObject): JSONObject = JSONObject()

        override suspend fun botValue(endpoint: String, payload: JSONObject): Any? {
            calls += endpoint
            return when (endpoint) {
                DiagnosticsViewModel.ENDPOINT_LOG -> {
                    logFailure?.let { throw it }
                    log
                }

                DiagnosticsViewModel.ENDPOINT_CLEAR -> {
                    clearFailure?.let { throw it }
                    true
                }

                else -> null
            }
        }

        override suspend fun rpc(method: String, args: JSONObject, timeoutMs: Int): JSONObject = JSONObject()

        override fun feed(): Flow<BotFeedEvent> = emptyFlow()
    }
}
