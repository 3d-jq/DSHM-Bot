package com.dshm.bot.ui.chat

import org.json.JSONObject

/**
 * 浏览器控制里那些「开发者能力」的脚本（对齐 DeepSearch `browser_use` 的同名动作）。
 *
 * 都是同步返回 `JSON.stringify(...)` 的小片段；异步的东西（`fetch`、等待稳定）
 * 用「先发起、再轮询」的方式在桥里做，因为 `evaluateJavascript` 不会等 Promise。
 */
internal object BrowserExtraScripts {

    /** 悬停：补发 mouseover/mousemove，供下拉菜单这类交互。 */
    fun hover(selector: String): String = """
        (function () {
          try {
            var selector = '${BrowserActionScripts.jsQuote(selector)}';
            var el = document.querySelector(selector);
            if (!el) return JSON.stringify({error: 'No element matches ' + selector});
            var bubbly = {bubbles: true, cancelable: true, view: window};
            var plain = {bubbles: false, cancelable: true, view: window};
            el.dispatchEvent(new MouseEvent('mouseover', bubbly));
            el.dispatchEvent(new MouseEvent('mouseenter', plain));
            el.dispatchEvent(new MouseEvent('mousemove', bubbly));
            var label = (el.innerText || el.getAttribute('aria-label') || '').replace(/\s+/g, ' ').trim().slice(0, 80);
            return JSON.stringify({hovered: true, tag: el.tagName, text: label});
          } catch (error) {
            return JSON.stringify({error: String(error)});
          }
        })()
    """.trimIndent()

    /** 在页面里跑一段同步 JS，返回它的值（异步结果拿不到，会提示用别的动作）。 */
    fun execute(script: String): String = """
        (function () {
          try {
            var value = (0, eval)('${BrowserActionScripts.jsQuote(script)}');
            if (value && typeof value.then === 'function') {
              return JSON.stringify({error: 'That script returns a Promise; only a synchronous value can be read. Use browser_read on the page afterwards.'});
            }
            if (value === undefined) return JSON.stringify({result: null});
            if (typeof value === 'object') {
              var text = JSON.stringify(value);
              return JSON.stringify({result: text.length > 20000 ? text.slice(0, 20000) : text});
            }
            return JSON.stringify({result: String(value).slice(0, 20000)});
          } catch (error) {
            return JSON.stringify({error: String(error)});
          }
        })()
    """.trimIndent()

    /**
     * 页面骨架：标题、地标、各级标题、以及带选择器的控件。
     *
     * 比 DeepSearch 的 `get_backbone` 简单：不量每个元素的矩形，只给结构 + 可点的选择器，
     * 够模型判断页面长什么样、该点哪个。
     */
    fun backbone(): String = """
        (function () {
          try {
            function selectorOf(el) {
              if (el.id) return '#' + el.id;
              var parts = [];
              var cur = el;
              while (cur && cur.nodeType === 1 && parts.length < 3) {
                var tag = cur.tagName.toLowerCase();
                if (cur.id) { parts.unshift('#' + cur.id); break; }
                var parent = cur.parentElement;
                if (parent) {
                  var index = 1;
                  for (var i = 0; i < parent.children.length; i++) {
                    var sibling = parent.children[i];
                    if (sibling === cur) break;
                    if (sibling.tagName === cur.tagName) index++;
                  }
                  tag += ':nth-of-type(' + index + ')';
                }
                parts.unshift(tag);
                cur = parent;
              }
              return parts.join(' > ');
            }
            function flat(text) { return String(text == null ? '' : text).replace(/\s+/g, ' ').trim(); }
            var lines = [];
            lines.push('Title: ' + document.title);
            lines.push('URL: ' + location.href);
            var landmarks = document.querySelectorAll('header,nav,main,article,section[aria-label],aside,footer,form');
            var seen = 0;
            for (var i = 0; i < landmarks.length && seen < 30; i++) {
              var el = landmarks[i];
              var label = flat(el.getAttribute('aria-label') || el.getAttribute('role') || '');
              lines.push('Landmark: ' + el.tagName.toLowerCase() + (label ? ' (' + label + ')' : ''));
              seen++;
            }
            var headings = document.querySelectorAll('h1,h2,h3');
            for (var h = 0; h < headings.length && h < 40; h++) {
              var text = flat(headings[h].innerText);
              if (text) lines.push(headings[h].tagName + ': ' + text.slice(0, 120));
            }
            var controls = document.querySelectorAll('a[href],button,input,textarea,select,[role=button]');
            var listed = 0;
            for (var c = 0; c < controls.length && listed < 60; c++) {
              var node = controls[c];
              var name = flat(node.innerText || node.getAttribute('aria-label') || node.getAttribute('placeholder') || node.getAttribute('name') || '');
              var role = node.tagName.toLowerCase() + (node.getAttribute('type') ? '[' + node.getAttribute('type') + ']' : '');
              lines.push('Control: ' + role + ' "' + name.slice(0, 60) + '" -> ' + selectorOf(node));
              listed++;
            }
            return JSON.stringify({text: lines.join('\n'), truncated: controls.length > listed || headings.length > 40});
          } catch (error) {
            return JSON.stringify({error: String(error)});
          }
        })()
    """.trimIndent()

    /** 发起页面内的 fetch；结果放在 `window.__dshmFetch`，由桥轮询取回。 */
    fun fetchStart(url: String, method: String, body: String?): String = """
        (function () {
          try {
            window.__dshmFetch = null;
            var options = {method: '${jsMethod(method)}', credentials: 'include'};
            var payload = ${if (body == null) "null" else "'" + BrowserActionScripts.jsQuote(body) + "'"};
            if (payload !== null) {
              options.body = payload;
              options.headers = {'content-type': 'application/json'};
            }
            fetch('${BrowserActionScripts.jsQuote(url)}', options).then(function (response) {
              return response.text().then(function (text) {
                window.__dshmFetch = {
                  status: response.status,
                  ok: response.ok,
                  contentType: response.headers.get('content-type') || '',
                  text: text.length > 20000 ? text.slice(0, 20000) : text,
                  truncated: text.length > 20000
                };
              });
            }).catch(function (error) {
              window.__dshmFetch = {error: String(error)};
            });
            return JSON.stringify({started: true});
          } catch (error) {
            return JSON.stringify({error: String(error)});
          }
        })()
    """.trimIndent()

    private fun jsMethod(method: String): String =
        method.uppercase().filter { it in 'A'..'Z' }.take(10).ifEmpty { "GET" }

    /** 取 fetch 的结果；还没回来就是 null。 */
    fun fetchPoll(): String = """
        (function () {
          try {
            var value = window.__dshmFetch;
            if (value === undefined || value === null) return JSON.stringify({pending: true});
            window.__dshmFetch = null;
            return JSON.stringify({value: value});
          } catch (error) {
            return JSON.stringify({error: String(error)});
          }
        })()
    """.trimIndent()

    /** 页面「安静」探针：文本长度 / 节点数 / 高度都不再变就算稳定。 */
    fun probe(): String = """
        (function () {
          try {
            var body = document.body;
            return JSON.stringify({
              length: body ? (body.innerText || '').length : 0,
              nodes: document.querySelectorAll('*').length,
              height: body ? body.scrollHeight : 0
            });
          } catch (error) {
            return JSON.stringify({error: String(error)});
          }
        })()
    """.trimIndent()

    /** 往下一屏，返回新的滚动高度。 */
    fun scrollStep(pixels: Int): String = """
        (function () {
          try {
            window.scrollBy({top: $pixels, behavior: 'instant'});
            var body = document.body;
            return JSON.stringify({
              y: Math.round(window.scrollY || 0),
              height: body ? body.scrollHeight : 0,
              viewport: window.innerHeight || 0
            });
          } catch (error) {
            return JSON.stringify({error: String(error)});
          }
        })()
    """.trimIndent()
}
