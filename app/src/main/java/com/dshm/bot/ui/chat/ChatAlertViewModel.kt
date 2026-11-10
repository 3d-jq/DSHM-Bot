package com.dshm.bot.ui.chat

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.dshm.bot.R
import com.dshm.bot.data.notify.ChatNotifier
import com.dshm.bot.data.remote.DshClients
import com.dshm.bot.data.remote.DshHostEvent
import com.dshm.bot.data.remote.DshMuxHub
import com.dshm.bot.data.remote.RemoteDshApi
import com.dshm.bot.data.runtime.RuntimeEndpoint
import com.dshm.bot.data.settings.SharedPrefsSettingsRepository
import com.dshm.bot.domain.model.DshGoPreferences
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * 全局对话告警：不依赖某个对话页是否打开，直接听本机引擎的 `$events` 主机流。
 *
 * - `waterfall approval/request` → 审批通知
 * - `waterfall user-questions/request` → 提问通知
 * - `emit api-session/status` 的 running true→false → 查 diagnostics 决定完成/失败
 *
 * 只负责后台系统通知；App 在前台时由各对话页自己的界面提示，不打扰。
 * 全局和页面内可能同时看到同一次事件，[ChatNotifier] 会用最近通知时间去重。
 */
class ChatAlertViewModel(
    context: Context,
    private val endpoint: RuntimeEndpoint?,
) : ViewModel() {

    private val appContext = context.applicationContext
    private val repository = SharedPrefsSettingsRepository(appContext)
    private val api = endpoint?.let { RemoteDshApi(DshClients.of(it)) }
    private var notifyEnabled = true
    private var prefs = DshGoPreferences()

    private val running = HashMap<String, Boolean>()
    private val runningSince = HashMap<String, Long>()
    private val names = HashMap<String, String>()
    private val notifiedEvents = HashSet<String>()

    init {
        viewModelScope.launch {
            combine(
                repository.observeNotificationsEnabled(),
                repository.observeDshGoPreferences(),
            ) { enabled, value -> enabled to value }
                .collect { (enabled, value) ->
                    notifyEnabled = enabled
                    prefs = value
                }
        }
        if (endpoint != null) {
            viewModelScope.launch {
                val mux = DshMuxHub.start(endpoint)
                launch {
                    mux.generations.collect {
                        mux.pendingRequests().forEach { pending ->
                            pending.approval?.let(::notifyApproval)
                            pending.question?.let(::notifyQuestion)
                        }
                    }
                }
                mux.hostEvents.collect(::handleEvent)
            }
        }
    }

    private fun handleEvent(event: DshHostEvent) {
        when (event) {
            is DshHostEvent.Waterfall -> {
                event.approval?.let(::notifyApproval)
                event.question?.let(::notifyQuestion)
            }
            is DshHostEvent.Cancelled -> notifiedEvents.remove(event.eventId)
            is DshHostEvent.Emit -> handleEmit(event)
            is DshHostEvent.Ready, is DshHostEvent.Unknown -> Unit
        }
    }

    private fun handleEmit(event: DshHostEvent.Emit) {
        when (event.event) {
            "api-session/status" -> {
                val id = event.args.optString(0).takeIf { it.isNotEmpty() } ?: return
                setRunning(id, event.args.optBoolean(1, false))
            }
            "api-session/added" -> {
                val summary = event.args.optJSONObject(0) ?: return
                val id = summary.optString("sessionId").takeIf { it.isNotEmpty() } ?: return
                rememberName(id, summary)
                setRunning(id, summary.optBoolean("running", false))
            }
        }
    }

    private fun setRunning(id: String, value: Boolean) {
        val was = running[id] ?: false
        running[id] = value
        if (value) {
            runningSince.putIfAbsent(id, System.currentTimeMillis())
            return
        }
        if (!was) return
        val since = runningSince.remove(id) ?: return
        viewModelScope.launch { finishRun(id, since) }
    }

    private suspend fun finishRun(id: String, since: Long) {
        if (!notifyEnabled || !prefs.notifyTurnDone && !prefs.notifyError) return
        if (ChatNotifier.wasNotifiedRecently(id, ChatNotifier.Kind.DONE) ||
            ChatNotifier.wasNotifiedRecently(id, ChatNotifier.Kind.ERROR)
        ) {
            return
        }
        val failure = detectFailure(id, since)
        val titleName = displayName(id)
        if (failure != null) {
            if (!prefs.notifyError) return
            ChatNotifier.notify(
                appContext,
                id,
                ChatNotifier.Kind.ERROR,
                appContext.getString(R.string.notify_error_title, titleName),
                preview(failure),
            )
        } else {
            if (!prefs.notifyTurnDone) return
            ChatNotifier.notify(
                appContext,
                id,
                ChatNotifier.Kind.DONE,
                appContext.getString(R.string.notify_done_title, titleName),
                appContext.getString(R.string.notify_done_body),
            )
        }
    }

    private suspend fun detectFailure(id: String, since: Long): String? {
        val report = runCatching { api?.bot("diagnostics") }.getOrNull() ?: return null
        val entries = report.optJSONArray("entries") ?: return null
        for (index in 0 until entries.length()) {
            val entry = entries.optJSONObject(index) ?: continue
            if (entry.optString("source") != "turn") continue
            val entrySession = entry.optString("sessionId")
            if (entrySession.isNotEmpty() && entrySession != id) continue
            val time = entry.optLong("time", 0L)
            if (time in 1 until since) continue
            return entry.optString("text").takeIf { it.isNotBlank() }
                ?: entry.optString("code").takeIf { it.isNotBlank() }
                ?: "the model call failed"
        }
        return null
    }

    private fun notifyApproval(request: com.dshm.bot.data.remote.DshApprovalRequest) {
        if (!notifyEnabled || !prefs.notifyApproval) return
        if (!notifiedEvents.add(request.eventId)) return
        ChatNotifier.notify(
            appContext,
            request.sessionId,
            ChatNotifier.Kind.APPROVAL,
            appContext.getString(R.string.notify_approval_title, displayName(request.sessionId)),
            preview(request.describe("zh")),
        )
    }

    private fun notifyQuestion(request: com.dshm.bot.data.remote.DshQuestionRequest) {
        if (!notifyEnabled || !prefs.notifyQuestion) return
        if (!notifiedEvents.add(request.eventId)) return
        val text = request.questions.firstOrNull()?.question.orEmpty()
        ChatNotifier.notify(
            appContext,
            request.sessionId,
            ChatNotifier.Kind.QUESTION,
            appContext.getString(R.string.notify_question_title, displayName(request.sessionId)),
            preview(text.ifBlank { appContext.getString(R.string.notify_done_body) }),
        )
    }

    private fun rememberName(id: String, summary: JSONObject) {
        val name = listOf("name", "title", "botName", "displayName")
            .firstNotNullOfOrNull { key -> summary.optString(key).takeIf { it.isNotBlank() } }
        if (name != null) names[id] = name
    }

    private fun displayName(id: String): String = names[id] ?: id

    private fun preview(text: String): String {
        val clean = text.replace('\n', ' ').trim()
        return if (clean.length <= 120) clean else clean.take(120) + "…"
    }

    companion object {
        fun factory(context: Context, endpoint: RuntimeEndpoint?): ViewModelProvider.Factory = viewModelFactory {
            initializer { ChatAlertViewModel(context, endpoint) }
        }
    }
}
