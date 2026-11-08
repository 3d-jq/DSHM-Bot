package com.dshm.bot.ui.chat

import org.json.JSONObject

/**
 * 点 / 填之前的检查（对齐 DeepSearch 的 `BrowserGuard`）。
 *
 * 两条规则：
 * - **填密码 / 验证码**：直接拒绝，让模型改用 `browser_hand_over` 交给用户自己填。
 * - **点付款 / 发送 / 删除这类**：先弹一次确认，用户点「允许一次」才真的点。
 *
 * 判定是纯函数，脚本单独放在 [BrowserGuardScripts]，两边都能单测。
 */
internal sealed interface GuardVerdict {
    /** 照做。 */
    data object Proceed : GuardVerdict

    /** 拒绝，并把原因交给模型。 */
    data class Refuse(val reason: String) : GuardVerdict

    /** 先问用户。 */
    data class Ask(val reason: String) : GuardVerdict
}

internal object BrowserGuardRules {

    /** 密码 / 验证码 / 一次性口令这类字段。 */
    private val SECRET_FIELD = Regex(
        "pass(word|wd)?|pwd|otp|one[-_ ]?time|verification|security[-_ ]?code|" +
            "验证码|密码|口令|动态码|校验码",
        RegexOption.IGNORE_CASE,
    )

    /** 点了会花钱 / 发东西 / 删东西的按钮。 */
    private val DANGEROUS = Regex(
        "\\b(pay|payment|purchase|checkout|transfer|send|delete|remove|unsubscribe|confirm)\\b|" +
            "支付|付款|购买|下单|结算|转账|发送|删除|移除|退订|确认支付",
        RegexOption.IGNORE_CASE,
    )

    fun verdict(action: String, target: JSONObject): GuardVerdict {
        // 元素没找到就别拦，让动作自己回「找不到」。
        if (!target.optBoolean("found", false)) return GuardVerdict.Proceed
        if (action == "type" && isSecretField(target)) {
            return GuardVerdict.Refuse(
                "That looks like a password or verification-code field, which a Bot must not fill. " +
                    "Use browser_hand_over so the user types it themselves.",
            )
        }
        if (action == "click") {
            val label = listOf("label", "text", "value")
                .map { target.optString(it).trim() }
                .firstOrNull { it.isNotEmpty() }
                .orEmpty()
            if (label.isNotEmpty() && DANGEROUS.containsMatchIn(label)) {
                return GuardVerdict.Ask("「$label」看起来会付款 / 发送 / 删除。")
            }
        }
        return GuardVerdict.Proceed
    }

    private fun isSecretField(target: JSONObject): Boolean {
        val type = target.optString("type").lowercase()
        if (type == "password") return true
        val autocomplete = target.optString("autocomplete").lowercase()
        if (autocomplete.contains("password") || autocomplete.contains("one-time-code")) return true
        val haystack = listOf("name", "id", "placeholder", "label", "autocomplete")
            .joinToString(" ") { target.optString(it) }
        return SECRET_FIELD.containsMatchIn(haystack)
    }
}

/** 动作前读一眼目标元素：是不是密码框、按钮上写的是什么。 */
internal object BrowserGuardScripts {

    fun inspect(selector: String): String = """
        (function () {
          try {
            var selector = '${BrowserActionScripts.jsQuote(selector)}';
            var el = document.querySelector(selector);
            if (!el) return JSON.stringify({found: false});
            var text = (el.innerText || '').replace(/\s+/g, ' ').trim();
            var aria = el.getAttribute('aria-label') || '';
            var title = el.getAttribute('title') || '';
            return JSON.stringify({
              found: true,
              tag: el.tagName,
              type: (el.getAttribute('type') || '').toLowerCase(),
              name: el.getAttribute('name') || '',
              id: el.id || '',
              placeholder: el.getAttribute('placeholder') || '',
              autocomplete: (el.getAttribute('autocomplete') || '').toLowerCase(),
              inputMode: (el.getAttribute('inputmode') || '').toLowerCase(),
              label: (aria || title || text || '').slice(0, 120),
              text: text.slice(0, 120),
              value: (typeof el.value === 'string' ? el.value : '').slice(0, 120),
              href: el.getAttribute('href') || ''
            });
          } catch (error) {
            return JSON.stringify({found: false, error: String(error)});
          }
        })()
    """.trimIndent()
}

/** 等待用户点「允许一次」的确认框（全局一份，面板没开着也能弹）。 */
internal data class GuardRequest(
    val botId: String,
    val action: String,
    val detail: String,
    val reason: String,
)
