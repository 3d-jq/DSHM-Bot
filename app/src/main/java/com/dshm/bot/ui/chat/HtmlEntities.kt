package com.dshm.bot.ui.chat

/**
 * HTML 实体解码：模型回复里常带 `&#128075;`、`&amp;`、`&lt;` 这类转义。
 *
 * 不解码的话用户看到的是「在的 &#128075;」而不是「在的 👋」。
 */
internal fun decodeHtmlEntities(text: String): String {
    if (!text.contains('&')) return text
    val out = StringBuilder(text.length)
    var index = 0
    while (index < text.length) {
        val char = text[index]
        if (char != '&') {
            out.append(char)
            index += 1
            continue
        }
        // 实体最长 10 个字符（`&#x1F600;` 这类）。
        val limit = minOf(text.length, index + 10)
        val semicolon = text.indexOf(';', index + 1)
        if (semicolon < 0 || semicolon >= limit) {
            out.append(char)
            index += 1
            continue
        }
        val body = text.substring(index + 1, semicolon)
        val decoded = decodeEntity(body)
        if (decoded == null) {
            out.append(char)
            index += 1
        } else {
            out.append(decoded)
            index = semicolon + 1
        }
    }
    return out.toString()
}

/** 解一个实体（不含首尾的 `&` `;`）；不认识就返回 null。 */
private fun decodeEntity(body: String): String? = when {
    body.startsWith("#x") || body.startsWith("#X") ->
        body.drop(2).toIntOrNull(16)?.let { codePointToString(it) }
    body.startsWith("#") ->
        body.drop(1).toIntOrNull()?.let { codePointToString(it) }
    body == "amp" -> "&"
    body == "lt" -> "<"
    body == "gt" -> ">"
    body == "quot" -> "\""
    body == "apos" -> "'"
    body == "nbsp" -> " "
    else -> null
}

/** 码点转成字符（要按码点算，不然 emoji 会变成两个乱码字符）。 */
private fun codePointToString(codePoint: Int): String? {
    if (codePoint < 0 || codePoint > 0x10FFFF) return null
    return String(Character.toChars(codePoint))
}
