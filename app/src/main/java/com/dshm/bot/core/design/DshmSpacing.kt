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
    /** Markdown 表格：一格的最小宽度（内容多了整张表横向滚）。 */
    val tableCellMinWidth: Dp = 88.dp,
    /** Markdown 表格：一列的固定宽度。 */
    val tableCellWidth: Dp = 120.dp,
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
    /**
     * 输入框离**系统导航栏**的额外留白。
     *
     * 系统栏本身由 `navigationBarsPadding()` 处理，这里只加一点自己的间隙。
     */
    val composerBottomInset: Dp = 16.dp,
    /**
     * 输入框离**输入法**的额外留白。
     *
     * 输入法的高度本身由外层的 `imePadding()` 顶开，这里只加一点自己的间隙。
     */
    val composerImeGap: Dp = 4.dp,
    /** 输入框下方渐变遮罩的高斯模糊半径（`Modifier.blur`，API 31+ 生效）。 */
    val composerBlurRadius: Dp = 28.dp,
    /**
     * 最后一条气泡与输入框之间的可见间隙。
     *
     * 和气泡之间的间距一致 —— 气泡离输入框太远会看着「脱开」，
     * 最后一颗气泡和输入框看起来不是一组的。
     */
    val composerClearance: Dp = 8.dp,
    /**
     * 一轮对话里「用户气泡 → 大模型气泡」之间的额外间距。
     *
     * 同一个人的连续气泡之间只留 [sm]（8dp），换人说话时多留一点，
     * 让「谁在说话」一眼能分出来。
     */
    val turnGap: Dp = 12.dp,
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
    /** 引擎状态环的描边宽度（dsh-Go 的 EngineRing）。 */
    val ringStroke: Dp = 2.5.dp,
    /** 日志井的最小高度。 */
    val logWellMin: Dp = 260.dp,
    /** 日志井的最大高度。 */
    val logWellMax: Dp = 520.dp,
)

val LocalDshmSpacing = staticCompositionLocalOf { DshmSpacing() }
