package com.dshm.bot.core.design

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 间距令牌：所有 padding / 尺寸都从这里取，界面里不写裸 dp 数字。 */
@Immutable
data class DshmSpacing(
    val xxs: Dp = 2.dp,
    /** 零间距：需要「不要留白」时也走令牌，不在界面里写裸 0.dp。 */
    val none: Dp = 0.dp,
    /** 设置表单输入框的统一高度：所有输入框等高，尾部控件不再撑高框。 */
    val inputHeight: Dp = 44.dp,
    val xs: Dp = 4.dp,
    val sm: Dp = 8.dp,
    val md: Dp = 12.dp,
    val lg: Dp = 16.dp,
    val xl: Dp = 20.dp,
    val xxl: Dp = 24.dp,
    val xxxl: Dp = 32.dp,
    val topBarHeight: Dp = 60.dp,
    val avatar: Dp = 44.dp,
    /** 设置首页账号卡头像。 */
    val settingsAvatar: Dp = 56.dp,
    val avatarGlyph: Dp = 20.dp,
    val icon: Dp = 24.dp,
    val topBarIcon: Dp = 20.dp,
    val iconButton: Dp = 44.dp,
    val rowMinHeight: Dp = 64.dp,
    val listBottomPadding: Dp = 24.dp,
    /** 顶栏悬浮按钮的阴影高度。 */
    val floatingElevation: Dp = 10.dp,
    /** 对话顶栏：名字胶囊叠入头像的量（头像罩住胶囊顶部一小段）。 */
    val capsuleOverlap: Dp = 6.dp,
    /** 输入框圆键边长（＋ / 发送）。 */
    val composerKey: Dp = 40.dp,
    /** 输入框圆键与胶囊边缘的间隙。 */
    val composerKeyGap: Dp = 8.dp,
    /** 输入框胶囊最小高度（单行 = 圆键 40 + 间隙 8×2）。 */
    val composerMinHeight: Dp = 56.dp,
    /** 输入框文字上下内边距：让单行文字在 40dp 圆键高度内垂直居中（(40-23.2)/2≈8）。 */
    val composerTextPad: Dp = 8.dp,
    /** 输入框发送键图标尺寸（dsh-Go 为 22dp）。 */
    val composerSendIcon: Dp = 22.dp,
    /** 顶栏「＋」下拉菜单面板宽度（比默认宽一些）。 */
    val menuWidth: Dp = 220.dp,
    /** 设置页每行的图标尺寸（与 15sp 文字垂直居中对齐）。 */
    val settingsRowIcon: Dp = 20.dp,
    /** 设置卡片的悬浮投影高度（让整块卡片浮起来）。 */
    val cardElevation: Dp = 16.dp,
    /** 输入框胶囊的柔和投影高度（dsh-Go：24 模糊 / 下移 8 / 8% 阴影）。 */
    val composerElevation: Dp = 12.dp,
    /** 1dp 细线（描边、分割线）。 */
    val hairline: Dp = 1.dp,
    /** 输入框底部安全区留白（与键盘/系统栏之间）。 */
    val composerBottomInset: Dp = 8.dp,
    /** 宠物挂件距屏幕右侧的外边距。 */
    val petInsetEnd: Dp = 16.dp,
    /** 宠物挂件距屏幕底部的外边距：抬到输入器上方，避免遮挡发送键。 */
    val petInsetBottom: Dp = 96.dp,
    /** 状态点直径（dsh-Go 的 8dp 渠道状态点）。 */
    val statusDot: Dp = 8.dp,
    /** 快捷胶囊高度（容量预设、档位筛选）。 */
    val capsuleHeight: Dp = 32.dp,
    /** 分段控件高度。 */
    val segmentedHeight: Dp = 36.dp,
    /** 分段滑块与底槽四周的间隙。 */
    val segmentedInset: Dp = 3.dp,
    /** 档位行高度（思考深度多选）。 */
    val levelRowHeight: Dp = 44.dp,
    /** 勾选圆点直径。 */
    val selectionTick: Dp = 20.dp,
)

val LocalDshmSpacing = staticCompositionLocalOf { DshmSpacing() }
