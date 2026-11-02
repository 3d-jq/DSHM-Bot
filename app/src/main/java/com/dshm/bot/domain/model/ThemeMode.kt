package com.dshm.bot.domain.model

/** 界面主题模式（设置 → 外观 → 主题）。 */
enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK;

    /** 结合系统当前深浅色，得出实际是否使用深色。 */
    fun isDark(systemInDark: Boolean): Boolean = when (this) {
        SYSTEM -> systemInDark
        LIGHT -> false
        DARK -> true
    }
}
