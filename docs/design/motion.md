# 页面切换动效（iOS 风格整屏 push / pop）

实现位置：`DshmBotApp.kt`（`AnimatedContent`），令牌：`core/design/DshmMotion.kt`。

## 规则

| 场景 | 新页面 | 旧页面 |
|---|---|---|
| 进入对话 / 搜索（push） | 整屏从右侧滑入（450ms，快出慢入） | 向左退 25% 屏宽并淡出 |
| 返回主界面（pop） | 从左侧 25% 位置滑回（450ms） | 整屏向右滑出并淡出 |

主界面、搜索页、Bot 对话都由 `DshmBotApp` 的同一个 `AnimatedContent` 承载，统一使用这套转场。

系统返回键 / 返回手势：对话页用 `BackHandler` 拦截，回到主界面（不退出 App）；只有已经在主界面时返回才交给系统。

深色模式：转场容器（`AnimatedContent`）铺当前主题背景 `colors.background`，窗口底色与状态栏图标也跟随 App 主题，滑动过程中两页之间不会露出白色。

- 时长/曲线/视差比例全部来自 `DshmMotion`（`screenTransitionMs`、`fadeMs`、`standard`、`exit`、
  `outgoingParallaxFraction`），界面不写裸数值。
- 两页各自带顶栏，一起参与滑动，视觉上是"整页"切换；状态栏区域由各自页面的 `statusBarsPadding` 处理。

## 测试

- `DshmBotAppNavigationTest`（插桩）：主界面点 Bot → 等待对话页出现 → 点返回 → 等待对话页消失。
