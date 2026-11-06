package com.dshm.bot.data.runtime

/**
 * 运行时版本的显示名，对齐 dsh-Go `app/lib/ui/engine/engine_screen.dart` 的
 * `shortRuntimeVersion`：`rN-dsh…-HASH` → `rN · HASH8`，别的原样返回。
 *
 * 完整版本串很长（`r14-dsh0.2.1-rc.4-node24.22.1-ubuntu24.04…`），
 * 直接放进设置行会把标签挤扁。
 */
fun shortRuntimeVersion(version: String?): String {
    if (version.isNullOrEmpty()) return "—"
    val match = Regex("^(r\\d+)-.*-([0-9a-f]{8,})$").find(version) ?: return version
    return "${match.groupValues[1]} · ${match.groupValues[2].take(8)}"
}
