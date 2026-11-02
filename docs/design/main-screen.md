# 主界面设计说明（M0）

设计稿：会话列表式主界面（见 `docs/images/main-screen.png`）。

## 结构

```
┌────────────────────────────────────────┐
│  (头像)                    (搜索) (+)  │  顶栏 60dp，左右 16dp
├────────────────────────────────────────┤
│  (头像)  名字                     时间 │  列表行，最小高度 64dp
│          最后一句（单行省略）           │
│  ...                                   │
└────────────────────────────────────────┘
```

- 顶栏：左侧 44dp 圆形个人资料入口；右侧搜索、新建。三个按钮**填充色与页面底色一致**，
  靠四周阴影（`spacing.floatingElevation` 8dp）做出悬浮效果。
- 列表行：40dp 圆形头像（内部 18dp Lucide 图标）；名字 15sp Medium；时间 11sp 三级文字；
  最后一句 13sp 二级文字、单行省略。
- 搜索：点击搜索进入**独立搜索页**（顶部返回 + 自动聚焦搜索框 + 实时过滤结果），不是内联搜索条。
- 新建：点击「＋」在**按钮下方弹出菜单**（新建 Bot / 新建群聊，`DropdownMenu` 自带出现动画），不是底部弹层。
- 空态：无数据 / 无搜索结果 / 加载失败三种文案，居中显示。

## 令牌

全部来自 `core/design`：

| 令牌 | 值 |
|---|---|
| `colors.background` | 浅色 `#FFFFFF` / 深色 `#0E0F12` |
| `colors.textPrimary` | `#0B0B0F` / `#F2F3F5` |
| `colors.textSecondary` | `#8A8F98` / `#9AA0AA` |
| `colors.textTertiary` | `#B3B8C0` / `#6C727C` |
| `colors.shadow` | `#66000000` / `#CC000000` |
| `spacing.avatar` | 40dp |
| `spacing.avatarGlyph` | 18dp |
| `spacing.rowMinHeight` | 64dp |
| `spacing.floatingElevation` | 8dp |
| `typography.rowTitle` | 15sp / 20sp，Medium |
| `typography.rowPreview` | 13sp / 18sp |
| `typography.rowTime` | 11sp / 14sp |

颜色不允许在界面代码里写死；由 `DesignTokensTest` 校验对比度与尺寸关系。

## 图标

界面图标统一使用 Lucide（ISC），由 `tools/fetch_lucide_icons.py` 从钉死版本（1.66.0）转换成
Android VectorDrawable，产物提交在 `app/src/main/res/drawable/ic_lucide_*.xml`，
由 `LucideIconResourcesTest` 守护。

## 头像

M0 用 DS Bot 的 Bot 颜色（`BotInks.color`，算法与 DS Bot / dsh-Go 一致）+ Lucide 占位图标。
真实头像（DS Bot 角色图或用户照片）接入后替换 `BotAvatar` 的图标层，颜色算法不变。

## 时间文案

| 场景 | 文案 |
|---|---|
| 今天 | `21:53` |
| 昨天 | `昨天` |
| 2–6 天前 | `周一` |
| 今年内更早 | `10/21` |
| 跨年 | `2025/10/21` |

规则在 `RelativeTimeFormatter`，时区 / 时钟 / 语言注入，便于测试。
