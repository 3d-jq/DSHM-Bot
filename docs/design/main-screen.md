# 主界面设计说明

设计稿：会话列表式主界面。旧截图已过时，暂不在 README 展示，后续更新。

> 2026-11-05 校订：本页原始数值抄自 M0 实现，后续令牌调整后已经漂移。
> **本页只引用令牌名，不再重复抄数字**；具体数值一律以 `core/design/` 为准，由 `DesignTokensTest` 守护。
> 数据层也已从 `DemoBotRepository` 换成 `DsBotRepository`（真实 `state` + `feed` SSE），不再是演示数据。

## 结构

```
┌────────────────────────────────────────┐
│  (头像)                    (搜索) (+)  │  顶栏 spacing.topBarHeight，左右 spacing.lg
├────────────────────────────────────────┤
│  (头像)  名字                     时间 │  列表行，最小高度 spacing.rowMinHeight
│          最后一句（单行省略）           │
│  ...                                   │
└────────────────────────────────────────┘
```

- 顶栏：左侧 `spacing.iconButton` 圆形个人资料入口；右侧搜索、新建。三个按钮**填充色与页面底色一致**，
  靠四周阴影（`spacing.floatingElevation`）做出悬浮效果。
- 列表行：头像用 `spacing.avatar` / 内部图标 `spacing.avatarGlyph`；名字 `typography.rowTitle`；
  时间 `typography.rowTime` + `colors.textTertiary`；最后一句 `typography.rowPreview` + `colors.textSecondary`，
  单行省略。
- 搜索：点击搜索进入**独立搜索页**（顶部返回 + 自动聚焦搜索框 + 实时过滤结果），不是内联搜索条。
- 新建：点击「＋」在**按钮下方弹出菜单**（新建 Bot / 新建群聊，`DropdownMenu` 自带出现动画），不是底部弹层。
  注意：两个创建动作目前仍是空回调，见 [../roadmap.md](../roadmap.md) M2。
- 空态：无数据 / 无搜索结果 / 引擎未启动三种文案，居中显示。

## 令牌

全部来自 `core/design`，界面代码不允许出现颜色 / 间距 / 圆角 / 字号字面量：

| 用途 | 令牌 |
|---|---|
| 页面底色 / 主文字 / 次级文字 / 三级文字 | `colors.pageBackground` / `colors.textPrimary` / `colors.textSecondary` / `colors.textTertiary` |
| 阴影 / 分割线 | `colors.shadow` / `colors.shadowCard` / `colors.divider` |
| 头像 / 图标 / 行高 | `spacing.avatar` / `spacing.avatarGlyph` / `spacing.rowMinHeight` |
| 悬浮高度 / 顶栏高度 | `spacing.floatingElevation` / `spacing.topBarHeight` |
| 列表文案 | `typography.rowTitle` / `typography.rowPreview` / `typography.rowTime` |

颜色与尺寸的可读性、尺寸单调性由 `DesignTokensTest` 校验。

## 图标

界面图标统一使用 Lucide（ISC），由 `tools/fetch_lucide_icons.py` 从钉死版本转换成
Android VectorDrawable，产物提交在 `app/src/main/res/drawable/ic_lucide_*.xml`，
由 `LucideIconResourcesTest` 守护。

## 头像

用 DS Bot 的 Bot 颜色（`BotInks.color`，算法与 DS Bot / dsh-Go 一致）+ `BotShapeDefaults.shapeFor(id)`
决定默认角色形状；服务端返回自定义 `avatar.image` / `avatar.shape` 时优先用服务端的。
`BotAvatar` / `BotShapeSpecs` 负责渲染，`BotShapeDefaultsTest` 守护默认形状。

## 时间文案

| 场景 | 文案 |
|---|---|
| 今天 | `21:53` |
| 昨天 | `昨天` |
| 2–6 天前 | `周一` |
| 今年内更早 | `10/21` |
| 跨年 | `2025/10/21` |

规则在 `RelativeTimeFormatter`，时区 / 时钟 / 语言注入，便于测试。
