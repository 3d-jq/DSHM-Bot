package com.dshm.bot.ui.chat

import com.dshm.bot.data.remote.ChatTransport
import com.dshm.bot.data.remote.DshChatTransport
import com.dshm.bot.data.remote.DshClients
import com.dshm.bot.data.remote.DshMux
import com.dshm.bot.data.remote.DshRemoteApi
import com.dshm.bot.data.remote.RemoteDshApi
import com.dshm.bot.data.remote.RemoteSpeechRepository
import com.dshm.bot.data.remote.SpeechRepository
import com.dshm.bot.data.runtime.RuntimeEndpoint

/**
 * `BotChatViewModel` 的网络 / mux 依赖。
 *
 * 生产实现仍然全部从 `DshClients.of(endpoint)` 来；JVM 单测传假 `DshRemoteApi`
 * 和 `FakeMuxLink` 驱动的 [DshMux]，就能覆盖「事件流 → 缓存 → 卡片」这条接线。
 * 缓存 / 通知 / 手机操控这些 Android 依赖仍由 `appContext` 保护，测试不传即可。
 */
data class BotChatDependencies(
    val remote: DshRemoteApi?,
    val transport: ChatTransport?,
    val speech: SpeechRepository?,
    /** 非 null 时直接用这个 mux（测试）；null 时走 [com.dshm.bot.data.remote.DshMuxHub]。 */
    val mux: DshMux? = null,
) {
    companion object {
        fun production(endpoint: RuntimeEndpoint?): BotChatDependencies {
            val client = endpoint?.let { DshClients.of(it) }
            return BotChatDependencies(
                remote = client?.let { RemoteDshApi(it) },
                transport = client?.let { DshChatTransport(it) },
                speech = client?.let { RemoteSpeechRepository(it) },
                mux = null,
            )
        }
    }
}
