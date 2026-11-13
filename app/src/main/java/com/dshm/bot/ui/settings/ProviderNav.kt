package com.dshm.bot.ui.settings

import com.dshm.bot.data.remote.ProviderModelSpec
import com.dshm.bot.data.remote.ProviderProfile

/**
 * 渠道子页面目标：列表 → 详情 → 编辑 → 模型设置。
 *
 * 这一层是页面**内部**的层级（不是 App 顶层路由），所以它自己负责返回：
 * 页面里注册内层 `BackHandler`，栈为空时（列表态）不拦截，交回顶层。
 */
internal sealed interface ProviderTarget {
    data class Detail(val route: String) : ProviderTarget

    data class Editor(val existing: ProviderProfile?) : ProviderTarget

    /**
     * 模型设置页。
     *
     * @param writes true = 从渠道详情页进来，保存直接写引擎；
     *   false = 从渠道表单进来，完成后把规格交回表单，跟着表单一起保存。
     */
    data class Model(
        val route: String,
        val model: ProviderModelSpec,
        val writes: Boolean,
    ) : ProviderTarget
}

/**
 * 渠道页的内层导航栈。
 *
 * 用栈而不是单个 `page`：返回永远是「弹一层」，所以
 * 详情 → 编辑 → 返回 回到**详情**，而不是跳回列表；
 * 列表「添加渠道」进的编辑页，返回则回到列表。保存/删除后的落点由调用方
 * 显式指定（保存成功回列表、详情内改模型成功回详情），不在这里猜。
 */
internal object ProviderNav {

    fun push(stack: List<ProviderTarget>, target: ProviderTarget): List<ProviderTarget> = stack + target

    /** 弹一层；已经在列表态时保持空栈。 */
    fun back(stack: List<ProviderTarget>): List<ProviderTarget> = stack.dropLast(1)

    /** 回到列表。 */
    fun reset(): List<ProviderTarget> = emptyList()

    fun current(stack: List<ProviderTarget>): ProviderTarget? = stack.lastOrNull()
}
