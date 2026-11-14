package com.dshm.bot.service

import android.content.Context
import com.dshm.bot.data.remote.BotCache
import com.dshm.bot.data.remote.DshClients
import com.dshm.bot.data.remote.RemoteDshApi
import com.dshm.bot.data.runtime.EngineLog
import com.dshm.bot.data.runtime.RuntimeEndpoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 手机操控的窗口侧：跟 BrowserReadBridge 一样长轮询，只是执行的是
 * [PhoneControlBridge] 的无障碍动作。引擎重启或服务没开时退避重试。
 */
object PhoneReadBridge {
    suspend fun loop(endpoint: RuntimeEndpoint, context: Context) {
        PhoneControlSession.reset()
        val api = RemoteDshApi(DshClients.of(endpoint))
        val clientId = "android-phone-" + UUID.randomUUID()
        while (currentCoroutineContext().isActive) {
            val started = System.currentTimeMillis()
            val value = runCatching {
                api.botValue("phone-wait", JSONObject().put("clientId", clientId))
            }
            val items = value.getOrNull() as? JSONArray
            if (items == null) {
                val elapsed = System.currentTimeMillis() - started
                delay(if (elapsed < 1_000) 5_000 else 500)
                continue
            }
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                refreshBotCache(api, context, item.optString("botId"))
                answer(api, context, item)
            }
        }
    }

    private var cachedBotId: String? = null
    private var cachedAt = 0L

    /** 悬浮条要画对应 Bot 的头像；新 Bot/一分钟过期时补一次 state，不能拖慢每个动作。 */
    private suspend fun refreshBotCache(api: RemoteDshApi, context: Context, botId: String) {
        if (botId.isBlank()) return
        val now = System.currentTimeMillis()
        if (botId == cachedBotId && now - cachedAt < 60_000L) return
        cachedBotId = botId
        cachedAt = now
        val state = runCatching { api.botValue("state") }.getOrNull()
        if (state is JSONObject) runCatching { BotCache(context).save(state) }
    }

    private suspend fun answer(api: RemoteDshApi, context: Context, item: JSONObject) {
        val id = item.optString("id")
        if (id.isEmpty()) return
        // 动作里有耗时很长的（collect 多页滚动、截图），而
        // DshmAccessibilityService.dispatch() 会用 CountDownLatch 阻塞当前线程；
        // 放主线程会 ANR。PhoneOverlay / PhoneStage 内部自己 marshals 到主线程，
        // 这里放 IO 即可。
        val result = withContext(Dispatchers.IO) {
            PhoneControlBridge.handle(context, item)
        }
        val payload = JSONObject().put("id", id).put("ok", result.optBoolean("ok", false)).apply {
            if (result.has("result")) put("result", result.opt("result"))
            if (result.has("error")) put("error", result.optString("error"))
        }
        postResult(api, payload)
    }

    /** 回执丢了引擎那头只能干等到超时；失败记引擎日志并重试一次。 */
    private suspend fun postResult(api: RemoteDshApi, payload: JSONObject) {
        var attempt = 0
        while (attempt < 2) {
            val outcome = runCatching { api.botValue("phone-result", payload) }
            if (outcome.isSuccess) return
            EngineLog.log("phone-result 发送失败（第 ${attempt + 1} 次）：${outcome.exceptionOrNull()?.message}")
            attempt += 1
            if (attempt < 2) delay(1_000)
        }
    }
}
