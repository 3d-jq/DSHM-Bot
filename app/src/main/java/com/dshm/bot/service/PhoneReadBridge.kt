package com.dshm.bot.service

import android.content.Context
import com.dshm.bot.data.remote.DshClients
import com.dshm.bot.data.remote.RemoteDshApi
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
                answer(api, context, item)
            }
        }
    }

    private suspend fun answer(api: RemoteDshApi, context: Context, item: JSONObject) {
        val id = item.optString("id")
        if (id.isEmpty()) return
        val result = withContext(Dispatchers.Main) {
            PhoneControlBridge.handle(context, item)
        }
        runCatching {
            api.botValue(
                "phone-result",
                JSONObject().put("id", id).put("ok", result.optBoolean("ok", false)).apply {
                    if (result.has("result")) put("result", result.opt("result"))
                    if (result.has("error")) put("error", result.optString("error"))
                },
            )
        }
    }
}
