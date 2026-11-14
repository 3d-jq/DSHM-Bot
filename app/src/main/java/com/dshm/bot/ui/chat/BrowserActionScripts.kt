package com.dshm.bot.ui.chat

import android.webkit.WebView
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * Bot 浏览器页面的动作脚本（移植自 DeepSearch 的 `BrowserUseJS`）。
 *
 * 每个函数返回一段自执行 JS，`JSON.stringify` 一个结果对象：成功时是动作的细节，
 * 失败时是 `{error}`。动作只作用在用户眼前那个标签页上，且只由 Bot 主动调用。
 */
internal object BrowserActionScripts {

    /** 按请求建脚本；不认识的动作返回 null。 */
    fun script(kind: String, item: JSONObject): String? = when (kind) {
        "click" -> click(item.optString("selector"))
        "type" -> type(item.optString("selector"), item.optString("text"))
        "scroll" -> scroll(
            direction = item.optString("direction").ifEmpty { "down" },
            amount = item.optInt("amount", 600).coerceIn(0, 5000),
        )

        else -> null
    }

    fun click(selector: String): String = """
        (function () {
          try {
            var selector = '${jsQuote(selector)}';
            var el = document.querySelector(selector);
            if (!el) return JSON.stringify({error: 'No element matches ' + selector});
            if (el.disabled) return JSON.stringify({error: 'The element is disabled'});
            el.scrollIntoView({block: 'center', inline: 'center'});
            var rect = el.getBoundingClientRect();
            if (rect.width === 0 && rect.height === 0) return JSON.stringify({error: 'The element is not visible'});
            var bubbly = {bubbles: true, cancelable: true, view: window};
            var plain = {bubbles: false, cancelable: true, view: window};
            el.dispatchEvent(new MouseEvent('mouseover', bubbly));
            el.dispatchEvent(new MouseEvent('mouseenter', plain));
            el.dispatchEvent(new MouseEvent('mousemove', bubbly));
            el.dispatchEvent(new MouseEvent('mousedown', bubbly));
            el.dispatchEvent(new MouseEvent('mouseup', bubbly));
            if (typeof el.click === 'function') el.click();
            el.dispatchEvent(new MouseEvent('mouseleave', plain));
            el.dispatchEvent(new MouseEvent('mouseout', bubbly));
            var label = (el.innerText || el.value || el.getAttribute('aria-label') || '')
              .replace(/\s+/g, ' ').trim().slice(0, 80);
            return JSON.stringify({clicked: true, tag: el.tagName, text: label, url: location.href});
          } catch (error) {
            return JSON.stringify({error: String(error)});
          }
        })()
    """.trimIndent()

    fun type(selector: String, text: String): String = """
        (function () {
          try {
            var selector = '${jsQuote(selector)}';
            var el = document.querySelector(selector);
            if (!el) return JSON.stringify({error: 'No element matches ' + selector});
            if (el.disabled) return JSON.stringify({error: 'The element is disabled'});
            el.focus();
            var value = '${jsQuote(text)}';
            if (el.isContentEditable) {
              el.textContent = value;
            } else {
              // 走元素自己的 value setter：React / Vue 的受控输入才会收到这次变化。
              var proto = el.tagName === 'TEXTAREA' ? window.HTMLTextAreaElement.prototype
                : (el.tagName === 'INPUT' ? window.HTMLInputElement.prototype : null);
              var desc = proto ? Object.getOwnPropertyDescriptor(proto, 'value') : null;
              if (desc && desc.set) desc.set.call(el, value);
              else el.value = value;
            }
            el.dispatchEvent(new Event('input', {bubbles: true}));
            el.dispatchEvent(new Event('change', {bubbles: true}));
            var name = el.getAttribute('aria-label') || el.getAttribute('name') || el.placeholder || '';
            return JSON.stringify({typed: true, tag: el.tagName, name: name, length: value.length});
          } catch (error) {
            return JSON.stringify({error: String(error)});
          }
        })()
    """.trimIndent()

    fun scroll(direction: String, amount: Int): String = """
        (function () {
          try {
            var dir = '${jsQuote(direction)}';
            var amount = $amount;
            var height = Math.max(
              document.body ? document.body.scrollHeight : 0,
              document.documentElement ? document.documentElement.scrollHeight : 0
            );
            var from = window.scrollY || 0;
            var target = from;
            if (dir === 'top') target = 0;
            else if (dir === 'bottom') target = height;
            else if (dir === 'up') target = Math.max(0, from - amount);
            else target = Math.min(height, from + amount);
            window.scrollTo({top: target, behavior: 'smooth'});
            return JSON.stringify({
              scrolled: dir,
              from: Math.round(from),
              target: Math.round(target),
              height: Math.round(height)
            });
          } catch (error) {
            return JSON.stringify({error: String(error)});
          }
        })()
    """.trimIndent()

    /** 单引号字符串转义（含换行和 JS 的行分隔符）。 */
    internal fun jsQuote(value: String): String = buildString {
        value.forEach { ch ->
            when (ch) {
                '\\' -> append("\\\\")
                '\'' -> append("\\'")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\u2028' -> append("\\u2028")
                '\u2029' -> append("\\u2029")
                else -> append(ch)
            }
        }
    }
}

/** 页面脚本统一限时：页面 JS 卡死（死循环/渲染线程挂起）时按超时回 null，不拖死长轮询通道。 */
internal const val PAGE_SCRIPT_TIMEOUT_MS = 8_000L

/** 统一的限时包装（JVM 可测）：超时回 null，及时完成原样返回。 */
internal suspend fun <T> withPageScriptTimeout(block: suspend () -> T): T? =
    withTimeoutOrNull(PAGE_SCRIPT_TIMEOUT_MS) { block() }

/** 在页面上跑一段脚本，把 `JSON.stringify` 的结果取回来；超时返回 null（按「页面没回答」处理）。 */
internal suspend fun evaluatePageScript(webView: WebView, script: String): JSONObject? {
    val raw = withPageScriptTimeout {
        suspendCancellableCoroutine<String?> { continuation ->
            runCatching {
                webView.evaluateJavascript(script) { value ->
                    if (continuation.isActive) continuation.resumeWith(Result.success(value))
                }
            }.onFailure { error ->
                if (continuation.isActive) continuation.resumeWith(Result.failure(error))
            }
        }
    }
    return decodeJsJson(raw)
}
