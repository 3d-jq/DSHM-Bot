package com.dshm.bot.data.settings

import com.dshm.bot.domain.model.AppPalette
import com.dshm.bot.domain.model.BotPrefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * 团队偏好的**进程内唯一真值**。
 *
 * 偏好由引擎持有（网页端和其它设备共用），但本机所有界面都要按它渲染：
 * 配色 / 强调色 / 气泡布局 / 内容宽度 / 动效档位 / 思考过程。设置页改完
 * 立刻写这里，全 App 同步生效；引擎没起来时保持默认值（网页端默认那套）。
 *
 * 只放内存：真值在引擎里，重新 `state` 一次就能恢复，不另存本地副本。
 */
object BotPrefsStore {

    private val _prefs = MutableStateFlow(BotPrefs())

    /** 当前团队偏好；界面直接 collect 它。 */
    val prefs: StateFlow<BotPrefs> = _prefs.asStateFlow()

    /** 引擎回报的新值（整份覆盖）。 */
    fun set(prefs: BotPrefs) {
        _prefs.value = prefs
    }

    /** 引擎断开 / 重启前清掉，回到默认值。 */
    fun clear() {
        _prefs.value = BotPrefs()
    }

    /**
     * 引擎的 `state.prefs` → 偏好。
     *
     * 字段和网页端 `GeneralPage` 同款（`client.js` 的 `setPrefs` 那一组）：
     * 认 `theme` / `accent` / `motion` / `layout` / `width` / `flatWidth` /
     * `thinking`；不认识的值退回默认值，宽度夹在 50..100。
     */
    fun parse(raw: JSONObject?): BotPrefs {
        if (raw == null) return BotPrefs()
        val motion = raw.optString("motion").takeIf { it == "quiet" || it == "lively" } ?: "normal"
        val layout = raw.optString("layout").takeIf { it == "flat" } ?: "bubble"
        return BotPrefs(
            theme = AppPalette.fromId(raw.optString("theme")).id,
            accent = raw.optString("accent").takeIf { it.matches(Regex("#[0-9a-fA-F]{6}")) },
            motion = motion,
            layout = layout,
            width = raw.optInt("width", 80).coerceIn(50, 100),
            flatWidth = raw.optInt("flatWidth", 100).coerceIn(50, 100),
            thinking = raw.optBoolean("thinking", false),
        )
    }
}
