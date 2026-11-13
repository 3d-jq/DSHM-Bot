package com.dshm.bot.domain.model

/**
 * 团队偏好的配色方案（`prefs.theme`）。
 *
 * 网页端是 4 套（`client.js` 的 `themes.js`）：`deepseek` / `mono` / `paper` /
 * `moonlight`。`deepseek` 在本机用的是原生令牌那套（团队不动主题时界面和
 * 现在一模一样），另外三套是网页端的原值。
 *
 * 放在 domain 层：引擎侧读偏好（`data/settings`）和界面侧上色
 * （`core/design`）都要认这套 id。
 */
enum class AppPalette(val id: String) {
    DEEPSEEK("deepseek"),
    MONO("mono"),
    PAPER("paper"),
    MOONLIGHT("moonlight"),
    ;

    companion object {
        /** 网页端 id → 配色；不认识的名字回落到默认那套。 */
        fun fromId(id: String?): AppPalette =
            entries.firstOrNull { it.id == id } ?: DEEPSEEK
    }
}
