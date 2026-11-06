package com.dshm.bot.domain.model

/**
 * DS Bot 给没有自定义头像的 Bot 选角色形状的规则（对齐 `ds-bot/client.js` 的
 * `defaultShape(id)`）：由 Bot id 做 32 位混合，从 7 个默认形状里取一个。
 * 同一个 Bot 在所有设备上得到同一个形状。
 */
object BotShapeDefaults {
    val shapes: List<String> = listOf("star", "moon", "gumdrop", "peanut", "bunny", "kitty", "acorn")

    fun shapeFor(id: String): String {
        var value = fnv1a(id)
        value = value xor (value ushr 16)
        value *= 73244475
        value = value xor (value ushr 13)
        value *= -1028477387 // 3266489909 as a signed Int
        val index = ((value xor (value ushr 16)).toUInt() % shapes.size.toUInt()).toInt()
        return shapes[index]
    }

    private fun fnv1a(text: String): Int {
        var value = -2128831035 // 2166136261 as a signed Int
        for (char in text) {
            value = value xor char.code
            value *= 16777619
        }
        return value
    }
}
