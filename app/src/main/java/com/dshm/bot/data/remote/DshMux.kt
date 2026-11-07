package com.dshm.bot.data.remote

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/** mux 连接状态。 */
enum class DshMuxState {
    /** 还没连。 */
    IDLE,

    /** 正在连（或重连）。 */
    CONNECTING,

    /** 已连上并拿到了 `$events` 的 ready。 */
    CONNECTED,

    /** 断开后正在重连。 */
    RECONNECTING,

    /** 需要用户处理（比如认证失败），不再自动重试。 */
    UNAUTHORIZED,

    /** 已关闭。 */
    CLOSED,
}

/**
 * `/api/remote.mux` 的客户端状态机。
 *
 * 一条 WebSocket 上跑很多条逻辑流；`$events` 是 Gateway 内部的主机事件流，
 * 每代连接的第一个 item 必须是 ready（带 clientId），之后才是 emit / waterfall / cancel。
 *
 * 规格照 dsh-Go `packages/dsh_client/src/mux.dart`（553 行）。
 * 关键行为：
 * - ready 之前收到别的事件 = 协议错误，断开重连；
 * - 断线后 pending 的审批/提问**会被主机用同一个 eventId 重新投递**，
 *   所以重连后要重新收集，不能指望本地缓存；
 * - 每次重连 generation 递增，回执必须带当前的 clientId。
 */
class DshMux(
    private val scope: CoroutineScope,
    /** 建立一条新链路（实现方负责握手与认证）。 */
    private val connect: suspend () -> DshMuxLink,
    private val readyTimeoutMs: Long = 15_000,
    private val retryCapsMs: List<Long> = listOf(500, 1_000, 2_000, 4_000, 8_000, 10_000),
    private val autoReconnect: Boolean = true,
) {

    private val lock = Mutex()
    private val random = Random.Default

    private val _state = MutableStateFlow(DshMuxState.IDLE)
    val state: StateFlow<DshMuxState> = _state.asStateFlow()

    /** ready 之后的主机事件（广播，不重放）。 */
    private val _hostEvents = MutableSharedFlow<DshHostEvent>(extraBufferCapacity = 64)
    val hostEvents: SharedFlow<DshHostEvent> = _hostEvents.asSharedFlow()

    /** 每次新的 ready 代 —— 逻辑流要在新代里重开。 */
    private val _generations = MutableSharedFlow<DshMuxGeneration>(extraBufferCapacity = 8)
    val generations: SharedFlow<DshMuxGeneration> = _generations.asSharedFlow()

    /** 当前代（只有 CONNECTED 时有效）。 */
    @Volatile
    var generation: DshMuxGeneration? = null
        private set

    /** 当前代里还没被应答/取消的主机请求，按到达顺序。 */
    private val pending = LinkedHashMap<String, DshHostEvent>()

    /** 本代已经打开的逻辑流（断线后要重开）。 */
    private val streams = LinkedHashMap<String, DshStreamHandle>()

    private var link: DshMuxLink? = null
    private var eventsStreamId: String? = null
    private var generationCounter = 0
    private var attempt = 0
    private var closed = false
    private var everConnected = false
    private var connectJob: Job? = null
    private var retryJob: Job? = null

    /** 一次待应答的主机请求。 */
    data class PendingRequest(
        val eventId: String,
        val approval: DshApprovalRequest? = null,
        val question: DshQuestionRequest? = null,
    )

    /** 当前待应答的请求（审批 / 提问）。 */
    fun pendingRequests(): List<PendingRequest> = synchronized(pending) {
        pending.mapNotNull { (eventId, event) ->
            when (event) {
                is DshHostEvent.Waterfall -> event.approval?.let { PendingRequest(eventId, approval = it) }
                    ?: event.question?.let { PendingRequest(eventId, question = it) }

                else -> null
            }
        }
    }

    /** 启动连接（幂等）。 */
    fun start() {
        scope.launch {
            // 注意：不要在 lock 里 launch —— runLoop 也要拿这把锁，会死锁。
            val shouldStart = lock.withLock {
                if (closed || connectJob?.isActive == true) {
                    false
                } else {
                    _state.value = DshMuxState.CONNECTING
                    true
                }
            }
                if (!shouldStart) return@launch
            connectJob = scope.launch { runLoop() }
        }
    }

    /** 关闭 mux，不再重连。 */
    fun close() {
        scope.launch {
            lock.withLock {
                closed = true
                retryJob?.cancel()
                connectJob?.cancel()
                link?.close(1000, "client closed")
                link = null
                generation = null
                eventsStreamId = null
                synchronized(pending) { pending.clear() }
                streams.clear()
                _state.value = DshMuxState.CLOSED
            }
        }
    }

    /**
     * 打开一条逻辑流（`session/follow` 之类）。断线重连后会自动重开。
     *
     * @param onFrame 收到该流的 item
     */
    fun openStream(
        endpoint: String,
        args: JSONObject = JSONObject(),
        onFrame: (JSONObject) -> Unit,
        onEnd: (String?) -> Unit = {},
    ): String {
        val streamId = UUID.randomUUID().toString()
        val handle = DshStreamHandle(streamId, endpoint, args, onFrame, onEnd)
        scope.launch {
            lock.withLock {
                streams[streamId] = handle
                link?.let { sendOpen(it, handle) }
            }
        }
        return streamId
    }

    /** 取消一条逻辑流。 */
    fun closeStream(streamId: String) {
        scope.launch {
            lock.withLock {
                streams.remove(streamId)?.let { handle ->
                    link?.cancel(handle.streamId)
                    handle.fail("stream closed")
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 内部：连接循环
    // ------------------------------------------------------------------

    private suspend fun runLoop() {
        while (!closed) {
            val newLink = try {
                connect()
            } catch (error: Throwable) {
                if (closed) return
                scheduleRetry("连接失败：${error.message}")
                return
            }
            val opened = openLink(newLink)
            if (!opened) {
                if (closed) return
                scheduleRetry("链路握手失败")
                return
            }
            // 等待 ready 或断开 —— 由回调推进状态。
            awaitReadyOrLoss(newLink)
            if (closed) return
            if (generation == null) {
                // 没等到 ready —— 重试。
                scheduleRetry("ready 超时")
                return
            }
            // 拿到 ready 了 —— 这一代就交给 onLinkLost 管，
            // 不能再往下循环（否则会又连一条，把刚建立的代冲掉）。
            return
        }
    }

    private suspend fun openLink(newLink: DshMuxLink): Boolean {
        val eventsId = UUID.randomUUID().toString()
        link = newLink
        eventsStreamId = eventsId
        newLink.start(
            onFrame = { frame -> onFrame(newLink, frame) },
            onLost = { reason -> onLinkLost(newLink, reason) },
        )
        // $events 的 args 必须是空的 —— 引擎会拒绝带参数的请求。
        // 注意只套一层 args：DshMuxFrame.open 会自己包 payload.args。
        newLink.open(
            eventsId,
            DshMuxFrame.HOST_EVENTS_ENDPOINT,
            JSONObject(),
        )
        return true
    }

    /** ready 到了就返回；链路断了也返回（由调用方判断要不要重试）。 */
    private suspend fun awaitReadyOrLoss(target: DshMuxLink) {
        val deadline = System.currentTimeMillis() + readyTimeoutMs
        while (!closed && link === target) {
            if (generation != null) return
            if (System.currentTimeMillis() > deadline) {
                onLinkLost(target, "ready 超时")
                return
            }
            delay(50)
        }
    }

    private fun onFrame(source: DshMuxLink, frame: JSONObject) {
        if (source !== link) return
        val streamId = frame.optString("streamId")
        if (streamId == eventsStreamId) {
            onEventsFrame(source, frame)
            return
        }
        val handle = streams[streamId] ?: return
        when (frame.optString("type")) {
            DshMuxFrame.TYPE_ITEM -> handle.onFrame(frame.opt("value") as? JSONObject ?: JSONObject())
            DshMuxFrame.TYPE_END -> {
                streams.remove(streamId)
                handle.onEnd(null)
            }

            DshMuxFrame.TYPE_ERROR -> {
                streams.remove(streamId)
                handle.onEnd(DshMuxFrame.errorOf(frame))
            }
        }
    }

    private fun onEventsFrame(source: DshMuxLink, frame: JSONObject) {
        val type = frame.optString("type")
        if (type != DshMuxFrame.TYPE_ITEM) {
            val reason = if (type == DshMuxFrame.TYPE_ERROR) {
                DshMuxFrame.errorOf(frame)
            } else {
                "\$events 流结束"
            }
            onLinkLost(source, reason)
            return
        }
        val event = DshMuxFrame.hostEventOf(frame) ?: return
        if (generation == null) {
            if (event !is DshHostEvent.Ready) {
                onLinkLost(source, "\$events 没有先发 ready")
                return
            }
            attempt = 0
            everConnected = true
            synchronized(pending) { pending.clear() }
            val newGeneration = DshMuxGeneration(
                number = ++generationCounter,
                clientId = event.clientId,
                home = event.home,
            )
            generation = newGeneration
            _state.value = DshMuxState.CONNECTED
            scope.launch { _generations.emit(newGeneration) }
            // 重开上一代的逻辑流。
            streams.values.toList().forEach { sendOpen(source, it) }
            return
        }
        when (event) {
            is DshHostEvent.Waterfall -> synchronized(pending) {
                pending[event.eventId] = event
            }

            is DshHostEvent.Cancelled -> synchronized(pending) {
                pending.remove(event.eventId)
            }

            else -> Unit
        }
        scope.launch { _hostEvents.emit(event) }
    }

    private fun onLinkLost(source: DshMuxLink, reason: String) {
        if (source !== link) return
        link = null
        eventsStreamId = null
        generation = null
        synchronized(pending) { pending.clear() }
        // 逻辑流全部失效 —— 重连后重开。
        streams.values.toList().forEach { it.fail(reason) }
        if (closed) return
        if (!autoReconnect) {
            _state.value = DshMuxState.IDLE
            return
        }
        scheduleRetry(reason)
    }

    private fun scheduleRetry(reason: String) {
        if (closed) return
        retryJob?.cancel()
        _state.value = DshMuxState.RECONNECTING
        val cap = retryCapsMs[minOf(attempt, retryCapsMs.size - 1)]
        attempt += 1
        // 50%~100% 抖动，避免多端同时重连撞在一起。
        val delayMs = cap / 2 + random.nextLong(cap / 2 + 1)
        retryJob = scope.launch {
            delay(delayMs)
            if (closed) return@launch
            val shouldStart = lock.withLock {
                if (closed) {
                    false
                } else {
                    _state.value = DshMuxState.CONNECTING
                    true
                }
            }
            if (!shouldStart) return@launch
            connectJob?.cancel()
            connectJob = scope.launch { runLoop() }
        }
    }

    private fun sendOpen(target: DshMuxLink, handle: DshStreamHandle) {
        target.open(handle.streamId, handle.endpoint, handle.args)
    }

    /** 一条逻辑流的句柄。 */
    private class DshStreamHandle(
        val streamId: String,
        val endpoint: String,
        val args: JSONObject,
        val onFrame: (JSONObject) -> Unit,
        val onEnd: (String?) -> Unit,
    ) {
        fun fail(reason: String) = onEnd(reason)
    }
}
