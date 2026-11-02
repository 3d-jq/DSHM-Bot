# 设置页设计（参照 dsh-Go settings IA）

入口：主界面左上角「头像」圆钮 → 设置页（与其它页面一样走 iOS 整屏 push/pop）。

## 结构

- 顶部：返回圆钮 + 标题「设置」。
- 概览卡：Bot 图标 + 应用名 + 主机状态（本机 · 未连接 · DSH 未接入）。
- 按意图分组（每组一张圆角卡片，行左侧图标、右侧当前值）：

| 组 | 行 |
|---|---|
| AI | 模型（未设置）、权限（默认）、个性化（未设置） |
| 能力 | 手机操控（未开启）、联网搜索（未配置）、插件（0 个） |
| 外观 | 主题（跟随系统 / 浅色 / 深色）、对话字号（标准）、宠物（未设置） |
| 通用 | 界面（原生）、语言（跟随系统）、通知（已开启）、语音输入（自动）、性能（自动）、后台运行（需设置） |
| 主机 | 本机引擎（未运行）、引擎参数（默认） |
| 关于 | 版本（BuildConfig.VERSION_NAME）、DSH 版本（未接入）、开源许可 |

- 行图标 20dp（`spacing.settingsRowIcon`），与 15sp 文字垂直居中。
- 行高 ≥ 40dp，行间 1dp 发丝分割线（`colors.divider`）。
- **每个分组是一整块悬浮卡片**（不是按行拆）：`shadowCard` + `spacing.cardElevation = 16dp` 的柔和阴影，与顶栏/输入框的悬浮按钮同一质感。
- 「主题」是当前真实可用的设置：选择后写入 SharedPreferences 并全局生效。

## 数据与状态

- `domain/model/ThemeMode.kt`：SYSTEM / LIGHT / DARK，`isDark(systemInDark)`。
- `domain/repository/SettingsRepository.kt` + `data/settings/SharedPrefsSettingsRepository.kt`：主题持久化。
- `ui/settings/SettingsViewModel.kt`：`SettingsUiState`（themeMode）。
- 剩余行为 DSH 接入后的占位入口（点击提示「即将上线」）。

## 测试

- `ThemeModeTest`（JVM）：SYSTEM 跟随系统、LIGHT/DARK 覆盖系统。
- `SettingsScreenTest`（插桩）：分组与当前值渲染、选深色触发回调。
- `DshmBotAppNavigationTest.profileOpensSettingsAndPopsBack`：头像进入设置、返回主界面。

## 顶栏规范（必读，踩过的坑）

- edge-to-edge 下，子页 header **必须**放在 `Scaffold(topBar)` 里，并在 header 内加**一次** `statusBarsPadding()`。
- 内容区只消费 `innerPadding`，**禁止**再加 `statusBarsPadding()`；也不要让 header 跟内容一起滚动。
- 否则会出现顶栏被状态栏压住（重叠）或整体下移（双重 inset）。
- 新页面一律使用 `SettingsPageScaffold`（`ui/settings/SettingsPageScaffold.kt`），不要手写 Scaffold + 顶栏。
